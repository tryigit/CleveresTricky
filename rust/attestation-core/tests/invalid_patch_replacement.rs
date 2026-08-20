use cleverestricky_attestation_core::{
    rewrite_extension, Error, PatchComponent, PatchLevels, RewriteRequest,
};

const BOOT_KEY: [u8; 32] = [0x11; 32];
const BOOT_HASH: [u8; 32] = [0x22; 32];

fn request(patch_levels: PatchLevels) -> RewriteRequest<'static> {
    RewriteRequest {
        extension_der: &[0x30],
        patch_levels,
        id_overrides: &[],
        module_hash: None,
        verified_boot_key: &BOOT_KEY,
        verified_boot_hash: &BOOT_HASH,
    }
}

#[test]
fn non_positive_patch_replacements_fail_closed() {
    for patch_levels in [
        PatchLevels {
            system: PatchComponent::replace(0),
            ..PatchLevels::default()
        },
        PatchLevels {
            vendor: PatchComponent::replace(-1),
            ..PatchLevels::default()
        },
        PatchLevels {
            boot: PatchComponent::replace(0),
            ..PatchLevels::default()
        },
    ] {
        assert_eq!(
            rewrite_extension(&request(patch_levels)),
            Err(Error::InvalidOverride)
        );
    }
}
