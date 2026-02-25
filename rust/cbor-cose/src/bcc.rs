//! Boot Certificate Chain (BCC) spoofing for RKP.
//!
//! Generates a valid-looking BCC using purely random keys.
//! This allows the device to present a "valid" chain of trust rooted in a
//! randomly generated key, effectively creating a fresh identity.

use crate::cbor;
use crate::cbor::CborValue;
use coset::{
    iana, CborSerializable, CoseKey, CoseSign1, CoseSign1Builder, HeaderBuilder,
    TaggedCborSerializable,
};
use p256::ecdsa::{signature::Signer, SigningKey, VerifyingKey};
use p256::pkcs8::EncodePublicKey;
use rand_core::OsRng;
use std::borrow::Cow;

/// Generate a spoofed Boot Certificate Chain (BCC).
///
/// Creates a 2-node chain:
/// 1. Root (DK) - Self-signed, payload is DK public key.
/// 2. KeyMint (KM) - Signed by DK, payload is KM public key.
///
/// Returns the CBOR-encoded BCC array.
pub fn generate_spoofed_bcc() -> Vec<u8> {
    // 1. Generate Root Key (DK)
    let dk_private = SigningKey::random(&mut OsRng);
    let dk_public = VerifyingKey::from(&dk_private);

    // 2. Generate KeyMint Key (KM)
    let km_private = SigningKey::random(&mut OsRng);
    let km_public = VerifyingKey::from(&km_private);

    // 3. Create BCC[0]: Root -> Root (Self-signed)
    let dk_cose_key = public_key_to_cose_key(&dk_public);
    let bcc_0 = create_bcc_entry(&dk_private, &dk_cose_key, None);

    // 4. Create BCC[1]: Root -> KeyMint
    let km_cose_key = public_key_to_cose_key(&km_public);
    let bcc_1 = create_bcc_entry(&dk_private, &km_cose_key, None);

    // 5. Construct BCC Array
    let bcc_array = CborValue::Array(vec![
        CborValue::Raw(Cow::Owned(bcc_0.to_tagged_vec().unwrap())),
        CborValue::Raw(Cow::Owned(bcc_1.to_tagged_vec().unwrap())),
    ]);

    cbor::encode(&bcc_array)
}

/// Helper to convert p256 Public Key to COSE_Key structure.
fn public_key_to_cose_key(key: &VerifyingKey) -> CoseKey {
    let _encoded = key.to_public_key_der().unwrap();
    // P-256 point is last 64 bytes of SubjectPublicKeyInfo for uncompressed
    // (technically we should parse DER properly, but for P-256 it's fixed offset usually.
    // However, p256 crate provides encoded point directly via `to_encoded_point`)
    let point = key.to_encoded_point(false);
    let x = point.x().unwrap().as_slice();
    let y = point.y().unwrap().as_slice();

    coset::CoseKeyBuilder::new_ec2_pub_key(iana::EllipticCurve::P_256, x.to_vec(), y.to_vec())
        .build()
}

/// Create a COSE_Sign1 entry for the BCC.
///
/// # Arguments
/// * `signer_key` - The private key to sign this entry with.
/// * `payload_key` - The public key to be contained in the payload.
/// * `extra_payload` - Optional map to add to the payload (e.g. device info).
fn create_bcc_entry<'a>(
    signer_key: &SigningKey,
    payload_key: &CoseKey,
    _extra_payload: Option<CborValue<'a>>, // Unused for basic spoofing but kept for future
) -> CoseSign1 {
    // Payload is the COSE_Key of the next key
    let payload_bytes = payload_key.clone().to_vec().unwrap();

    let protected = HeaderBuilder::new()
        .algorithm(iana::Algorithm::ES256)
        .build();

    let builder = CoseSign1Builder::new()
        .protected(protected)
        .payload(payload_bytes);

    // Calculate signature
    // For COSE_Sign1, the signature is over the Sig_structure
    // coset handles this internally if we provide a closure or sign directly
    // but here we need to use p256 signer.

    // We use a workaround: construct the builder, then sign manually.
    // Actually coset's `create_signature` helper is useful here if we have a Signer.

    builder
        .create_signature(&[], |data| {
            let signature: p256::ecdsa::Signature = signer_key.sign(data);
            signature.to_vec()
        })
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_spoofed_bcc_structure() {
        let bcc_bytes = generate_spoofed_bcc();
        assert!(!bcc_bytes.is_empty());

        // Should be a CBOR array
        assert_eq!(bcc_bytes[0] & 0xE0, 0x80);
    }
}
