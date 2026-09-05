// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use cleverestricky_attestation_core::{rewrite_extension, PatchLevels, RewriteRequest};
use der::asn1::{Any, AnyRef};
use der::{Decode, Encode, Tag, TagNumber, Tagged};

const ROOT_OF_TRUST_TAG: u32 = 704;
const BOOT_KEY: [u8; 32] = [0x11; 32];
const BOOT_HASH: [u8; 32] = [0x22; 32];
const SOFTWARE: u8 = 0;
const TRUSTED_ENVIRONMENT: u8 = 1;
const STRONGBOX: u8 = 2;

#[test]
fn detector_observes_strongbox_after_rewrite() {
    let original = key_description(STRONGBOX, STRONGBOX);
    let rewritten = rewrite(&original);

    assert_eq!(security_level(&rewritten, 1), STRONGBOX);
    assert_eq!(security_level(&rewritten, 3), STRONGBOX);
}

#[test]
fn detector_does_not_promote_tee_to_strongbox() {
    let original = key_description(TRUSTED_ENVIRONMENT, TRUSTED_ENVIRONMENT);
    let rewritten = rewrite(&original);

    assert_eq!(security_level(&rewritten, 1), TRUSTED_ENVIRONMENT);
    assert_eq!(security_level(&rewritten, 3), TRUSTED_ENVIRONMENT);
}

#[test]
fn detector_preserves_software_security_level() {
    let original = key_description(SOFTWARE, SOFTWARE);
    let rewritten = rewrite(&original);

    assert_eq!(security_level(&rewritten, 1), SOFTWARE);
    assert_eq!(security_level(&rewritten, 3), SOFTWARE);
}

fn rewrite(extension: &[u8]) -> Vec<u8> {
    rewrite_extension(&RewriteRequest {
        extension_der: extension,
        patch_levels: PatchLevels::default(),
        id_overrides: &[],
        module_hash: None,
        verified_boot_key: &BOOT_KEY,
        verified_boot_hash: &BOOT_HASH,
    })
    .expect("valid attestation rewrite")
    .extension_der
}

fn key_description(attestation_level: u8, keymint_level: u8) -> Vec<u8> {
    let software = sequence([]);
    let tee = sequence([explicit_tag(
        ROOT_OF_TRUST_TAG,
        &root_of_trust([0x33; 32], [0x44; 32]),
    )]);
    sequence([
        300i32.to_der().unwrap(),
        enumerated(attestation_level),
        300i32.to_der().unwrap(),
        enumerated(keymint_level),
        octets(&[]),
        octets(&[]),
        software,
        tee,
    ])
}

fn security_level(extension: &[u8], index: usize) -> u8 {
    let outer = Any::from_der(extension).expect("KeyDescription DER");
    let fields = split(outer.value()).expect("KeyDescription fields");
    let level = Any::from_der(&fields[index]).expect("security level DER");
    assert_eq!(level.tag(), Tag::Enumerated);
    assert_eq!(level.value().len(), 1);
    level.value()[0]
}

fn enumerated(value: u8) -> Vec<u8> {
    Any::new(Tag::Enumerated, vec![value])
        .unwrap()
        .to_der()
        .unwrap()
}

fn octets(value: &[u8]) -> Vec<u8> {
    Any::new(Tag::OctetString, value.to_vec())
        .unwrap()
        .to_der()
        .unwrap()
}

fn root_of_trust(key: [u8; 32], hash: [u8; 32]) -> Vec<u8> {
    sequence([
        octets(&key),
        true.to_der().unwrap(),
        enumerated(0),
        octets(&hash),
    ])
}

fn explicit_tag(tag: u32, inner: &[u8]) -> Vec<u8> {
    Any::new(
        Tag::ContextSpecific {
            constructed: true,
            number: TagNumber(tag),
        },
        inner.to_vec(),
    )
    .unwrap()
    .to_der()
    .unwrap()
}

fn sequence<const N: usize>(fields: [Vec<u8>; N]) -> Vec<u8> {
    let mut value = Vec::new();
    for field in fields {
        value.extend_from_slice(&field);
    }
    Any::new(Tag::Sequence, value).unwrap().to_der().unwrap()
}

fn split(mut bytes: &[u8]) -> Result<Vec<Vec<u8>>, der::Error> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let (_, rest) = AnyRef::from_der_partial(bytes)?;
        let used = bytes.len() - rest.len();
        out.push(bytes[..used].to_vec());
        bytes = rest;
    }
    Ok(out)
}
