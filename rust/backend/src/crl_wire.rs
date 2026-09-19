// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use cleverestricky_crl_core::{CrlIndex, MAX_CRL_BYTES, MAX_SERIAL_BYTES, MAX_SPKI_BYTES};
use std::sync::{Arc, Mutex, OnceLock};
use zeroize::Zeroize;

const WIRE_VERSION: u8 = 2;
const ACTION_REFRESH: u8 = 1;
const ACTION_QUERY: u8 = 2;
const REFRESH_PREFIX_BYTES: usize = 6;
const REFRESH_RESPONSE_BYTES: usize = 18;
const QUERY_PREFIX_BYTES: usize = 12;
const QUERY_RESPONSE_PREFIX_BYTES: usize = 12;
pub const MAX_QUERY_COUNT: usize = 16 * 1024;
pub const MAX_REQUEST_BYTES: usize = 24 * 1024 * 1024;
pub const MAX_RESPONSE_BYTES: usize = QUERY_RESPONSE_PREFIX_BYTES + MAX_QUERY_COUNT.div_ceil(8);

struct Snapshot {
    generation: u64,
    index: Arc<CrlIndex>,
}

static STORE: OnceLock<Mutex<Option<Snapshot>>> = OnceLock::new();

pub fn handle(mut request: Vec<u8>) -> Result<Vec<u8>, &'static str> {
    let result = handle_inner(&request);
    request.zeroize();
    result
}

fn handle_inner(request: &[u8]) -> Result<Vec<u8>, &'static str> {
    if request.len() < 2 || request.len() > MAX_REQUEST_BYTES || request[0] != WIRE_VERSION {
        return Err("CRL wire request rejected");
    }
    match request[1] {
        ACTION_REFRESH => refresh(request),
        ACTION_QUERY => query(request),
        _ => Err("unsupported CRL wire action"),
    }
}

fn refresh(request: &[u8]) -> Result<Vec<u8>, &'static str> {
    if request.len() < REFRESH_PREFIX_BYTES {
        return Err("CRL refresh request is truncated");
    }
    let crl_len = read_u32(request, 2)?;
    if crl_len == 0 || crl_len > MAX_CRL_BYTES || request.len() != REFRESH_PREFIX_BYTES + crl_len {
        return Err("CRL refresh request exceeds configured bound");
    }
    let index = CrlIndex::parse(&request[REFRESH_PREFIX_BYTES..]).map_err(|_| "CRL rejected")?;
    let raw_count =
        u32::try_from(index.raw_entry_count()).map_err(|_| "CRL raw count exceeds wire bound")?;
    let normalized_count = u32::try_from(index.normalized_count())
        .map_err(|_| "CRL normalized count exceeds wire bound")?;

    let store = STORE.get_or_init(|| Mutex::new(None));
    let mut guard = store.lock().map_err(|_| "CRL store lock poisoned")?;
    let generation = guard
        .as_ref()
        .map_or(1, |snapshot| snapshot.generation.saturating_add(1).max(1));
    *guard = Some(Snapshot {
        generation,
        index: Arc::new(index),
    });

    let mut response = Vec::with_capacity(REFRESH_RESPONSE_BYTES);
    response.push(WIRE_VERSION);
    response.push(ACTION_REFRESH);
    response.extend_from_slice(&generation.to_be_bytes());
    response.extend_from_slice(&raw_count.to_be_bytes());
    response.extend_from_slice(&normalized_count.to_be_bytes());
    debug_assert_eq!(response.len(), REFRESH_RESPONSE_BYTES);
    Ok(response)
}

