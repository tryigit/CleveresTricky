// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use cleverestricky_attestation_core::CapturedPatchLevels;
use cleverestricky_certificate_core::{inspect_certificate, Error, SecurityLevel};

mod fixture {
    include!("rewrite.rs");

    pub(super) fn genuine_leaf_der() -> Vec<u8> {
        let document = parse_keybox_xml_bytes(VALID_EC).expect("fixture XML");
        let key = document.keys.first().expect("fixture key");
        let issuer_pem = key
            .certificates_pem
            .first()
            .expect("fixture issuer certificate");
        let normalized_issuer = normalized_pem(issuer_pem);
        let issuer =
            Certificate::from_pem(normalized_issuer.as_bytes()).expect("fixture issuer DER");
        synthetic_genuine_leaf(&issuer)
            .to_der()
            .expect("genuine DER")
    }

    pub(super) fn ordinary_certificate_der() -> Vec<u8> {
        let document = parse_keybox_xml_bytes(VALID_EC).expect("fixture XML");
        let key = document.keys.first().expect("fixture key");
        let issuer_pem = key
            .certificates_pem
            .first()
            .expect("fixture issuer certificate");
        let normalized_issuer = normalized_pem(issuer_pem);
        Certificate::from_pem(normalized_issuer.as_bytes())
            .expect("fixture certificate")
            .to_der()
            .expect("fixture DER")
    }

    pub(super) fn zero_boot_key_leaf_der() -> Vec<u8> {
        let document = parse_keybox_xml_bytes(VALID_EC).expect("fixture XML");
        let key = document.keys.first().expect("fixture key");
        let issuer_pem = key
            .certificates_pem
            .first()
            .expect("fixture issuer certificate");
        let normalized_issuer = normalized_pem(issuer_pem);
        let issuer =
            Certificate::from_pem(normalized_issuer.as_bytes()).expect("fixture issuer DER");
        let tee = auth_list([
            explicit_tag_raw(704, &root_of_trust([0u8; 32], [0x31; 32])),
            explicit_integer_raw(706, 202401),
            explicit_octet_raw(714, b"old-imei"),
        ]);
        let software = auth_list([]);
        let ext = encode_sequence([
            attestation_i32(400).as_slice(),
            any_enumerated(1).as_slice(),
            attestation_i32(400).as_slice(),
            any_enumerated(1).as_slice(),
            any_octets(&[]).as_slice(),
            any_octets(&[]).as_slice(),
            software.as_slice(),
            tee.as_slice(),
        ]);
        synthetic_leaf_with_ext(&issuer, ext)
            .to_der()
            .expect("genuine DER")
    }

    fn synthetic_leaf_with_ext(issuer: &Certificate, extension_der: Vec<u8>) -> Certificate {
        let issuer_tbs = issuer.tbs_certificate();
        let version = explicit_x509_tag(0, &2i32.to_der().expect("v3 DER"));
        let serial = 0x80i32.to_der().expect("serial DER");
        let signature = issuer_tbs
            .signature()
            .to_der()
            .expect("signature algorithm DER");
        let issuer_name = issuer_tbs.subject().to_der().expect("issuer name DER");
        let validity = issuer_tbs.validity().to_der().expect("validity DER");
        let subject = issuer_tbs.subject().to_der().expect("subject DER");
        let spki = issuer_tbs
            .subject_public_key_info()
            .to_der()
            .expect("SPKI DER");
        let issuer_unique_id = implicit_unique_id(1, 0xa0);
        let subject_unique_id = implicit_unique_id(2, 0xb0);

        let mut extensions = issuer_tbs.extensions().cloned().unwrap_or_default();
        extensions.retain(|extension| extension.extn_id != ANDROID_ATTESTATION_OID);
        extensions.push(x509_cert::ext::Extension {
            extn_id: ANDROID_ATTESTATION_OID,
            critical: false,
            extn_value: attestation_der::asn1::OctetString::new(extension_der)
                .expect("attestation octets"),
        });
        let extensions = extensions.to_der().expect("extensions DER");
        let extensions = explicit_x509_tag(3, &extensions);

        let tbs = x509_sequence([
            version.as_slice(),
            serial.as_slice(),
            signature.as_slice(),
            issuer_name.as_slice(),
            validity.as_slice(),
            subject.as_slice(),
            spki.as_slice(),
            issuer_unique_id.as_slice(),
            subject_unique_id.as_slice(),
            extensions.as_slice(),
        ]);
        let outer_algorithm = issuer
            .signature_algorithm()
            .to_der()
            .expect("outer signature algorithm DER");
        let outer_signature = issuer.signature().to_der().expect("outer signature DER");
        let certificate = x509_sequence([
            tbs.as_slice(),
            outer_algorithm.as_slice(),
            outer_signature.as_slice(),
        ]);
        Certificate::from_der(&certificate).expect("synthetic genuine certificate")
    }
}

#[test]
fn inspection_returns_only_policy_inputs_needed_by_android_adapter() {
    let inspected = inspect_certificate(&fixture::genuine_leaf_der()).expect("inspection");
    assert_eq!(
        inspected.captured_patch_levels,
        CapturedPatchLevels {
            system: Some(202401),
            vendor: None,
            boot: None,
        }
    );
    assert_eq!(inspected.present_id_mask, 1 << 4);
    assert!(inspected.supports_module_hash);
    assert_eq!(inspected.original_boot_key, Some([0x21; 32]));
    assert_eq!(inspected.original_boot_hash, Some([0x31; 32]));
    assert_eq!(
        inspected.attestation_security_level,
        SecurityLevel::TrustedEnvironment
    );
    assert_eq!(
        inspected.keymint_security_level,
        SecurityLevel::TrustedEnvironment
    );
}

#[test]
fn inspection_preserves_boot_hash_when_boot_key_is_all_zeros() {
    let inspected = inspect_certificate(&fixture::zero_boot_key_leaf_der()).expect("inspection");
    assert_eq!(inspected.original_boot_key, None);
    assert_eq!(inspected.original_boot_hash, Some([0x31; 32]));
}

#[test]
fn certificate_without_attestation_extension_fails_closed() {
    assert_eq!(
        inspect_certificate(&fixture::ordinary_certificate_der()).unwrap_err(),
        Error::MissingAttestationExtension
    );
}
