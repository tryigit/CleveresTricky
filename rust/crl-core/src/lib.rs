// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
#![forbid(unsafe_code)]

use md5::{Digest as Md5Digest, Md5};
use num_bigint::{BigInt, Sign};
use serde::de::{self, DeserializeSeed, Deserializer, IgnoredAny, MapAccess, Visitor};
use sha1::{Digest as Sha1Digest, Sha1};
use sha2::{Digest as Sha2Digest, Sha256};
use std::collections::HashSet;
use std::fmt;

pub const MAX_CRL_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_CRL_ENTRIES: usize = 1_000_000;
pub const MAX_NORMALIZED_ENTRIES: usize = 1_000_000;
pub const MAX_CRL_KEY_UTF16_UNITS: usize = 128;
pub const MAX_NORMALIZED_ENTRY_BYTES: usize = 128;
pub const MAX_SERIAL_BYTES: usize = 256;
pub const MAX_SPKI_BYTES: usize = 64 * 1024;

const PAD_LENGTHS: [usize; 3] = [32, 40, 64];

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum CrlError {
    Size,
    Invalid,
}

impl fmt::Display for CrlError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Size => formatter.write_str("CRL exceeds configured bound"),
            Self::Invalid => formatter.write_str("CRL is invalid"),
        }
    }
}

impl std::error::Error for CrlError {}

#[derive(Debug)]
pub struct CrlIndex {
    entries: HashSet<Box<str>>,
    raw_entry_count: usize,
}

impl CrlIndex {
    pub fn parse(input: &[u8]) -> Result<Self, CrlError> {
        if input.is_empty() || input.len() > MAX_CRL_BYTES {
            return Err(CrlError::Size);
        }
        let mut deserializer = serde_json::Deserializer::from_slice(input);
        let index = CrlSeed
            .deserialize(&mut deserializer)
            .map_err(|_| CrlError::Invalid)?;
        deserializer.end().map_err(|_| CrlError::Invalid)?;
        Ok(index)
    }

    pub fn normalized_count(&self) -> usize {
        self.entries.len()
    }

    pub fn raw_entry_count(&self) -> usize {
        self.raw_entry_count
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn contains(&self, value: &str) -> bool {
        self.entries.contains(value)
    }

    pub fn is_revoked(
        &self,
        serial_twos_complement_be: &[u8],
        subject_public_key_info: &[u8],
    ) -> Result<bool, CrlError> {
        let values = fingerprints(serial_twos_complement_be, subject_public_key_info)?;
        Ok(self.contains(&values.serial_hex)
            || self.contains(&values.sha1_hex)
            || self.contains(&values.sha256_hex)
            || self.contains(&values.md5_hex))
    }
}

pub struct RevocationFingerprints {
    pub serial_hex: String,
    pub md5_hex: String,
    pub sha1_hex: String,
    pub sha256_hex: String,
}

pub fn fingerprints(
    serial_twos_complement_be: &[u8],
    subject_public_key_info: &[u8],
) -> Result<RevocationFingerprints, CrlError> {
    if serial_twos_complement_be.is_empty() || serial_twos_complement_be.len() > MAX_SERIAL_BYTES {
        return Err(CrlError::Size);
    }
    if subject_public_key_info.is_empty() || subject_public_key_info.len() > MAX_SPKI_BYTES {
        return Err(CrlError::Size);
    }
    let serial = BigInt::from_signed_bytes_be(serial_twos_complement_be).to_str_radix(16);
    if serial.len() > MAX_NORMALIZED_ENTRY_BYTES {
        return Err(CrlError::Size);
    }
    let md5 = <Md5 as Md5Digest>::digest(subject_public_key_info);
    let sha1 = <Sha1 as Sha1Digest>::digest(subject_public_key_info);
    let sha256 = <Sha256 as Sha2Digest>::digest(subject_public_key_info);
    Ok(RevocationFingerprints {
        serial_hex: serial,
        md5_hex: bytes_hex(md5.as_ref()),
        sha1_hex: bytes_hex(sha1.as_ref()),
        sha256_hex: bytes_hex(sha256.as_ref()),
    })
}

fn bytes_hex(input: &[u8]) -> String {
    let mut output = String::with_capacity(input.len() * 2);
    for byte in input {
        use std::fmt::Write as _;
        let _ = write!(output, "{byte:02x}");
    }
    output
}

struct CrlSeed;

impl<'de> DeserializeSeed<'de> for CrlSeed {
    type Value = CrlIndex;

