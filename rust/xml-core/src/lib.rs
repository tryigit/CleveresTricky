// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
#![forbid(unsafe_code)]

use quick_xml::events::{BytesStart, Event};
use quick_xml::{Reader, XmlVersion};
use std::fmt;
use zeroize::Zeroize;

pub const MAX_DEPTH: usize = 32;
pub const MAX_ELEMENTS: usize = 4096;
pub const MAX_ATTRIBUTES_PER_ELEMENT: usize = 32;
pub const MAX_NAME_UTF16_UNITS: usize = 128;
pub const MAX_ATTRIBUTE_VALUE_UTF16_UNITS: usize = 4096;
pub const MAX_TEXT_UTF16_UNITS: usize = 12 * 1024 * 1024;
pub const MAX_DOCUMENT_UTF16_UNITS: usize = 16 * 1024 * 1024;
pub const MAX_DOCUMENT_UTF8_BYTES: usize = 3 * MAX_DOCUMENT_UTF16_UNITS;
pub const MAX_KEYBOXES_PER_FILE: usize = 64;
pub const MAX_KEYS_PER_KEYBOX: usize = 4;
pub const MAX_CERTIFICATES_PER_CHAIN: usize = 16;
pub const MAX_PEM_UTF16_UNITS: usize = 256 * 1024;
const MAX_COUNT_UTF16_UNITS: usize = 16;
const MAX_ALGORITHM_UTF16_UNITS: usize = 32;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum XmlError {
    InvalidUtf8,
    DocumentTooLarge,
    DtdRejected,
    EntityRejected,
    Malformed,
    MultipleRoots,
    TextOutsideRoot,
    DepthLimit,
    ElementLimit,
    AttributeLimit,
    NameLimit,
    AttributeValueLimit,
    TextLimit,
    InvalidRoot,
    InvalidKeyboxCount,
    InvalidKeyCount,
    InvalidCertificateCount,
    MissingField,
    PemLimit,
    AlgorithmLimit,
}

impl fmt::Display for XmlError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InvalidUtf8 => "XML is not valid UTF-8",
            Self::DocumentTooLarge => "XML document exceeds size limit",
            Self::DtdRejected => "DTD is not allowed in this parser to prevent XXE attacks",
            Self::EntityRejected => "custom XML entities are not allowed",
            Self::Malformed => "XML document is malformed",
            Self::MultipleRoots => "XML document contains multiple root elements",
            Self::TextOutsideRoot => "XML document contains text outside the root element",
            Self::DepthLimit => "XML nesting exceeds depth limit",
            Self::ElementLimit => "XML document exceeds element limit",
            Self::AttributeLimit => "XML element exceeds attribute limit",
            Self::NameLimit => "XML name exceeds length limit",
            Self::AttributeValueLimit => "XML attribute value exceeds length limit",
            Self::TextLimit => "XML text exceeds length limit",
            Self::InvalidRoot => "keybox XML root is invalid",
            Self::InvalidKeyboxCount => "keybox count is invalid",
            Self::InvalidKeyCount => "key count is invalid",
            Self::InvalidCertificateCount => "certificate count is invalid",
            Self::MissingField => "keybox XML is missing a required field",
            Self::PemLimit => "PEM field exceeds size limit",
            Self::AlgorithmLimit => "key algorithm exceeds size limit",
        })
    }
}

impl std::error::Error for XmlError {}

#[derive(Eq, PartialEq)]
pub struct RawKey {
    pub algorithm: String,
    pub private_key_pem: String,
    pub certificates_pem: Vec<String>,
}

impl fmt::Debug for RawKey {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RawKey")
            .field("algorithm", &self.algorithm)
            .field("private_key_pem", &"<redacted>")
            .field("certificate_count", &self.certificates_pem.len())
            .finish()
    }
}