fn query(request: &[u8]) -> Result<Vec<u8>, &'static str> {
    if request.len() < QUERY_PREFIX_BYTES {
        return Err("CRL query request is truncated");
    }
    let generation = read_u64(request, 2)?;
    let query_count = read_u16(request, 10)?;
    if generation == 0 || query_count > MAX_QUERY_COUNT {
        return Err("CRL query request exceeds configured bound");
    }

    // Clone the immutable index under the lock, then answer from the snapshot:
    // per-entry hashing must not block concurrent refreshes.
    let (snapshot_generation, index) = {
        let store = STORE.get_or_init(|| Mutex::new(None));
        let guard = store.lock().map_err(|_| "CRL store lock poisoned")?;
        match guard.as_ref() {
            Some(snapshot) => (snapshot.generation, Arc::clone(&snapshot.index)),
            None => return Err("CRL index is not initialized"),
        }
    };
    if snapshot_generation != generation {
        return Err("CRL generation is stale");
    }

    let response_len = QUERY_RESPONSE_PREFIX_BYTES
        .checked_add(query_count.div_ceil(8))
        .ok_or("CRL query response length overflow")?;
    if response_len > MAX_RESPONSE_BYTES {
        return Err("CRL query response exceeds configured bound");
    }
    let mut response = Vec::with_capacity(response_len);
    response.push(WIRE_VERSION);
    response.push(ACTION_QUERY);
    response.extend_from_slice(&generation.to_be_bytes());
    response.extend_from_slice(&(query_count as u16).to_be_bytes());
    response.resize(response_len, 0);

    let mut cursor = Cursor::new(request, QUERY_PREFIX_BYTES);
    for query_index in 0..query_count {
        let serial_len = cursor.read_u16()?;
        let spki_len = cursor.read_u32()?;
        if serial_len == 0
            || serial_len > MAX_SERIAL_BYTES
            || spki_len == 0
            || spki_len > MAX_SPKI_BYTES
        {
            return Err("CRL query fields exceed configured bound");
        }
        let serial = cursor.read_bytes(serial_len)?;
        let spki = cursor.read_bytes(spki_len)?;
        if index
            .is_revoked(serial, spki)
            .map_err(|_| "CRL query rejected")?
        {
            response[QUERY_RESPONSE_PREFIX_BYTES + query_index / 8] |= 1u8 << (query_index % 8);
        }
    }
    if !cursor.is_at_end() {
        return Err("CRL query request has trailing bytes");
    }
    Ok(response)
}

fn read_u16(input: &[u8], offset: usize) -> Result<usize, &'static str> {
    let bytes: [u8; 2] = input
        .get(offset..offset.checked_add(2).ok_or("CRL wire length overflow")?)
        .ok_or("CRL wire is truncated")?
        .try_into()
        .map_err(|_| "CRL wire is truncated")?;
    Ok(u16::from_be_bytes(bytes) as usize)
}

fn read_u32(input: &[u8], offset: usize) -> Result<usize, &'static str> {
    let bytes: [u8; 4] = input
        .get(offset..offset.checked_add(4).ok_or("CRL wire length overflow")?)
        .ok_or("CRL wire is truncated")?
        .try_into()
        .map_err(|_| "CRL wire is truncated")?;
    Ok(u32::from_be_bytes(bytes) as usize)
}

fn read_u64(input: &[u8], offset: usize) -> Result<u64, &'static str> {
    let bytes: [u8; 8] = input
        .get(offset..offset.checked_add(8).ok_or("CRL wire length overflow")?)
        .ok_or("CRL wire is truncated")?
        .try_into()
        .map_err(|_| "CRL wire is truncated")?;
    Ok(u64::from_be_bytes(bytes))
}

