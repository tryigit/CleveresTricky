//! COSE structure helpers for Android Remote Key Provisioning (RKP).
//!
//! Implements COSE_Mac0 and related structures required for generating
//! MACed public keys and certificate request responses in the RKP protocol.
//!
//! Based on RFC 9052 (COSE) and Android RKP specification.

use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::borrow::Cow;

use crate::cbor::{self, CborValue};

type HmacSha256 = Hmac<Sha256>;

/// COSE algorithm identifier for HMAC-256/256.
const COSE_ALG_HMAC_256_256: i64 = 5;

/// COSE header label for algorithm.
const COSE_HEADER_ALG: i64 = 1;

/// COSE key type label.
const COSE_KEY_TYPE: i64 = 1;
/// COSE key type value for EC2.
const COSE_KEY_TYPE_EC2: i64 = 2;
/// COSE EC2 curve label.
const COSE_KEY_EC2_CRV: i64 = -1;
/// COSE EC2 X coordinate label.
const COSE_KEY_EC2_X: i64 = -2;
/// COSE EC2 Y coordinate label.
const COSE_KEY_EC2_Y: i64 = -3;
/// P-256 curve identifier.
const COSE_CRV_P256: i64 = 1;

/// Build the COSE_Mac0 MAC structure for computing the MAC tag.
///
/// MAC_structure = [
///   "MAC0",           // context
///   protected,        // serialized protected headers
///   external_aad,     // empty byte string
///   payload           // the payload bytes
/// ]
fn build_mac_structure(protected_headers: &[u8], payload: &[u8]) -> Vec<u8> {
    let structure = CborValue::Array(vec![
        CborValue::TextString(Cow::Borrowed("MAC0")),
        CborValue::ByteString(Cow::Borrowed(protected_headers)),
        CborValue::ByteString(Cow::Borrowed(&[])), // external_aad
        CborValue::ByteString(Cow::Borrowed(payload)),
    ]);
    cbor::encode(&structure)
}

/// Compute HMAC-SHA256 over the given data.
fn compute_hmac(key: &[u8], data: &[u8]) -> Result<Vec<u8>, CoseError> {
    let mut mac = HmacSha256::new_from_slice(key).map_err(|_| CoseError::InvalidKeyLength)?;
    mac.update(data);
    Ok(mac.finalize().into_bytes().to_vec())
}

/// Errors that can occur during COSE operations.
#[derive(Debug)]
pub enum CoseError {
    /// The HMAC key has an invalid length.
    InvalidKeyLength,
    /// The EC public key coordinates are invalid.
    InvalidPublicKey,
}

impl std::fmt::Display for CoseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CoseError::InvalidKeyLength => write!(f, "invalid HMAC key length"),
            CoseError::InvalidPublicKey => write!(f, "invalid EC public key coordinates"),
        }
    }
}

impl std::error::Error for CoseError {}

/// Encode protected headers for COSE_Mac0 (algorithm = HMAC-256/256).
fn encode_protected_headers() -> Vec<u8> {
    let headers = CborValue::Map(vec![(
        CborValue::from_int(COSE_HEADER_ALG),
        CborValue::from_int(COSE_ALG_HMAC_256_256),
    )]);
    cbor::encode(&headers)
}

/// Encode an EC P-256 public key as a COSE_Key structure.
///
/// # Arguments
/// * `x` - The X coordinate of the public key (32 bytes for P-256).
/// * `y` - The Y coordinate of the public key (32 bytes for P-256).
pub fn encode_cose_key(x: &[u8], y: &[u8]) -> Result<Vec<u8>, CoseError> {
    if x.is_empty() || y.is_empty() {
        return Err(CoseError::InvalidPublicKey);
    }
    let key = CborValue::Map(vec![
        (
            CborValue::from_int(COSE_KEY_TYPE),
            CborValue::from_int(COSE_KEY_TYPE_EC2),
        ),
        (
            CborValue::from_int(COSE_KEY_EC2_CRV),
            CborValue::from_int(COSE_CRV_P256),
        ),
        (
            CborValue::from_int(COSE_KEY_EC2_X),
            CborValue::ByteString(Cow::Borrowed(x)),
        ),
        (
            CborValue::from_int(COSE_KEY_EC2_Y),
            CborValue::ByteString(Cow::Borrowed(y)),
        ),
    ]);
    Ok(cbor::encode(&key))
}