impl Drop for RawKey {
    fn drop(&mut self) {
        self.private_key_pem.zeroize();
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct KeyboxDocument {
    pub declared_keyboxes: usize,
    pub keybox_count: usize,
    pub keys: Vec<RawKey>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Role {
    Root,
    Keybox,
    Key,
    NumberOfKeyboxes,
    PrivateKey,
    CertificateChain,
    NumberOfCertificates,
    Certificate,
    Other,
}

struct Frame {
    role: Role,
    text: Option<String>,
    text_units: usize,
}

impl Frame {
    fn plain(role: Role) -> Self {
        Self {
            role,
            text: None,
            text_units: 0,
        }
    }

    fn capture(role: Role) -> Self {
        Self {
            role,
            text: Some(String::new()),
            text_units: 0,
        }
    }

    fn capture_limit(&self) -> Option<usize> {
        match self.role {
            Role::NumberOfKeyboxes | Role::NumberOfCertificates => Some(MAX_COUNT_UTF16_UNITS),
            Role::PrivateKey | Role::Certificate => Some(MAX_PEM_UTF16_UNITS),
            _ => None,
        }
    }

    fn take_nonempty_text(&mut self) -> Option<String> {
        self.text.take().filter(|value| !value.is_empty())
    }
}

impl Drop for Frame {
    fn drop(&mut self) {
        if self.role == Role::PrivateKey {
            if let Some(text) = self.text.as_mut() {
                text.zeroize();
            }
        }
    }
}

#[derive(Default)]
struct KeyboxBuilder {
    keys: Vec<RawKey>,
}

#[derive(Default)]
struct ChainBuilder {
    declared_count: Option<String>,
    certificates: Vec<String>,
}

#[derive(Default)]
struct KeyBuilder {
    algorithm: Option<String>,
    private_key: Option<String>,
    chain: Option<ChainBuilder>,
}

impl Drop for KeyBuilder {
    fn drop(&mut self) {
        if let Some(private_key) = self.private_key.as_mut() {
            private_key.zeroize();
        }
    }
}

struct ParseState {
    root_seen: bool,
    element_count: usize,
    total_text_units: usize,
    declared_keyboxes: Option<String>,
    keybox_count: usize,
    keys: Vec<RawKey>,
    current_keybox: Option<KeyboxBuilder>,
    current_key: Option<KeyBuilder>,
}

impl ParseState {
    fn new() -> Self {
        Self {
            root_seen: false,
            element_count: 0,
            total_text_units: 0,
            declared_keyboxes: None,
            keybox_count: 0,
            keys: Vec::new(),
            current_keybox: None,
            current_key: None,
        }
    }
}

pub fn parse_keybox_xml_bytes(input: &[u8]) -> Result<KeyboxDocument, XmlError> {
    if input.len() > MAX_DOCUMENT_UTF8_BYTES {
        return Err(XmlError::DocumentTooLarge);
    }
    let input = std::str::from_utf8(input).map_err(|_| XmlError::InvalidUtf8)?;
    parse_keybox_xml(input)
}

pub fn parse_keybox_xml(input: &str) -> Result<KeyboxDocument, XmlError> {
    if input.len() > MAX_DOCUMENT_UTF8_BYTES
        || utf16_units_bounded(input, MAX_DOCUMENT_UTF16_UNITS).is_none()
    {
        return Err(XmlError::DocumentTooLarge);
    }
    if input.contains("<!DOCTYPE") || input.contains("<!ENTITY") {
        return Err(XmlError::DtdRejected);
    }

    let mut reader = Reader::from_str(input);
    reader.config_mut().check_end_names = true;
    reader.config_mut().allow_unmatched_ends = false;
    reader.config_mut().allow_dangling_amp = false;

    let mut state = ParseState::new();
    let mut stack: Vec<Frame> = Vec::new();

    loop {
        let event = reader.read_event().map_err(|_| XmlError::Malformed)?;
        match event {
            Event::Start(start) => {
                let frame = begin_element(&start, &stack, &mut state)?;
                stack.push(frame);
            }
            Event::Empty(start) => {
                let mut frame = begin_element(&start, &stack, &mut state)?;
                finish_element(&mut frame, &mut state)?;
            }
            Event::End(_) => {
                let mut frame = stack.pop().ok_or(XmlError::Malformed)?;
                finish_element(&mut frame, &mut state)?;
            }
            Event::Text(text) => {
                let decoded = text.xml10_content();
                append_text(decoded.as_ref(), &mut stack, &mut state)?;
            }
            Event::GeneralRef(reference) => {
                let reference = reference.xml10_content();
                let resolved =
                    resolve_reference(reference.as_ref()).ok_or(XmlError::EntityRejected)?;
                append_text(&resolved, &mut stack, &mut state)?;
            }
            Event::CData(_) => {
                // The managed XmlPullParser oracle exposes CDATA as a separate event and the
                // legacy parser intentionally ignored that event. Keep the same semantics during
                // migration so differential tests do not silently broaden accepted keybox input.
            }
            Event::DocType(_) => return Err(XmlError::DtdRejected),
            Event::Decl(_) | Event::Comment(_) | Event::PI(_) => {}
            Event::Eof => break,
        }
    }

    if !stack.is_empty() || state.current_key.is_some() || state.current_keybox.is_some() {
        return Err(XmlError::Malformed);
    }
    if !state.root_seen {
        return Err(XmlError::InvalidRoot);
    }
    let declared_keyboxes = parse_bounded_count(
        state.declared_keyboxes.as_deref(),
        MAX_KEYBOXES_PER_FILE,
        XmlError::InvalidKeyboxCount,
    )?;
    if declared_keyboxes != state.keybox_count {
        return Err(XmlError::InvalidKeyboxCount);
    }

    Ok(KeyboxDocument {
        declared_keyboxes,
        keybox_count: state.keybox_count,
        keys: state.keys,
    })
}

fn begin_element(
    start: &BytesStart<'_>,
    stack: &[Frame],
    state: &mut ParseState,
) -> Result<Frame, XmlError> {
    if stack.len() >= MAX_DEPTH {
        return Err(XmlError::DepthLimit);
    }
    state.element_count = state
        .element_count
        .checked_add(1)
        .filter(|value| *value <= MAX_ELEMENTS)
        .ok_or(XmlError::ElementLimit)?;

    let raw_name = start.name();
    let name = raw_name.as_ref();
    if name.is_empty() || utf16_units_bounded(name, MAX_NAME_UTF16_UNITS).is_none() {
        return Err(XmlError::NameLimit);
    }

    let parent = stack.last().map(|frame| frame.role);
    if parent.is_none() {
        if state.root_seen {
            return Err(XmlError::MultipleRoots);
        }
        state.root_seen = true;
        validate_attributes(start, None)?;
        if name != "AndroidAttestation" {
            return Err(XmlError::InvalidRoot);
        }
        return Ok(Frame::plain(Role::Root));
    }

    let mut algorithm = None;
    let is_direct_key = parent == Some(Role::Keybox) && name == "Key";
    validate_attributes(
        start,
        if is_direct_key {
            Some(&mut algorithm)
        } else {
            None
        },
    )?;

    match (parent, name) {
        (Some(Role::Root), "NumberOfKeyboxes") if state.declared_keyboxes.is_none() => {
            Ok(Frame::capture(Role::NumberOfKeyboxes))
        }
        (Some(Role::Root), "Keybox") => {
            if state.current_keybox.is_some() || state.keybox_count >= MAX_KEYBOXES_PER_FILE {
                return Err(XmlError::InvalidKeyboxCount);
            }
            state.current_keybox = Some(KeyboxBuilder::default());
            Ok(Frame::plain(Role::Keybox))
        }
        (Some(Role::Keybox), "Key") => {
            let keybox = state.current_keybox.as_ref().ok_or(XmlError::Malformed)?;
            if state.current_key.is_some() || keybox.keys.len() >= MAX_KEYS_PER_KEYBOX {
                return Err(XmlError::InvalidKeyCount);
            }
            if algorithm.as_deref().is_some_and(|value| {
                utf16_units_bounded(value, MAX_ALGORITHM_UTF16_UNITS).is_none()
            }) {
                return Err(XmlError::AlgorithmLimit);
            }
            state.current_key = Some(KeyBuilder {
                algorithm,
                private_key: None,
                chain: None,
            });
            Ok(Frame::plain(Role::Key))
        }
        (Some(Role::Key), "PrivateKey")
            if state
                .current_key
                .as_ref()
                .is_some_and(|key| key.private_key.is_none()) =>
        {
            Ok(Frame::capture(Role::PrivateKey))
        }
        (Some(Role::Key), "CertificateChain")
            if state
                .current_key
                .as_ref()
                .is_some_and(|key| key.chain.is_none()) =>
        {
            state.current_key.as_mut().ok_or(XmlError::Malformed)?.chain =
                Some(ChainBuilder::default());
            Ok(Frame::plain(Role::CertificateChain))
        }
        (Some(Role::CertificateChain), "NumberOfCertificates")
            if state
                .current_key
                .as_ref()
                .and_then(|key| key.chain.as_ref())
                .is_some_and(|chain| chain.declared_count.is_none()) =>
        {
            Ok(Frame::capture(Role::NumberOfCertificates))
        }
        (Some(Role::CertificateChain), "Certificate") => {
            let chain = state
                .current_key
                .as_ref()
                .and_then(|key| key.chain.as_ref())
                .ok_or(XmlError::Malformed)?;
            if chain.certificates.len() >= MAX_CERTIFICATES_PER_CHAIN {
                return Err(XmlError::InvalidCertificateCount);
            }
            Ok(Frame::capture(Role::Certificate))
        }
        _ => Ok(Frame::plain(Role::Other)),
    }
}

fn validate_attributes(
    start: &BytesStart<'_>,
    mut algorithm: Option<&mut Option<String>>,
) -> Result<(), XmlError> {
    let mut count = 0usize;
    for attribute in start.attributes() {
        count = count.checked_add(1).ok_or(XmlError::AttributeLimit)?;
        if count > MAX_ATTRIBUTES_PER_ELEMENT {
            return Err(XmlError::AttributeLimit);
        }
        let attribute = attribute.map_err(|_| XmlError::Malformed)?;
        let name = attribute.key.as_ref();
        if name.is_empty() || utf16_units_bounded(name, MAX_NAME_UTF16_UNITS).is_none() {
            return Err(XmlError::NameLimit);
        }
        let value = attribute
            .normalized_value(XmlVersion::Implicit1_0)
            .map_err(|_| XmlError::Malformed)?;
        if utf16_units_bounded(value.as_ref(), MAX_ATTRIBUTE_VALUE_UTF16_UNITS).is_none() {
            return Err(XmlError::AttributeValueLimit);
        }
        if name == "algorithm" {
            if let Some(destination) = algorithm.as_deref_mut() {
                *destination = Some(value.into_owned());
            }
        }
    }
    Ok(())
}

fn append_text(text: &str, stack: &mut [Frame], state: &mut ParseState) -> Result<(), XmlError> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(());
    }
    let frame = stack.last_mut().ok_or(XmlError::TextOutsideRoot)?;
    let units = utf16_units_bounded(text, MAX_TEXT_UTF16_UNITS).ok_or(XmlError::TextLimit)?;
    state.total_text_units = state
        .total_text_units
        .checked_add(units)
        .filter(|value| *value <= MAX_TEXT_UTF16_UNITS)
        .ok_or(XmlError::TextLimit)?;

    let capture_limit = frame.capture_limit();
    let capture_role = frame.role;
    if let Some(buffer) = frame.text.as_mut() {
        let limit = capture_limit.ok_or(XmlError::Malformed)?;
        let limit_error = if matches!(capture_role, Role::PrivateKey | Role::Certificate) {
            XmlError::PemLimit
        } else {
            XmlError::MissingField
        };
        frame.text_units = frame
            .text_units
            .checked_add(units)
            .filter(|value| *value <= limit)
            .ok_or(limit_error)?;
        buffer
            .try_reserve(text.len())
            .map_err(|_| XmlError::DocumentTooLarge)?;
        buffer.push_str(text);
    }
    Ok(())
}

fn finish_element(frame: &mut Frame, state: &mut ParseState) -> Result<(), XmlError> {
    match frame.role {
        Role::NumberOfKeyboxes => {
            state.declared_keyboxes = frame.take_nonempty_text();
        }
        Role::PrivateKey => {
            state
                .current_key
                .as_mut()
                .ok_or(XmlError::Malformed)?
                .private_key = frame.take_nonempty_text();
        }
        Role::NumberOfCertificates => {
            state
                .current_key
                .as_mut()
                .and_then(|key| key.chain.as_mut())
                .ok_or(XmlError::Malformed)?
                .declared_count = frame.take_nonempty_text();
        }
        Role::Certificate => {
            let certificate = frame.take_nonempty_text().ok_or(XmlError::MissingField)?;
            state
                .current_key
                .as_mut()
                .and_then(|key| key.chain.as_mut())
                .ok_or(XmlError::Malformed)?
                .certificates
                .push(certificate);
        }
        Role::Key => finish_key(state)?,
        Role::Keybox => finish_keybox(state)?,
        Role::Root | Role::CertificateChain | Role::Other => {}
    }
    Ok(())
}

fn finish_key(state: &mut ParseState) -> Result<(), XmlError> {
    let mut key = state.current_key.take().ok_or(XmlError::Malformed)?;
    let algorithm = key.algorithm.take().ok_or(XmlError::MissingField)?;
    if algorithm.is_empty() {
        return Err(XmlError::MissingField);
    }
    let chain = key.chain.take().ok_or(XmlError::MissingField)?;
    let declared_count = parse_bounded_count(
        chain.declared_count.as_deref(),
        MAX_CERTIFICATES_PER_CHAIN,
        XmlError::InvalidCertificateCount,
    )?;
    if declared_count != chain.certificates.len() {
        return Err(XmlError::InvalidCertificateCount);
    }
    let destination = state.current_keybox.as_mut().ok_or(XmlError::Malformed)?;
    let private_key_pem = key.private_key.take().ok_or(XmlError::MissingField)?;
    destination.keys.push(RawKey {
        algorithm,
        private_key_pem,
        certificates_pem: chain.certificates,
    });
    Ok(())
}

fn finish_keybox(state: &mut ParseState) -> Result<(), XmlError> {
    let mut keybox = state.current_keybox.take().ok_or(XmlError::Malformed)?;
    if keybox.keys.is_empty() || keybox.keys.len() > MAX_KEYS_PER_KEYBOX {
        return Err(XmlError::InvalidKeyCount);
    }
    state.keybox_count = state
        .keybox_count
        .checked_add(1)
        .filter(|value| *value <= MAX_KEYBOXES_PER_FILE)
        .ok_or(XmlError::InvalidKeyboxCount)?;
    state
        .keys
        .try_reserve(keybox.keys.len())
        .map_err(|_| XmlError::DocumentTooLarge)?;
    state.keys.append(&mut keybox.keys);
    Ok(())
}

fn parse_bounded_count(
    value: Option<&str>,
    maximum: usize,
    error: XmlError,
) -> Result<usize, XmlError> {
    let parsed = value
        .ok_or(XmlError::MissingField)?
        .parse::<i32>()
        .map_err(|_| error)?;
    let parsed = usize::try_from(parsed).map_err(|_| error)?;
    if parsed == 0 || parsed > maximum {
        Err(error)
    } else {
        Ok(parsed)
    }
}

fn resolve_reference(reference: &str) -> Option<String> {
    let character = match reference {
        "lt" => return Some("<".to_owned()),
        "gt" => return Some(">".to_owned()),
        "amp" => return Some("&".to_owned()),
        "apos" => return Some("'".to_owned()),
        "quot" => return Some("\"".to_owned()),
        value if value.starts_with("#x") => {
            char::from_u32(u32::from_str_radix(&value[2..], 16).ok()?)?
        }
        value if value.starts_with('#') => char::from_u32(value[1..].parse::<u32>().ok()?)?,
        _ => return None,
    };
    if is_forbidden_xml_char(character) {
        None
    } else {
        Some(character.to_string())
    }
}

fn is_forbidden_xml_char(value: char) -> bool {
    !matches!(value as u32, 0x9 | 0xA | 0xD | 0x20..=0xD7FF | 0xE000..=0xFFFD | 0x10000..=0x10FFFF)
}

fn utf16_units_bounded(value: &str, limit: usize) -> Option<usize> {
    let mut units = 0usize;
    for character in value.chars() {
        units = units.checked_add(character.len_utf16())?;
        if units > limit {
            return None;
        }
    }
    Some(units)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn one_key_xml(private_key: &str, certificates: &[&str]) -> String {
        let certificates_xml = certificates
            .iter()
            .map(|certificate| format!("<Certificate>{certificate}</Certificate>"))
            .collect::<String>();
        format!(
            "<AndroidAttestation><NumberOfKeyboxes>1</NumberOfKeyboxes><Keybox>\
             <Key algorithm=\"ecdsa\"><PrivateKey>{private_key}</PrivateKey><CertificateChain>\
             <NumberOfCertificates>{}</NumberOfCertificates>{certificates_xml}\
             </CertificateChain></Key></Keybox></AndroidAttestation>",
            certificates.len()
        )
    }

    #[test]
    fn extracts_only_keybox_payload_fields() {
        let xml = one_key_xml("PRIVATE", &["LEAF", "ROOT"]);
        let parsed = parse_keybox_xml(&xml).unwrap();
        assert_eq!(parsed.declared_keyboxes, 1);
        assert_eq!(parsed.keybox_count, 1);
        assert_eq!(parsed.keys.len(), 1);
        assert_eq!(parsed.keys[0].algorithm, "ecdsa");
        assert_eq!(parsed.keys[0].private_key_pem, "PRIVATE");
        assert_eq!(parsed.keys[0].certificates_pem, ["LEAF", "ROOT"]);
    }

    #[test]
    fn predefined_attribute_entities_are_decoded() {
        let xml = one_key_xml("PRIVATE", &["CERT"])
            .replace(" algorithm=\"ecdsa\"", " algorithm=\"ecdsa&amp;v1\"");
        let parsed = parse_keybox_xml(&xml).unwrap();
        assert_eq!(parsed.keys[0].algorithm, "ecdsa&v1");
    }

    #[test]
    fn debug_output_redacts_private_key_material() {
        let parsed = parse_keybox_xml(&one_key_xml("PRIVATE-SECRET", &["CERT"])).unwrap();
        let debug = format!("{parsed:?}");
        assert!(debug.contains("<redacted>"));
        assert!(!debug.contains("PRIVATE-SECRET"));
    }

    #[test]
    fn mixed_content_matches_managed_text_behavior() {
        let xml = one_key_xml("Part1<Inner>ignored</Inner>Part2", &["CERT"]);
        let parsed = parse_keybox_xml(&xml).unwrap();
        assert_eq!(parsed.keys[0].private_key_pem, "Part1Part2");
    }

    #[test]
    fn cdata_is_ignored_like_managed_xmlpull_oracle() {
        let xml = one_key_xml("Part1<![CDATA[ignored]]>Part2", &["CERT"]);
        let parsed = parse_keybox_xml(&xml).unwrap();
        assert_eq!(parsed.keys[0].private_key_pem, "Part1Part2");
    }

    #[test]
    fn dtd_and_custom_entities_fail_closed() {
        assert_eq!(
            parse_keybox_xml(
                "<!DOCTYPE foo [<!ENTITY x \"Hello\">]><AndroidAttestation>&x;</AndroidAttestation>"
            )
            .unwrap_err(),
            XmlError::DtdRejected
        );
        let xml = one_key_xml("A&custom;B", &["CERT"]);
        assert_eq!(
            parse_keybox_xml(&xml).unwrap_err(),
            XmlError::EntityRejected
        );
    }

    #[test]
    fn multiple_roots_and_text_outside_root_fail_closed() {
        assert_eq!(
            parse_keybox_xml("<AndroidAttestation/><Second/>").unwrap_err(),
            XmlError::MultipleRoots
        );
        assert_eq!(
            parse_keybox_xml("unexpected<AndroidAttestation/>").unwrap_err(),
            XmlError::TextOutsideRoot
        );
    }

    #[test]
    fn declaration_counts_are_exact_and_bounded() {
        let mismatch = one_key_xml("PRIVATE", &["CERT"]).replace(
            "<NumberOfKeyboxes>1</NumberOfKeyboxes>",
            "<NumberOfKeyboxes>2</NumberOfKeyboxes>",
        );
        assert_eq!(
            parse_keybox_xml(&mismatch).unwrap_err(),
            XmlError::InvalidKeyboxCount
        );

        let cert_mismatch = one_key_xml("PRIVATE", &["CERT"]).replace(
            "<NumberOfCertificates>1</NumberOfCertificates>",
            "<NumberOfCertificates>2</NumberOfCertificates>",
        );
        assert_eq!(
            parse_keybox_xml(&cert_mismatch).unwrap_err(),
            XmlError::InvalidCertificateCount
        );
    }

    #[test]
    fn required_fields_are_not_synthesized() {
        let missing_algorithm =
            one_key_xml("PRIVATE", &["CERT"]).replace(" algorithm=\"ecdsa\"", "");
        assert_eq!(
            parse_keybox_xml(&missing_algorithm).unwrap_err(),
            XmlError::MissingField
        );
        let empty_private = one_key_xml("", &["CERT"]);
        assert_eq!(
            parse_keybox_xml(&empty_private).unwrap_err(),
            XmlError::MissingField
        );
    }

    #[test]
    fn malformed_and_invalid_utf8_input_fail_closed() {
        assert_eq!(
            parse_keybox_xml("<AndroidAttestation><Keybox></AndroidAttestation>").unwrap_err(),
            XmlError::Malformed
        );
        assert_eq!(
            parse_keybox_xml_bytes(&[0xff, 0xfe]).unwrap_err(),
            XmlError::InvalidUtf8
        );
    }

    #[test]
    fn pem_fields_are_bounded_in_managed_utf16_units() {
        let oversized = "🔐".repeat(MAX_PEM_UTF16_UNITS / 2 + 1);
        let xml = one_key_xml(&oversized, &["CERT"]);
        assert_eq!(parse_keybox_xml(&xml).unwrap_err(), XmlError::PemLimit);
    }

    #[test]
    fn structural_limits_are_enforced_before_unbounded_growth() {
        let attributes = (0..=MAX_ATTRIBUTES_PER_ELEMENT)
            .map(|index| format!(" a{index}=\"x\""))
            .collect::<String>();
        let xml = format!("<AndroidAttestation{attributes}/>");
        assert_eq!(
            parse_keybox_xml(&xml).unwrap_err(),
            XmlError::AttributeLimit
        );

        let mut nested = String::from("<AndroidAttestation>");
        for _ in 0..MAX_DEPTH {
            nested.push_str("<x>");
        }
        for _ in 0..MAX_DEPTH {
            nested.push_str("</x>");
        }
        nested.push_str("</AndroidAttestation>");
        assert_eq!(parse_keybox_xml(&nested).unwrap_err(), XmlError::DepthLimit);
    }
}