struct Cursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8], offset: usize) -> Self {
        Self { bytes, offset }
    }

    fn read_u16(&mut self) -> Result<usize, &'static str> {
        let value = read_u16(self.bytes, self.offset)?;
        self.offset = self
            .offset
            .checked_add(2)
            .ok_or("CRL wire length overflow")?;
        Ok(value)
    }

    fn read_u32(&mut self) -> Result<usize, &'static str> {
        let value = read_u32(self.bytes, self.offset)?;
        self.offset = self
            .offset
            .checked_add(4)
            .ok_or("CRL wire length overflow")?;
        Ok(value)
    }

    fn read_bytes(&mut self, length: usize) -> Result<&'a [u8], &'static str> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or("CRL wire length overflow")?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or("CRL wire is truncated")?;
        self.offset = end;
        Ok(value)
    }

    fn is_at_end(&self) -> bool {
        self.offset == self.bytes.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::MutexGuard;

    static TEST_STORE_SEQUENCE: OnceLock<Mutex<()>> = OnceLock::new();

    fn isolate_store_sequence() -> MutexGuard<'static, ()> {
        TEST_STORE_SEQUENCE
            .get_or_init(|| Mutex::new(()))
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn refresh_request(crl: &[u8]) -> Vec<u8> {
        let mut output = Vec::new();
        output.push(WIRE_VERSION);
        output.push(ACTION_REFRESH);
        output.extend_from_slice(&(crl.len() as u32).to_be_bytes());
        output.extend_from_slice(crl);
        output
    }

    fn query_request(generation: u64, queries: &[(&[u8], &[u8])]) -> Vec<u8> {
        let mut output = Vec::new();
        output.push(WIRE_VERSION);
        output.push(ACTION_QUERY);
        output.extend_from_slice(&generation.to_be_bytes());
        output.extend_from_slice(&(queries.len() as u16).to_be_bytes());
        for (serial, spki) in queries {
            output.extend_from_slice(&(serial.len() as u16).to_be_bytes());
            output.extend_from_slice(&(spki.len() as u32).to_be_bytes());
            output.extend_from_slice(serial);
            output.extend_from_slice(spki);
        }
        output
    }

    fn generation(response: &[u8]) -> u64 {
        u64::from_be_bytes(response[2..10].try_into().unwrap())
    }

    fn response_bit(response: &[u8], index: usize) -> bool {
        response[QUERY_RESPONSE_PREFIX_BYTES + index / 8] & (1u8 << (index % 8)) != 0
    }

    #[test]
    fn refresh_once_then_query_without_resending_crl() {
        let _sequence = isolate_store_sequence();
        let crl = br#"{"entries":{"1":0,"900150983cd24fb0d6963f7d28e17f72":0}}"#;
        let refresh = handle(refresh_request(crl)).unwrap();
        assert_eq!(refresh[0], WIRE_VERSION);
        assert_eq!(refresh[1], ACTION_REFRESH);
        assert_eq!(u32::from_be_bytes(refresh[10..14].try_into().unwrap()), 2);
        let generation = generation(&refresh);

        let query = handle(query_request(
            generation,
            &[
                (&[0x01], b"unrelated"),
                (&[0x02], b"abc"),
                (&[0x03], b"def"),
            ],
        ))
        .unwrap();
        assert_eq!(query[1], ACTION_QUERY);
        assert_eq!(
            generation,
            u64::from_be_bytes(query[2..10].try_into().unwrap())
        );
        assert!(response_bit(&query, 0));
        assert!(response_bit(&query, 1));
        assert!(!response_bit(&query, 2));
    }

    #[test]
    fn failed_refresh_preserves_previous_generation_and_index() {
        let _sequence = isolate_store_sequence();
        let first = handle(refresh_request(br#"{"entries":{"1":0}}"#)).unwrap();
        let generation = generation(&first);
        assert!(handle(refresh_request(b"not-json")).is_err());
        let query = handle(query_request(generation, &[(&[0x01], b"x")])).unwrap();
        assert!(response_bit(&query, 0));
    }

    #[test]
    fn successful_refresh_advances_generation_and_stale_query_fails_closed() {
        let _sequence = isolate_store_sequence();
        let first = handle(refresh_request(br#"{"entries":{"1":0}}"#)).unwrap();
        let old = generation(&first);
        let second = handle(refresh_request(br#"{"entries":{"2":0}}"#)).unwrap();
        let new = generation(&second);
        assert_ne!(old, new);
        assert!(handle(query_request(old, &[(&[0x01], b"x")])).is_err());
        let query = handle(query_request(new, &[(&[0x02], b"x")])).unwrap();
        assert!(response_bit(&query, 0));
    }

    #[test]
    fn malformed_lengths_and_oversized_fields_fail_closed() {
        let _sequence = isolate_store_sequence();
        assert!(handle(vec![WIRE_VERSION, ACTION_REFRESH, 0, 0, 0, 0]).is_err());
        let generation = generation(&handle(refresh_request(br#"{"entries":{}}"#)).unwrap());
        let oversized_serial = vec![0u8; MAX_SERIAL_BYTES + 1];
        assert!(handle(query_request(generation, &[(&oversized_serial, b"spki")])).is_err());
        let mut trailing = query_request(generation, &[]);
        trailing.push(1);
        assert!(handle(trailing).is_err());
    }
}
