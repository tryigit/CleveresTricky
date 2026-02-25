//! CBOR encoder implementing RFC 8949 canonical encoding.
//!
//! Supports the subset required for COSE/RKP structures:
//! - Unsigned and negative integers
//! - Byte strings and text strings
//! - Arrays and maps (with deterministic key ordering)
//! - Simple values (null, true, false)

use std::borrow::Cow;
use std::cmp::Ordering;
use std::io::{self, Write};

// CBOR Major Types
const MT_UNSIGNED: u8 = 0;
const MT_NEGATIVE: u8 = 1;
const MT_BYTE_STRING: u8 = 2;
const MT_TEXT_STRING: u8 = 3;
const MT_ARRAY: u8 = 4;
const MT_MAP: u8 = 5;
const MT_TAG: u8 = 6;
const MT_SIMPLE: u8 = 7;

/// A CBOR value that can be encoded.
#[derive(Debug, Clone, PartialEq)]
pub enum CborValue<'a> {
    /// Unsigned integer (major type 0).
    UnsignedInt(u64),
    /// Negative integer (major type 1). Stored as the positive magnitude - 1.
    NegativeInt(i64),
    /// Byte string (major type 2).
    ByteString(Cow<'a, [u8]>),
    /// Text string (major type 3).
    TextString(Cow<'a, str>),
    /// Array of CBOR values (major type 4).
    Array(Vec<CborValue<'a>>),
    /// Map of CBOR key-value pairs (major type 5).
    /// Keys are sorted in canonical order during encoding.
    Map(Vec<(CborValue<'a>, CborValue<'a>)>),
    /// CBOR tag (major type 6).
    Tag(u64, Box<CborValue<'a>>),
    /// Boolean value.
    Bool(bool),
    /// Null value.
    Null,
    /// Raw encoded CBOR bytes (embedded directly).
    Raw(Cow<'a, [u8]>),
}

impl<'a> CborValue<'a> {
    /// Create from a signed integer, choosing unsigned or negative encoding.
    pub fn from_int(value: i64) -> Self {
        if value >= 0 {
            CborValue::UnsignedInt(value as u64)
        } else {
            CborValue::NegativeInt(value)
        }
    }
}

/// Encode a CBOR value to bytes.
pub fn encode(value: &CborValue) -> Vec<u8> {
    let mut buf = Vec::with_capacity(256);
    encode_item(&mut buf, value).expect("writing to Vec should not fail");
    buf
}

/// Encode a CBOR value to a writer.
pub fn encode_item<W: Write>(w: &mut W, value: &CborValue) -> io::Result<()> {
    match value {
        CborValue::UnsignedInt(v) => encode_type_and_length(w, MT_UNSIGNED, *v),
        CborValue::NegativeInt(v) => {
            let encoded = (-1 - *v) as u64;
            encode_type_and_length(w, MT_NEGATIVE, encoded)
        }
        CborValue::ByteString(bytes) => {
            encode_type_and_length(w, MT_BYTE_STRING, bytes.len() as u64)?;
            w.write_all(bytes)
        }
        CborValue::TextString(s) => {
            let bytes = s.as_bytes();
            encode_type_and_length(w, MT_TEXT_STRING, bytes.len() as u64)?;
            w.write_all(bytes)
        }
        CborValue::Array(items) => {
            encode_type_and_length(w, MT_ARRAY, items.len() as u64)?;
            for item in items {
                encode_item(w, item)?;
            }
            Ok(())
        }
        CborValue::Map(entries) => {
            encode_type_and_length(w, MT_MAP, entries.len() as u64)?;

            // Sort entries by canonical key ordering (RFC 8949 Section 4.2.1).
            let mut sorted: Vec<&(CborValue, CborValue)> = entries.iter().collect();
            sorted.sort_by(|a, b| canonical_key_cmp(&a.0, &b.0));

            for (key, val) in sorted {
                encode_item(w, key)?;
                encode_item(w, val)?;
            }
            Ok(())
        }
        CborValue::Tag(tag, inner) => {
            encode_type_and_length(w, MT_TAG, *tag)?;
            encode_item(w, inner)
        }
        CborValue::Bool(b) => encode_type_and_length(w, MT_SIMPLE, if *b { 21 } else { 20 }),
        CborValue::Null => encode_type_and_length(w, MT_SIMPLE, 22),
        CborValue::Raw(bytes) => w.write_all(bytes),
    }
}

/// Encode CBOR type and length directly to the writer.
///
/// Writes bytes directly using bit-shifting to avoid temporary allocations,
/// matching the optimization in the Java `CborEncoder.encodeTypeAndLength`.
fn encode_type_and_length<W: Write>(w: &mut W, major_type: u8, value: u64) -> io::Result<()> {
    let mt = major_type << 5;
    if value < 24 {
        w.write_all(&[mt | value as u8])
    } else if value <= 0xFF {
        w.write_all(&[mt | 24, value as u8])
    } else if value <= 0xFFFF {
        w.write_all(&[mt | 25, (value >> 8) as u8, value as u8])
    } else if value <= 0xFFFF_FFFF {
        w.write_all(&[
            mt | 26,
            (value >> 24) as u8,
            (value >> 16) as u8,
            (value >> 8) as u8,
            value as u8,
        ])
    } else {
        w.write_all(&[
            mt | 27,
            (value >> 56) as u8,
            (value >> 48) as u8,
            (value >> 40) as u8,
            (value >> 32) as u8,
            (value >> 24) as u8,
            (value >> 16) as u8,
            (value >> 8) as u8,
            value as u8,
        ])
    }
}

/// Compare CBOR map keys in canonical order (RFC 8949 Section 4.2.1).
///
/// Rules matching the Java `CborEncoder.EncodedEntry.compareTo`:
/// - Integer keys: Major type 0 (positive) < Major type 1 (negative).
///   Within same type, smaller absolute values first.
/// - String keys: Shorter strings first, then lexicographic byte comparison.
/// - Mixed: Integer keys come before string keys (lower major type first).
fn canonical_key_cmp(a: &CborValue, b: &CborValue) -> Ordering {
    match (a, b) {
        // Both integers
        (CborValue::UnsignedInt(a_val), CborValue::UnsignedInt(b_val)) => a_val.cmp(b_val),
        (CborValue::NegativeInt(a_val), CborValue::NegativeInt(b_val)) => {
            // Both negative: -1 (encoded as 0) < -2 (encoded as 1)
            // So we reverse the comparison (more negative = larger encoded value)
            b_val.cmp(a_val)
        }
        (CborValue::UnsignedInt(_), CborValue::NegativeInt(_)) => Ordering::Less,
        (CborValue::NegativeInt(_), CborValue::UnsignedInt(_)) => Ordering::Greater,

        // Handle from_int which stores small positive as UnsignedInt
        // and negative as NegativeInt - this is already covered above.

        // Both strings
        (CborValue::TextString(a_str), CborValue::TextString(b_str)) => {
            let a_bytes = a_str.as_bytes();
            let b_bytes = b_str.as_bytes();
            // Shorter length first
            match a_bytes.len().cmp(&b_bytes.len()) {
                Ordering::Equal => a_bytes.cmp(b_bytes),
                other => other,
            }
        }

        // Integer < String (lower major type)
        (CborValue::UnsignedInt(_) | CborValue::NegativeInt(_), CborValue::TextString(_)) => {
            Ordering::Less
        }
        (CborValue::TextString(_), CborValue::UnsignedInt(_) | CborValue::NegativeInt(_)) => {
            Ordering::Greater
        }

        // Fallback: compare by encoded bytes
        _ => {
            let a_enc = encode(a);
            let b_enc = encode(b);
            a_enc.cmp(&b_enc)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bytes_to_hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{:02x}", b)).collect()
    }

    // ============ Tests matching Java CborEncoderTest ============

    /// Mirrors Java `CborEncoderTest.testMapSortingOrder`.
    /// Map { 1: "a", -1: "b" }
    /// Expected: 1 (MT0) before -1 (MT1)
    #[test]
    fn test_map_sorting_order() {
        let value = CborValue::Map(vec![
            (CborValue::from_int(1), CborValue::TextString("a".into())),
            (CborValue::from_int(-1), CborValue::TextString("b".into())),
        ]);
        let encoded = encode(&value);

        // A2 01 61 61 20 61 62
        let expected: Vec<u8> = vec![0xA2, 0x01, 0x61, 0x61, 0x20, 0x61, 0x62];
        assert_eq!(
            bytes_to_hex(&encoded),
            bytes_to_hex(&expected),
            "CBOR Map sorting is incorrect!"
        );
    }

    /// Mirrors Java `CborEncoderTest.testStringMapSortingOrder`.
    /// Map { "aa": 1, "a": 2, "b": 3 }
    /// Expected sort: "a", "b", "aa" (shorter length first)
    #[test]
    fn test_string_map_sorting_order() {
        let value = CborValue::Map(vec![
            (CborValue::TextString("aa".into()), CborValue::from_int(1)),
            (CborValue::TextString("a".into()), CborValue::from_int(2)),
            (CborValue::TextString("b".into()), CborValue::from_int(3)),
        ]);
        let encoded = encode(&value);

        // A3 61 61 02 61 62 03 62 61 61 01
        let expected: Vec<u8> = vec![
            0xA3, 0x61, 0x61, 0x02, 0x61, 0x62, 0x03, 0x62, 0x61, 0x61, 0x01,
        ];
        assert_eq!(
            bytes_to_hex(&encoded),
            bytes_to_hex(&expected),
            "CBOR String Map sorting is incorrect!"
        );
    }

    /// Mirrors Kotlin `CborEncoderTest.testCanonicalMapSorting`.
    /// "b" (len 1) vs "aa" (len 2) => "b" < "aa"
    #[test]
    fn test_canonical_map_sorting() {
        let value = CborValue::Map(vec![
            (CborValue::TextString("aa".into()), CborValue::from_int(1)),
            (CborValue::TextString("b".into()), CborValue::from_int(2)),
        ]);
        let encoded = encode(&value);
        let expected = "a261620262616101";
        assert_eq!(
            bytes_to_hex(&encoded),
            expected,
            "Expected canonical sort order"
        );
    }

    /// Mirrors Kotlin `CborEncoderTest.testCanonicalMapSortingLongerKeys`.
    /// "manufacturer" (12) vs "vb_state" (8) => "vb_state" < "manufacturer"
    #[test]
    fn test_canonical_map_sorting_longer_keys() {
        let value = CborValue::Map(vec![
            (
                CborValue::TextString("manufacturer".into()),
                CborValue::from_int(1),
            ),
            (
                CborValue::TextString("vb_state".into()),
                CborValue::from_int(2),
            ),
        ]);
        let encoded = encode(&value);
        let expected = "a26876625f7374617465026c6d616e75666163747572657201";
        assert_eq!(
            bytes_to_hex(&encoded),
            expected,
            "Expected shorter key (vb_state) first"
        );
    }

    // ============ Basic type encoding tests ============

    #[test]
    fn test_encode_unsigned_integers() {
        // 0 => 0x00
        assert_eq!(encode(&CborValue::UnsignedInt(0)), vec![0x00]);
        // 1 => 0x01
        assert_eq!(encode(&CborValue::UnsignedInt(1)), vec![0x01]);
        // 23 => 0x17
        assert_eq!(encode(&CborValue::UnsignedInt(23)), vec![0x17]);
        // 24 => 0x18 0x18
        assert_eq!(encode(&CborValue::UnsignedInt(24)), vec![0x18, 0x18]);
        // 255 => 0x18 0xff
        assert_eq!(encode(&CborValue::UnsignedInt(255)), vec![0x18, 0xff]);
        // 256 => 0x19 0x01 0x00
        assert_eq!(encode(&CborValue::UnsignedInt(256)), vec![0x19, 0x01, 0x00]);
    }

    #[test]
    fn test_encode_negative_integers() {
        // -1 => 0x20
        assert_eq!(encode(&CborValue::from_int(-1)), vec![0x20]);
        // -2 => 0x21
        assert_eq!(encode(&CborValue::from_int(-2)), vec![0x21]);
        // -100 => 0x38 0x63
        assert_eq!(encode(&CborValue::from_int(-100)), vec![0x38, 0x63]);
    }

    #[test]
    fn test_encode_byte_string() {
        let bytes = vec![0x01, 0x02, 0x03];
        assert_eq!(
            encode(&CborValue::ByteString(Cow::Owned(bytes))),
            vec![0x43, 0x01, 0x02, 0x03]
        );
    }

    #[test]
    fn test_encode_text_string() {
        assert_eq!(
            encode(&CborValue::TextString("hello".into())),
            vec![0x65, b'h', b'e', b'l', b'l', b'o']
        );
    }

    #[test]
    fn test_encode_empty_array() {
        assert_eq!(encode(&CborValue::Array(vec![])), vec![0x80]);
    }

    #[test]
    fn test_encode_array() {
        let value = CborValue::Array(vec![
            CborValue::UnsignedInt(1),
            CborValue::UnsignedInt(2),
            CborValue::UnsignedInt(3),
        ]);
        assert_eq!(encode(&value), vec![0x83, 0x01, 0x02, 0x03]);
    }

    #[test]
    fn test_encode_empty_map() {
        assert_eq!(encode(&CborValue::Map(vec![])), vec![0xa0]);
    }

    #[test]
    fn test_encode_null() {
        assert_eq!(encode(&CborValue::Null), vec![0xf6]);
    }

    #[test]
    fn test_encode_booleans() {
        assert_eq!(encode(&CborValue::Bool(false)), vec![0xf4]);
        assert_eq!(encode(&CborValue::Bool(true)), vec![0xf5]);
    }

    #[test]
    fn test_encode_tag() {
        // Tag 1 wrapping integer 1363896240
        let value = CborValue::Tag(1, Box::new(CborValue::UnsignedInt(1363896240)));
        let encoded = encode(&value);
        assert_eq!(encoded[0], 0xc1); // Tag 1
    }

    // ============ Mixed key type tests ============

    #[test]
    fn test_mixed_int_string_keys() {
        // Integer keys should come before string keys
        let value = CborValue::Map(vec![
            (
                CborValue::TextString("key".into()),
                CborValue::UnsignedInt(1),
            ),
            (CborValue::UnsignedInt(0), CborValue::UnsignedInt(2)),
        ]);
        let encoded = encode(&value);
        // Int key (0x00) should come first, then string key
        assert_eq!(encoded[1], 0x00); // first key is integer 0
    }

    // ============ Large value tests ============

    #[test]
    fn test_encode_large_unsigned() {
        // 65535 => 0x19 0xff 0xff
        assert_eq!(
            encode(&CborValue::UnsignedInt(65535)),
            vec![0x19, 0xff, 0xff]
        );
        // 65536 => 0x1a 0x00 0x01 0x00 0x00
        assert_eq!(
            encode(&CborValue::UnsignedInt(65536)),
            vec![0x1a, 0x00, 0x01, 0x00, 0x00]
        );
    }

    #[test]
    fn test_encode_large_byte_string() {
        let bytes = vec![0xAB; 300];
        let encoded = encode(&CborValue::ByteString(Cow::Owned(bytes.clone())));
        // 300 = 0x012C => MT_BYTE_STRING | 25, then 0x01, 0x2C
        assert_eq!(encoded[0], 0x59); // 0x40 | 25
        assert_eq!(encoded[1], 0x01);
        assert_eq!(encoded[2], 0x2C);
        assert_eq!(encoded.len(), 3 + 300);
    }
}
