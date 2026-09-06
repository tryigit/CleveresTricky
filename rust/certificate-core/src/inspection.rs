// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use crate::{Error, ANDROID_ATTESTATION_OID, MAX_CERTIFICATE_DER_BYTES};
use attestation_der::asn1::AnyRef;
use attestation_der::{Decode as AttestationDecode, Tag, Tagged};
use cleverestricky_attestation_core::{inspect_captured_patch_levels, CapturedPatchLevels};
use x509_cert::Certificate;

const SOFTWARE_INDEX: usize = 6;
const TEE_INDEX: usize = 7;
const ROOT_OF_TRUST_TAG: u32 = 704;
const ID_TAGS: [u32; 9] = [710, 711, 712, 713, 714, 715, 716, 717, 723];
const MAX_FIELDS: usize = 16;
const MAX_TAGS: usize = 256;

/// Canonical Android KeyMint security levels carried by KeyDescription.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum SecurityLevel {
    Software = 0,
    TrustedEnvironment = 1,
    StrongBox = 2,
}

impl SecurityLevel {
    pub const fn wire_value(self) -> u8 {
        self as u8
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SecurityLevels {
    attestation: SecurityLevel,
    keymint: SecurityLevel,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CertificateInspection {
    pub captured_patch_levels: CapturedPatchLevels,
    pub present_id_mask: u16,
    pub supports_module_hash: bool,
    pub original_boot_key: Option<[u8; 32]>,
    pub original_boot_hash: Option<[u8; 32]>,
    pub attestation_security_level: SecurityLevel,
    pub keymint_security_level: SecurityLevel,
}

pub fn inspect_certificate(leaf_der: &[u8]) -> Result<CertificateInspection, Error> {
    if leaf_der.is_empty() || leaf_der.len() > MAX_CERTIFICATE_DER_BYTES {
        return Err(Error::Bounds);
    }
    let leaf = Certificate::from_der(leaf_der).map_err(|_| Error::InvalidCertificate)?;
    let extensions = leaf
        .tbs_certificate()
        .extensions()
        .ok_or(Error::MissingAttestationExtension)?;
    let mut attestation = None;
    for extension in extensions {
        if extension.extn_id == ANDROID_ATTESTATION_OID
            && attestation
                .replace(extension.extn_value.as_bytes())
                .is_some()
        {
            return Err(Error::DuplicateAttestationExtension);
        }
    }
    let extension_der = attestation.ok_or(Error::MissingAttestationExtension)?;
    let captured_patch_levels =
        inspect_captured_patch_levels(extension_der).map_err(|_| Error::AttestationRewrite)?;
    let outer = AnyRef::from_der(extension_der).map_err(|_| Error::AttestationRewrite)?;
    if outer.tag() != Tag::Sequence {
        return Err(Error::AttestationRewrite);
    }
    let fields = split(outer.value(), MAX_FIELDS)?;
    if fields.len() <= TEE_INDEX {
        return Err(Error::AttestationRewrite);
    }
    let attestation_version = <i32 as attestation_der::Decode>::from_der(&fields[0])
        .map_err(|_| Error::AttestationRewrite)?;
    let security_levels = security_levels_from_fields(&fields)?;
    let keymint_version = <i32 as attestation_der::Decode>::from_der(&fields[2])
        .map_err(|_| Error::AttestationRewrite)?;
    let list_six = tagged_fields(&fields[SOFTWARE_INDEX])?;
    let list_seven = tagged_fields(&fields[TEE_INDEX])?;
    let six_has_root = list_six.iter().any(|field| field.0 == ROOT_OF_TRUST_TAG);
    let seven_has_root = list_seven.iter().any(|field| field.0 == ROOT_OF_TRUST_TAG);
    let tee = if six_has_root && !seven_has_root {
        &list_six
    } else {
        &list_seven
    };

    let mut present_id_mask = 0u16;
    for (tag, _) in tee {
        if let Some(index) = ID_TAGS.iter().position(|candidate| candidate == tag) {
            present_id_mask |= 1u16 << index;
        }
    }
    let (original_boot_key, original_boot_hash) = tee
        .iter()
        .find(|(tag, _)| *tag == ROOT_OF_TRUST_TAG)
        .map(|(_, encoded)| parse_root_of_trust(encoded))
        .unwrap_or((None, None));

    Ok(CertificateInspection {
        captured_patch_levels,
        present_id_mask,
        supports_module_hash: attestation_version >= 400 && keymint_version >= 400,
        original_boot_key,
        original_boot_hash,
        attestation_security_level: security_levels.attestation,
        keymint_security_level: security_levels.keymint,
    })
}

fn security_levels_from_fields(fields: &[Vec<u8>]) -> Result<SecurityLevels, Error> {
    Ok(SecurityLevels {
        attestation: decode_security_level(&fields[1])?,
        keymint: decode_security_level(&fields[3])?,
    })
}

fn decode_security_level(encoded: &[u8]) -> Result<SecurityLevel, Error> {
    let level = AnyRef::from_der(encoded).map_err(|_| Error::AttestationRewrite)?;
    if level.tag() != Tag::Enumerated || level.value().len() != 1 {
        return Err(Error::AttestationRewrite);
    }
    match level.value()[0] {
        0 => Ok(SecurityLevel::Software),
        1 => Ok(SecurityLevel::TrustedEnvironment),
        2 => Ok(SecurityLevel::StrongBox),
        _ => Err(Error::AttestationRewrite),
    }
}

fn tagged_fields(encoded: &[u8]) -> Result<Vec<(u32, Vec<u8>)>, Error> {
    let sequence = AnyRef::from_der(encoded).map_err(|_| Error::AttestationRewrite)?;
    if sequence.tag() != Tag::Sequence {
        return Err(Error::AttestationRewrite);
    }
    split(sequence.value(), MAX_TAGS)?
        .into_iter()
        .map(|encoded| {
            let any = AnyRef::from_der(&encoded).map_err(|_| Error::AttestationRewrite)?;
            let tag = match any.tag() {
                Tag::ContextSpecific {
                    constructed: true,
                    number,
                } => number.value(),
                _ => return Err(Error::AttestationRewrite),
            };
            Ok((tag, encoded))
        })
        .collect()
}

fn parse_root_of_trust(encoded: &[u8]) -> (Option<[u8; 32]>, Option<[u8; 32]>) {
    let Some(outer) = AnyRef::from_der(encoded).ok() else {
        return (None, None);
    };
    let Some(sequence) = AnyRef::from_der(outer.value()).ok() else {
        return (None, None);
    };
    if sequence.tag() != Tag::Sequence {
        return (None, None);
    }
    let Ok(fields) = split(sequence.value(), 4) else {
        return (None, None);
    };
    if fields.len() != 4 {
        return (None, None);
    }
    let key = decode_digest(&fields[0]);
    let hash = decode_digest(&fields[3]);
    (key, hash)
}

fn decode_digest(encoded: &[u8]) -> Option<[u8; 32]> {
    let value = AnyRef::from_der(encoded).ok()?;
    if value.tag() != Tag::OctetString || value.value().len() != 32 {
        return None;
    }
    let digest: [u8; 32] = value.value().try_into().ok()?;
    (!digest.iter().all(|byte| *byte == 0)).then_some(digest)
}

fn split(mut encoded: &[u8], max_items: usize) -> Result<Vec<Vec<u8>>, Error> {
    let mut output = Vec::new();
    while !encoded.is_empty() {
        if output.len() >= max_items {
            return Err(Error::AttestationRewrite);
        }
        let (_, rest) = AnyRef::from_der_partial(encoded).map_err(|_| Error::AttestationRewrite)?;
        let consumed = encoded
            .len()
            .checked_sub(rest.len())
            .ok_or(Error::AttestationRewrite)?;
        if consumed == 0 {
            return Err(Error::AttestationRewrite);
        }
        output.push(encoded[..consumed].to_vec());
        encoded = rest;
    }
    Ok(output)
}