    fn deserialize<D>(self, deserializer: D) -> Result<Self::Value, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_map(CrlVisitor)
    }
}

struct CrlVisitor;

impl<'de> Visitor<'de> for CrlVisitor {
    type Value = CrlIndex;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("an Android attestation CRL object")
    }

    fn visit_map<M>(self, mut map: M) -> Result<Self::Value, M::Error>
    where
        M: MapAccess<'de>,
    {
        let mut normalized = HashSet::new();
        let mut entries_found = false;
        let mut processed = 0usize;
        while let Some(name) = map.next_key::<String>()? {
            if name == "entries" {
                entries_found = true;
                map.next_value_seed(EntriesSeed {
                    normalized: &mut normalized,
                    processed: &mut processed,
                })?;
            } else {
                map.next_value::<IgnoredAny>()?;
            }
        }
        if !entries_found {
            return Err(de::Error::custom("CRL entries object is missing"));
        }
        Ok(CrlIndex {
            entries: normalized,
            raw_entry_count: processed,
        })
    }
}

struct EntriesSeed<'a> {
    normalized: &'a mut HashSet<Box<str>>,
    processed: &'a mut usize,
}

impl<'de> DeserializeSeed<'de> for EntriesSeed<'_> {
    type Value = ();

    fn deserialize<D>(self, deserializer: D) -> Result<Self::Value, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_map(EntriesVisitor {
            normalized: self.normalized,
            processed: self.processed,
        })
    }
}

struct EntriesVisitor<'a> {
    normalized: &'a mut HashSet<Box<str>>,
    processed: &'a mut usize,
}

impl<'de> Visitor<'de> for EntriesVisitor<'_> {
    type Value = ();

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("a CRL entries object")
    }

    fn visit_map<M>(self, mut map: M) -> Result<Self::Value, M::Error>
    where
        M: MapAccess<'de>,
    {
        while let Some(key) = map.next_key::<String>()? {
            *self.processed = self
                .processed
                .checked_add(1)
                .ok_or_else(|| de::Error::custom("CRL entry count overflow"))?;
            if *self.processed > MAX_CRL_ENTRIES {
                return Err(de::Error::custom("CRL has too many entries"));
            }
            if key.encode_utf16().count() > MAX_CRL_KEY_UTF16_UNITS {
                return Err(de::Error::custom("CRL entry key is too long"));
            }
            map.next_value::<IgnoredAny>()?;
            normalize_entry(&key, self.normalized)
                .map_err(|_| de::Error::custom("CRL normalization exceeds configured bound"))?;
        }
        Ok(())
    }
}

fn normalize_entry(value: &str, output: &mut HashSet<Box<str>>) -> Result<(), CrlError> {
    if value.is_empty() || value.len() > MAX_NORMALIZED_ENTRY_BYTES {
        return Ok(());
    }

    let bytes = value.as_bytes();
    let digit_start = usize::from(bytes.first() == Some(&b'-'));
    let mut is_decimal = digit_start < bytes.len();
    if is_decimal && bytes.len() - digit_start > 1 && bytes[digit_start] == b'0' {
        is_decimal = false;
    } else if is_decimal {
        is_decimal = bytes[digit_start..].iter().all(u8::is_ascii_digit);
    }

    let mut added = false;
    if is_decimal {
        if let Ok(number) = value.parse::<BigInt>() {
            let hex = number.to_str_radix(16);
            insert_bounded(output, hex.clone())?;
            if number.sign() != Sign::Minus {
                for target in PAD_LENGTHS {
                    if hex.len() < target {
                        let mut padded = String::with_capacity(target);
                        padded.extend(std::iter::repeat_n('0', target - hex.len()));
                        padded.push_str(&hex);
                        insert_bounded(output, padded)?;
                    }
                }
            }
            added = true;
        }
    }

    let is_hex = bytes.iter().all(u8::is_ascii_hexdigit);
    if PAD_LENGTHS.contains(&bytes.len()) && is_hex {
        insert_bounded(output, value.to_ascii_lowercase())?;
    }
    if !is_decimal && !added && is_hex {
        if let Some(number) = BigInt::parse_bytes(bytes, 16) {
            insert_bounded(output, number.to_str_radix(16))?;
        }
    }
    Ok(())
}