/// Generate a COSE_Mac0 structure for a MACed public key.
///
/// COSE_Mac0 = [
///   protected,     // serialized protected headers
///   unprotected,   // empty map
///   payload,       // COSE_Key bytes
///   tag            // HMAC-SHA256 tag
/// ]
///
/// # Arguments
/// * `x` - EC P-256 public key X coordinate (32 bytes).
/// * `y` - EC P-256 public key Y coordinate (32 bytes).
/// * `hmac_key` - The HMAC-SHA256 key (typically 32 bytes).
pub fn generate_maced_public_key(
    x: &[u8],
    y: &[u8],
    hmac_key: &[u8],
) -> Result<Vec<u8>, CoseError> {
    let protected = encode_protected_headers();
    let payload = encode_cose_key(x, y)?;

    let mac_structure = build_mac_structure(&protected, &payload);
    let tag = compute_hmac(hmac_key, &mac_structure)?;

    let cose_mac0 = CborValue::Array(vec![
        CborValue::ByteString(Cow::Owned(protected)),
        CborValue::Map(vec![]), // unprotected headers (empty)
        CborValue::ByteString(Cow::Owned(payload)),
        CborValue::ByteString(Cow::Owned(tag)),
    ]);

    Ok(cbor::encode(&cose_mac0))
}

/// Create a DeviceInfo CBOR map matching the Android RKP DeviceInfo schema.
///
/// Generates a CBOR map with 11 entries (0xAB header) containing device
/// attestation properties needed for certificate requests.
///
/// # Arguments
/// * `brand` - Device brand (e.g., "google").
/// * `manufacturer` - Device manufacturer (e.g., "Google").
/// * `product` - Product name (e.g., "husky").
/// * `model` - Model name (e.g., "Pixel 8 Pro").
/// * `device` - Device codename (e.g., "husky").
pub fn create_device_info_cbor(
    brand: Option<&str>,
    manufacturer: Option<&str>,
    product: Option<&str>,
    model: Option<&str>,
    device: Option<&str>,
) -> Vec<u8> {
    let brand = brand.unwrap_or("google");
    let manufacturer = manufacturer.unwrap_or("Google");
    let product = product.unwrap_or("generic");
    let model = model.unwrap_or("Pixel");
    let device = device.unwrap_or("generic");

    let map = CborValue::Map(vec![
        (
            CborValue::TextString(Cow::Borrowed("brand")),
            CborValue::TextString(Cow::Borrowed(brand)),
        ),
        (
            CborValue::TextString(Cow::Borrowed("manufacturer")),
            CborValue::TextString(Cow::Borrowed(manufacturer)),
        ),
        (
            CborValue::TextString(Cow::Borrowed("product")),
            CborValue::TextString(Cow::Borrowed(product)),
        ),
        (
            CborValue::TextString(Cow::Borrowed("model")),
            CborValue::TextString(Cow::Borrowed(model)),
        ),
        (
            CborValue::TextString(Cow::Borrowed("device")),
            CborValue::TextString(Cow::Borrowed(device)),
        ),
        (
            CborValue::TextString(Cow::Borrowed("vb_state")),
            CborValue::TextString(Cow::Borrowed("green")),
        ),
        (
            CborValue::TextString(Cow::Borrowed("bootloader_state")),
            CborValue::TextString(Cow::Borrowed("locked")),
        ),
        (
            CborValue::TextString(Cow::Borrowed("vbmeta_digest")),
            CborValue::ByteString(Cow::Borrowed(&[0; 32])),
        ),
        (
            CborValue::TextString(Cow::Borrowed("os_version")),
            CborValue::TextString(Cow::Borrowed("15.0.0")),
        ),
        (
            CborValue::TextString(Cow::Borrowed("system_patch_level")),
            CborValue::UnsignedInt(20250205),
        ),
        (
            CborValue::TextString(Cow::Borrowed("vendor_patch_level")),
            CborValue::UnsignedInt(20250205),
        ),
    ]);

    cbor::encode(&map)
}

