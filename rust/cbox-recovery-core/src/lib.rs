// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
#![forbid(unsafe_code)]

use aes_gcm::aead::{AeadInOut, KeyInit};
use aes_gcm::Aes256Gcm;
use cleverestricky_crypto_core::{CboxPayload, CryptoError};
use pbkdf2::{pbkdf2_hmac, sha2::Sha256 as Pbkdf2Sha256};
use serde::Deserialize;
use zeroize::{Zeroize, Zeroizing};

const KDF_ITERATIONS: u32 = 250_000;
pub const RECOVERY_KEY_BYTES: usize = 32;
const SALT_BYTES: usize = 16;
const IV_BYTES: usize = 12;
const TAG_BYTES: usize = 16;
const HEADER_BYTES: usize = 4 + 4 + SALT_BYTES + IV_BYTES;
const VERSION_LEGACY: u32 = 1;
const VERSION_CURRENT: u32 = 2;
const CBOX_MAGIC: [u8; 4] = *b"CBOX";
const MAX_CBOX_CIPHERTEXT_BYTES: usize = 10 * 1024 * 1024;
const MAX_CBOX_XML_UTF16_UNITS: usize = 10 * 1024 * 1024;
const MAX_CBOX_SIGNATURE_UTF16_UNITS: usize = 16 * 1024;
const MAX_CBOX_AUTHOR_UTF16_UNITS: usize = 1024;
const MAX_PASSWORD_UTF16_UNITS: usize = 1024;