fn insert_bounded(output: &mut HashSet<Box<str>>, value: String) -> Result<(), CrlError> {
    if value.is_empty() || value.len() > MAX_NORMALIZED_ENTRY_BYTES {
        return Err(CrlError::Size);
    }
    if output.contains(value.as_str()) {
        return Ok(());
    }
    if output.len() >= MAX_NORMALIZED_ENTRIES {
        return Err(CrlError::Size);
    }
    output.insert(value.into_boxed_str());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalization_matches_managed_decimal_and_hex_rules() {
        let input = br#"{"ignored":1,"entries":{"255":0,"0001":0,"-2":0,"ABCDEF":0,"0000000000000000000000000000000A":0}}"#;
        let index = CrlIndex::parse(input).unwrap();
        assert_eq!(index.raw_entry_count(), 5);
        assert!(index.contains("ff"));
        assert!(index.contains("000000000000000000000000000000ff"));
        assert!(index.contains("00000000000000000000000000000000000000ff"));
        assert!(index.contains("00000000000000000000000000000000000000000000000000000000000000ff"));
        assert!(index.contains("1"));
        assert!(index.contains("-2"));
        assert!(index.contains("abcdef"));
        assert!(index.contains("a"));
    }

    #[test]
    fn multiple_entries_objects_are_merged() {
        let index = CrlIndex::parse(br#"{"entries":{"1":0},"entries":{"2":0}}"#).unwrap();
        assert_eq!(index.raw_entry_count(), 2);
        assert!(index.contains("1"));
        assert!(index.contains("2"));
    }

    #[test]
    fn malformed_shapes_and_oversized_keys_fail_closed() {
        assert!(matches!(CrlIndex::parse(b"{}"), Err(CrlError::Invalid)));
        assert!(matches!(CrlIndex::parse(b"[]"), Err(CrlError::Invalid)));
        assert!(matches!(
            CrlIndex::parse(br#"{"entries":[]}"#),
            Err(CrlError::Invalid)
        ));
        let key = "1".repeat(MAX_CRL_KEY_UTF16_UNITS + 1);
        let input = format!("{{\"entries\":{{\"{key}\":0}}}}");
        assert!(matches!(
            CrlIndex::parse(input.as_bytes()),
            Err(CrlError::Invalid)
        ));
    }

    #[test]
    fn non_ascii_numeric_keys_are_not_treated_as_valid_decimal_serials() {
        let index = CrlIndex::parse("{\"entries\":{\"١٢٣\":0}}".as_bytes()).unwrap();
        assert!(index.is_empty());
    }

    #[test]
    fn fingerprints_match_standard_digest_vectors() {
        let values = fingerprints(&[0x01], b"abc").unwrap();
        assert_eq!(values.serial_hex, "1");
        assert_eq!(values.md5_hex, "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(values.sha1_hex, "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert_eq!(
            values.sha256_hex,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn negative_twos_complement_serial_matches_java_big_integer_hex_shape() {
        let values = fingerprints(&[0xfe], b"x").unwrap();
        assert_eq!(values.serial_hex, "-2");
    }

    #[test]
    fn snapshot_matches_serial_and_digest_aliases() {
        let index = CrlIndex::parse(br#"{"entries":{"1":0,"900150983cd24fb0d6963f7d28e17f72":0}}"#)
            .unwrap();
        assert!(index.is_revoked(&[0x01], b"unrelated").unwrap());
        assert!(index.is_revoked(&[0x02], b"abc").unwrap());
        assert!(!index.is_revoked(&[0x02], b"def").unwrap());
    }
}