/// Create a certificate request response structure for RKP.
///
/// Structure: [version, keysToSign, challenge, deviceInfo]
///
/// # Arguments
/// * `maced_keys` - List of COSE_Mac0 encoded public keys.
/// * `challenge` - Server-provided challenge bytes.
/// * `device_info` - CBOR-encoded DeviceInfo map.
pub fn create_certificate_request_response(
    maced_keys: &[Vec<u8>],
    challenge: &[u8],
    device_info: &[u8],
) -> Vec<u8> {
    let keys_array: Vec<CborValue> = maced_keys
        .iter()
        .map(|k| CborValue::ByteString(Cow::Borrowed(k)))
        .collect();

    let response = CborValue::Array(vec![
        CborValue::UnsignedInt(3), // version
        CborValue::Array(keys_array),
        CborValue::ByteString(Cow::Borrowed(challenge)),
        CborValue::ByteString(Cow::Borrowed(device_info)),
    ]);

    cbor::encode(&response)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Test MACed public key generation produces valid COSE_Mac0 structure.
    /// Mirrors RkpInterceptorTest.testMacedPublicKeyGeneration.
    #[test]
    fn test_maced_public_key_generation() {
        let x = vec![0x01; 32]; // dummy X coordinate
        let y = vec![0x02; 32]; // dummy Y coordinate
        let hmac_key = vec![0x00; 32];

        let result = generate_maced_public_key(&x, &y, &hmac_key).unwrap();

        assert!(!result.is_empty(), "macedKey should not be empty");
        assert_eq!(
            result[0], 0x84,
            "should start with COSE array header (4 items)"
        );
    }

    /// Test multiple key generations produce unique outputs.
    /// Mirrors RkpInterceptorTest.testMacedPublicKeyMultipleGenerations.
    #[test]
    fn test_maced_public_key_multiple_generations() {
        let hmac_key = vec![0x00; 32];
        let mut keys = Vec::new();

        for i in 0u8..5 {
            let x = vec![i + 1; 32];
            let y = vec![i + 10; 32];
            let result = generate_maced_public_key(&x, &y, &hmac_key).unwrap();
            assert!(!result.is_empty());
            keys.push(result);
        }

        // Verify all keys are unique
        for i in 0..keys.len() {
            for j in (i + 1)..keys.len() {
                assert_ne!(keys[i], keys[j], "keys {} and {} should be unique", i, j);
            }
        }
    }

    /// Test DeviceInfo CBOR generation.
    /// Mirrors RkpInterceptorTest.testDeviceInfoCborGeneration.
    #[test]
    fn test_device_info_cbor_generation() {
        let info = create_device_info_cbor(
            Some("google"),
            Some("Google"),
            Some("husky"),
            Some("Pixel 8 Pro"),
            Some("husky"),
        );

        assert!(!info.is_empty(), "deviceInfo should not be empty");
        assert_eq!(
            info[0], 0xAB,
            "should start with CBOR map header for 11 items"
        );

        let content = String::from_utf8_lossy(&info);
        assert!(content.contains("google"), "should contain brand");
        assert!(content.contains("vb_state"), "should contain vb_state");
        assert!(content.contains("green"), "should contain green");
        assert!(content.contains("locked"), "should contain locked");
    }

    /// Test DeviceInfo with None values uses defaults.
    /// Mirrors RkpInterceptorTest.testDeviceInfoWithNullValues.
    #[test]
    fn test_device_info_with_null_values() {
        let info = create_device_info_cbor(None, None, None, None, None);

        assert!(!info.is_empty(), "deviceInfo should not be empty");

        let content = String::from_utf8_lossy(&info);
        assert!(content.contains("google"), "should contain default brand");
    }

    /// Test certificate request response generation.
    /// Mirrors RkpInterceptorTest.testCertificateRequestResponseGeneration.
    #[test]
    fn test_certificate_request_response_generation() {
        let x = vec![0x01; 32];
        let y = vec![0x02; 32];
        let hmac_key = vec![0x00; 32];

        let maced_key = generate_maced_public_key(&x, &y, &hmac_key).unwrap();
        let device_info = create_device_info_cbor(
            Some("google"),
            Some("Google"),
            Some("redfin"),
            Some("Pixel 5"),
            Some("redfin"),
        );
        let challenge = b"test_challenge";

        let response = create_certificate_request_response(&[maced_key], challenge, &device_info);

        assert!(!response.is_empty(), "response should not be empty");
        assert_eq!(
            response[0], 0x84,
            "should start with CBOR array header (4 items)"
        );
    }

    /// Test with multiple MACed keys.
    /// Mirrors RkpInterceptorTest.testCertificateRequestWithMultipleKeys.
    #[test]
    fn test_certificate_request_with_multiple_keys() {
        let hmac_key = vec![0x00; 32];
        let mut maced_keys = Vec::new();

        for i in 0u8..3 {
            let x = vec![i + 1; 32];
            let y = vec![i + 10; 32];
            let key = generate_maced_public_key(&x, &y, &hmac_key).unwrap();
            maced_keys.push(key);
        }

        let device_info = create_device_info_cbor(
            Some("google"),
            Some("Google"),
            Some("husky"),
            Some("Pixel 8 Pro"),
            Some("husky"),
        );

        let response =
            create_certificate_request_response(&maced_keys, b"multi_key_test", &device_info);

        assert!(!response.is_empty());
    }

    /// Test with empty challenge.
    /// Mirrors RkpInterceptorTest.testCertificateRequestWithEmptyChallenge.
    #[test]
    fn test_certificate_request_with_empty_challenge() {
        let x = vec![0x01; 32];
        let y = vec![0x02; 32];
        let hmac_key = vec![0x00; 32];

        let maced_key = generate_maced_public_key(&x, &y, &hmac_key).unwrap();
        let device_info = create_device_info_cbor(
            Some("google"),
            Some("Google"),
            Some("generic"),
            Some("Pixel"),
            Some("generic"),
        );

        let response = create_certificate_request_response(&[maced_key], &[], &device_info);

        assert!(!response.is_empty());
    }

    /// Test with empty keys list.
    /// Mirrors RkpInterceptorTest.testEmptyKeysList.
    #[test]
    fn test_empty_keys_list() {
        let device_info = create_device_info_cbor(
            Some("google"),
            Some("Google"),
            Some("generic"),
            Some("Pixel"),
            Some("generic"),
        );

        let response = create_certificate_request_response(&[], b"test", &device_info);

        assert!(!response.is_empty());
    }

    /// Test invalid key coordinates.
    #[test]
    fn test_invalid_key_coordinates() {
        let hmac_key = vec![0x00; 32];

        let result = generate_maced_public_key(&[], &[0x02; 32], &hmac_key);
        assert!(result.is_err(), "empty X should fail");

        let result = generate_maced_public_key(&[0x01; 32], &[], &hmac_key);
        assert!(result.is_err(), "empty Y should fail");
    }

    /// Test COSE_Key encoding produces valid CBOR map.
    #[test]
    fn test_cose_key_encoding() {
        let x = vec![0xAA; 32];
        let y = vec![0xBB; 32];

        let key_bytes = encode_cose_key(&x, &y).unwrap();
        assert!(!key_bytes.is_empty());

        // Should be a CBOR map with 4 entries: 0xA4
        assert_eq!(key_bytes[0], 0xA4);
    }

    /// Test HMAC tag is deterministic.
    #[test]
    fn test_hmac_deterministic() {
        let x = vec![0x01; 32];
        let y = vec![0x02; 32];
        let hmac_key = vec![0xAA; 32];

        let result1 = generate_maced_public_key(&x, &y, &hmac_key).unwrap();
        let result2 = generate_maced_public_key(&x, &y, &hmac_key).unwrap();

        assert_eq!(result1, result2, "same inputs should produce same output");
    }
}