#[derive(Deserialize)]
struct CboxJson {
    author: String,
    xml_content: String,
    signature: String,
    #[serde(default = "default_signature_version")]
    signature_version: SignatureVersion,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum SignatureVersion {
    Number(i64),
    Text(String),
}

fn default_signature_version() -> SignatureVersion {
    SignatureVersion::Number(1)
}

impl SignatureVersion {
    fn normalized(&self) -> Option<u8> {
        let value = match self {
            Self::Number(value) => *value,
            Self::Text(value) => value.parse::<i64>().ok()?,
        };
        u8::try_from(value)
            .ok()
            .filter(|value| (1..=2).contains(value))
    }
}

/// Derives the exact AES key for this CBOX salt. The result is source-salt specific and must be
/// wrapped at rest by the Android keystore boundary before persistence.
pub fn derive_recovery_key(
    bytes: &[u8],
    password: &str,
) -> Result<Zeroizing<Vec<u8>>, CryptoError> {
    validate_cbox(bytes)?;
    if password.encode_utf16().count() > MAX_PASSWORD_UTF16_UNITS {
        return Err(CryptoError::InvalidInput);
    }
    let salt = &bytes[8..8 + SALT_BYTES];
    let mut key = Zeroizing::new(vec![0u8; RECOVERY_KEY_BYTES]);
    pbkdf2_hmac::<Pbkdf2Sha256>(
        password.as_bytes(),
        salt,
        KDF_ITERATIONS,
        key.as_mut_slice(),
    );
    Ok(key)
}

/// Opens a CBOX with a source-specific recovery key without retaining or reconstructing the user's
/// original password. The encrypted input is always wiped before return.
pub fn decrypt_cbox_with_recovery_key(
    mut bytes: Vec<u8>,
    recovery_key: &[u8],
) -> Result<CboxPayload, CryptoError> {
    let result = decrypt_inner(&mut bytes, recovery_key);
    bytes.zeroize();
    result
}

fn decrypt_inner(bytes: &mut [u8], recovery_key: &[u8]) -> Result<CboxPayload, CryptoError> {
    validate_cbox(bytes)?;
    if recovery_key.len() != RECOVERY_KEY_BYTES {
        return Err(CryptoError::InvalidInput);
    }
    let version = read_version(bytes)?;
    let body_end = bytes
        .len()
        .checked_sub(TAG_BYTES)
        .ok_or(CryptoError::InvalidInput)?;

    let mut header = [0u8; HEADER_BYTES];
    header.copy_from_slice(&bytes[..HEADER_BYTES]);
    let mut iv: [u8; IV_BYTES] = header[8 + SALT_BYTES..]
        .try_into()
        .map_err(|_| CryptoError::InvalidInput)?;
    let mut tag_bytes = [0u8; TAG_BYTES];
    tag_bytes.copy_from_slice(&bytes[body_end..]);

    let cipher = Aes256Gcm::new_from_slice(recovery_key).map_err(|_| CryptoError::InvalidInput)?;
    let aad: &[u8] = if version == VERSION_CURRENT {
        &header
    } else {
        &[]
    };
    let decrypted = cipher.decrypt_inout_detached(
        (&iv).into(),
        aad,
        (&mut bytes[HEADER_BYTES..body_end]).into(),
        (&tag_bytes).into(),
    );
    iv.zeroize();
    tag_bytes.zeroize();
    header.zeroize();
    decrypted.map_err(|_| CryptoError::AuthenticationFailed)?;

    let decoded: CboxJson = serde_json::from_slice(&bytes[HEADER_BYTES..body_end])
        .map_err(|_| CryptoError::InvalidPayload)?;
    let signature_version = decoded
        .signature_version
        .normalized()
        .ok_or(CryptoError::InvalidPayload)?;
    if decoded.author.encode_utf16().count() > MAX_CBOX_AUTHOR_UTF16_UNITS
        || decoded.xml_content.encode_utf16().count() > MAX_CBOX_XML_UTF16_UNITS
        || decoded.signature.encode_utf16().count() > MAX_CBOX_SIGNATURE_UTF16_UNITS
    {
        return Err(CryptoError::InvalidPayload);
    }
    Ok(CboxPayload {
        author: decoded.author,
        xml_content: decoded.xml_content,
        signature_base64: decoded.signature,
        signature_version,
    })
}

fn validate_cbox(bytes: &[u8]) -> Result<(), CryptoError> {
    if bytes.len() < HEADER_BYTES + TAG_BYTES
        || bytes.len() > HEADER_BYTES + MAX_CBOX_CIPHERTEXT_BYTES
        || bytes[..4] != CBOX_MAGIC
    {
        return Err(CryptoError::InvalidInput);
    }
    read_version(bytes).map(|_| ())
}

fn read_version(bytes: &[u8]) -> Result<u32, CryptoError> {
    let input = bytes.get(4..8).ok_or(CryptoError::InvalidInput)?;
    match u32::from_be_bytes(input.try_into().map_err(|_| CryptoError::InvalidInput)?) {
        VERSION_LEGACY => Ok(VERSION_LEGACY),
        VERSION_CURRENT => Ok(VERSION_CURRENT),
        _ => Err(CryptoError::UnsupportedVersion),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;
    use cleverestricky_crypto_core::decrypt_cbox;

    const CBOX_V1: &str = "Q0JPWAAAAAEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobQrPWcdbGETfpef7mviy130oMGrIv/EwTlOVOuFIH5qfAaY+XUMc2qXWTgNu7FkkT/w9lEwrpv/iFQNyu/EsamoACXPaOVKKg+oGNsVLwNRNN4Gth46JQOziUU1/B3Fen+4BvKg9VtB9H4xnPi4AX+qMZHYhaW8ysgOQaSFcJy59C9IckzAalbsWXcjdsX8r1kr/KBOEALbqGa941n5vAlQEX5P77BBTF";
    const CBOX_V2: &str = "Q0JPWAAAAAIAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobQrPWcdbGETfpef7mviy130oMGrIv/EwTlOVOuFIH5qfAaY+XUMc2qXWTgNu7FkkT/w9lEwrpv/iFQNyu/EsamoACXPaOVKKg+oGNsVLwNRNN4Gth46JQOziUU1/B3Fen+4BvKg9VtB9H4xnPi4AX+qMZHYhaW8ysgOQaSFcJy59C9IckzAalbsWXcjdsX8r1kr/KBOEALbqYmlPfNbKQEZdEZacWRvO3";

    fn password() -> String {
        std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../testdata/crypto-golden-password.txt"
        ))
        .unwrap()
    }

    fn decode(input: &str) -> Vec<u8> {
        base64::engine::general_purpose::STANDARD
            .decode(input)
            .unwrap()
    }

    #[test]
    fn recovery_key_path_matches_password_path_for_v1_and_v2() {
        for encoded in [CBOX_V1, CBOX_V2] {
            let encrypted = decode(encoded);
            let key = derive_recovery_key(&encrypted, password().as_str()).unwrap();
            let recovered =
                decrypt_cbox_with_recovery_key(encrypted.clone(), key.as_slice()).unwrap();
            let normal = decrypt_cbox(encrypted, password().as_str()).unwrap();
            assert_eq!(recovered, normal);
        }
    }

    #[test]
    fn wrong_recovery_key_fails_authentication() {
        let encrypted = decode(CBOX_V2);
        let key = [0x5au8; RECOVERY_KEY_BYTES];
        assert_eq!(
            decrypt_cbox_with_recovery_key(encrypted, &key),
            Err(CryptoError::AuthenticationFailed)
        );
    }

    #[test]
    fn v2_source_header_replacement_invalidates_cached_key() {
        let mut encrypted = decode(CBOX_V2);
        let key = derive_recovery_key(&encrypted, password().as_str()).unwrap();
        encrypted[8] ^= 0x40;
        assert_eq!(
            decrypt_cbox_with_recovery_key(encrypted, key.as_slice()),
            Err(CryptoError::AuthenticationFailed)
        );
    }
}
