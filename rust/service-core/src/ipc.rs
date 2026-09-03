// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use std::io::{self, Read, Write};

pub const PROTOCOL_MAGIC: [u8; 4] = *b"CTIP";
pub const PROTOCOL_VERSION: u16 = 1;
pub const HEADER_BYTES: usize = 16;
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;
pub const STREAM_COPY_BYTES: usize = 64 * 1024;

pub const OP_PING: u16 = 1;
pub const OP_ADAPTER_REGISTER: u16 = 2;
pub const OP_WEB_REQUEST: u16 = 3;
pub const OP_FILE_WRITE: u16 = 10;
pub const OP_FILE_READ: u16 = 11;
pub const OP_CRYPTO_CBOX_OPEN: u16 = 20;
pub const OP_CRYPTO_BACKUP_ENCRYPT: u16 = 21;
pub const OP_CRYPTO_BACKUP_DECRYPT: u16 = 22;
pub const OP_KEYBOX_PARSE: u16 = 23;
pub const OP_INTEGRITY_VERIFY_FULL: u16 = 0x30;
pub const OP_INTEGRITY_VERIFY_FILE: u16 = 0x31;
pub const OP_INTEGRITY_DELETE_MODULE: u16 = 0x32;
pub const FLAG_ERROR: u32 = 1;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FrameHeader {
    pub opcode: u16,
    pub flags: u32,
    pub payload_len: usize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FrameRef<'a> {
    pub opcode: u16,
    pub flags: u32,
    pub payload: &'a [u8],
}

/// Reads an IPC frame header with the default maximum payload size.
pub fn read_header<R: Read>(reader: &mut R) -> io::Result<FrameHeader> {
    read_header_bounded(reader, MAX_FRAME_BYTES)
}

/// Reads an IPC frame header with a custom maximum payload size.
pub fn read_header_bounded<R: Read>(reader: &mut R, max_payload: usize) -> io::Result<FrameHeader> {
    if max_payload > u32::MAX as usize {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "IPC bound exceeds wire format",
        ));
    }
    let mut header = [0u8; HEADER_BYTES];
    read_exact_retry(reader, &mut header)?;
    if header[0..4] != PROTOCOL_MAGIC {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid IPC magic",
        ));
    }
    let version = u16::from_be_bytes([header[4], header[5]]);
    if version != PROTOCOL_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unsupported IPC version",
        ));
    }
    let opcode = u16::from_be_bytes([header[6], header[7]]);
    if opcode == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid IPC opcode",
        ));
    }
    let flags = u32::from_be_bytes(header[8..12].try_into().expect("fixed header slice"));
    let payload_len =
        u32::from_be_bytes(header[12..16].try_into().expect("fixed header slice")) as usize;
    if payload_len > max_payload {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "IPC frame exceeds configured bound",
        ));
    }
    Ok(FrameHeader {
        opcode,
        flags,
        payload_len,
    })
}

/// Writes an IPC frame header with the default maximum payload size.
pub fn write_header<W: Write>(writer: &mut W, header: FrameHeader) -> io::Result<()> {
    write_header_bounded(writer, header, MAX_FRAME_BYTES)
}

/// Writes an IPC frame header with a custom maximum payload size.
pub fn write_header_bounded<W: Write>(
    writer: &mut W,
    header: FrameHeader,
    max_payload: usize,
) -> io::Result<()> {
    if max_payload > u32::MAX as usize
        || header.opcode == 0
        || header.payload_len > max_payload
        || header.payload_len > u32::MAX as usize
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid IPC frame",
        ));
    }
    let mut encoded = [0u8; HEADER_BYTES];
    encoded[0..4].copy_from_slice(&PROTOCOL_MAGIC);
    encoded[4..6].copy_from_slice(&PROTOCOL_VERSION.to_be_bytes());
    encoded[6..8].copy_from_slice(&header.opcode.to_be_bytes());
    encoded[8..12].copy_from_slice(&header.flags.to_be_bytes());
    encoded[12..16].copy_from_slice(&(header.payload_len as u32).to_be_bytes());
    write_all_retry(writer, &encoded)
}

/// Reads an IPC frame header and payload into the provided scratch buffer.
pub fn read_frame_into<'a, R: Read>(
    reader: &mut R,
    scratch: &'a mut [u8],
) -> io::Result<FrameRef<'a>> {
    let header = read_header(reader)?;
    if header.payload_len > scratch.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "IPC frame exceeds caller buffer",
        ));
    }
    read_exact_retry(reader, &mut scratch[..header.payload_len])?;
    Ok(FrameRef {
        opcode: header.opcode,
        flags: header.flags,
        payload: &scratch[..header.payload_len],
    })
}

/// Writes a complete IPC frame with header and payload using the default size limit.
pub fn write_frame<W: Write>(
    writer: &mut W,
    opcode: u16,
    flags: u32,
    payload: &[u8],
) -> io::Result<()> {
    write_frame_bounded(writer, opcode, flags, payload, MAX_FRAME_BYTES)
}

/// Writes a complete IPC frame with header and payload using a custom size limit.
pub fn write_frame_bounded<W: Write>(
    writer: &mut W,
    opcode: u16,
    flags: u32,
    payload: &[u8],
    max_payload: usize,
) -> io::Result<()> {
    write_header_bounded(
        writer,
        FrameHeader {
            opcode,
            flags,
            payload_len: payload.len(),
        },
        max_payload,
    )?;
    write_all_retry(writer, payload)
}

/// Relays exactly `remaining` bytes from reader to writer using a scratch buffer.
pub fn relay_exact<R: Read, W: Write>(
    reader: &mut R,
    writer: &mut W,
    remaining: usize,
    scratch: &mut [u8],
) -> io::Result<()> {
    relay_exact_bounded(reader, writer, remaining, scratch, MAX_FRAME_BYTES)
}

/// Relays exactly `remaining` bytes with a custom maximum payload limit.
pub fn relay_exact_bounded<R: Read, W: Write>(
    reader: &mut R,
    writer: &mut W,
    mut remaining: usize,
    scratch: &mut [u8],
    max_payload: usize,
) -> io::Result<()> {
    if max_payload > u32::MAX as usize
        || remaining > max_payload
        || (remaining != 0 && scratch.is_empty())
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid bounded relay",
        ));
    }
    while remaining != 0 {
        let chunk_len = remaining.min(scratch.len());
        read_exact_retry(reader, &mut scratch[..chunk_len])?;
        write_all_retry(writer, &scratch[..chunk_len])?;
        remaining -= chunk_len;
    }
    Ok(())
}

/// Reads exactly the required number of bytes, retrying on EINTR.
fn read_exact_retry<R: Read>(reader: &mut R, mut output: &mut [u8]) -> io::Result<()> {
    while !output.is_empty() {
        match reader.read(output) {
            Ok(0) => {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "truncated IPC frame",
                ))
            }
            Ok(read) => {
                let (_, rest) = output.split_at_mut(read);
                output = rest;
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

/// Writes all bytes from input, retrying on EINTR.
fn write_all_retry<W: Write>(writer: &mut W, mut input: &[u8]) -> io::Result<()> {
    while !input.is_empty() {
        match writer.write(input) {
            Ok(0) => {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "stalled IPC write",
                ))
            }
            Ok(written) => input = &input[written..],
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cmp;
    use std::io::Cursor;

    /// A test writer that accepts data in small chunks to exercise retry logic.
    struct ChunkedWriter {
        bytes: Vec<u8>,
        chunk: usize,
    }

    impl Write for ChunkedWriter {
        fn write(&mut self, input: &[u8]) -> io::Result<usize> {
            let take = cmp::min(input.len(), self.chunk);
            self.bytes.extend_from_slice(&input[..take]);
            Ok(take)
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    /// A test reader that provides data in small chunks to exercise retry logic.
    struct ChunkedReader {
        cursor: Cursor<Vec<u8>>,
        chunk: usize,
    }

    impl Read for ChunkedReader {
        fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
            let limit = cmp::min(output.len(), self.chunk);
            self.cursor.read(&mut output[..limit])
        }
    }

    #[test]
    fn partial_reads_and_writes_round_trip_into_caller_buffer() {
        let payload = b"bounded-control-payload";
        let mut writer = ChunkedWriter {
            bytes: Vec::new(),
            chunk: 3,
        };
        write_frame(&mut writer, 7, 0x10, payload).unwrap();

        let mut reader = ChunkedReader {
            cursor: Cursor::new(writer.bytes),
            chunk: 2,
        };
        let mut scratch = [0u8; 64];
        let frame = read_frame_into(&mut reader, &mut scratch).unwrap();
        assert_eq!(frame.opcode, 7);
        assert_eq!(frame.flags, 0x10);
        assert_eq!(frame.payload, payload);
    }

    #[test]
    fn relay_streams_a_frame_with_fixed_scratch() {
        let input: Vec<u8> = (0..=255).cycle().take(200_000).collect();
        let mut reader = Cursor::new(input.clone());
        let mut output = ChunkedWriter {
            bytes: Vec::new(),
            chunk: 97,
        };
        let mut scratch = [0u8; 4096];
        relay_exact(&mut reader, &mut output, input.len(), &mut scratch).unwrap();
        assert_eq!(output.bytes, input);
    }

    #[test]
    fn rejects_truncated_header() {
        let mut reader = Cursor::new(PROTOCOL_MAGIC.to_vec());
        let mut scratch = [0u8; 8];
        assert_eq!(
            read_frame_into(&mut reader, &mut scratch)
                .unwrap_err()
                .kind(),
            io::ErrorKind::UnexpectedEof
        );
    }

    #[test]
    fn rejects_oversized_frame_before_reading_payload() {
        let mut header = [0u8; HEADER_BYTES];
        header[0..4].copy_from_slice(&PROTOCOL_MAGIC);
        header[4..6].copy_from_slice(&PROTOCOL_VERSION.to_be_bytes());
        header[6..8].copy_from_slice(&1u16.to_be_bytes());
        header[12..16].copy_from_slice(&((MAX_FRAME_BYTES as u32) + 1).to_be_bytes());
        let mut reader = Cursor::new(header);
        assert_eq!(
            read_header(&mut reader).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn explicit_bound_allows_large_control_plane_frames_without_relaxing_default() {
        let larger = MAX_FRAME_BYTES + 1;
        let mut header = [0u8; HEADER_BYTES];
        header[0..4].copy_from_slice(&PROTOCOL_MAGIC);
        header[4..6].copy_from_slice(&PROTOCOL_VERSION.to_be_bytes());
        header[6..8].copy_from_slice(&OP_CRYPTO_BACKUP_ENCRYPT.to_be_bytes());
        header[12..16].copy_from_slice(&(larger as u32).to_be_bytes());

        assert_eq!(
            read_header(&mut Cursor::new(header)).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
        assert_eq!(
            read_header_bounded(&mut Cursor::new(header), larger)
                .unwrap()
                .payload_len,
            larger
        );
    }

    #[test]
    fn caller_buffer_is_an_independent_stricter_bound() {
        let mut bytes = Vec::new();
        write_frame(&mut bytes, 1, 0, &[1u8; 64]).unwrap();
        let mut reader = Cursor::new(bytes);
        let mut scratch = [0u8; 32];
        assert_eq!(
            read_frame_into(&mut reader, &mut scratch)
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_unknown_version_and_zero_opcode() {
        let mut header = [0u8; HEADER_BYTES];
        header[0..4].copy_from_slice(&PROTOCOL_MAGIC);
        header[4..6].copy_from_slice(&(PROTOCOL_VERSION + 1).to_be_bytes());
        header[6..8].copy_from_slice(&1u16.to_be_bytes());
        let mut reader = Cursor::new(header);
        assert_eq!(
            read_header(&mut reader).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );

        let mut header = [0u8; HEADER_BYTES];
        header[0..4].copy_from_slice(&PROTOCOL_MAGIC);
        header[4..6].copy_from_slice(&PROTOCOL_VERSION.to_be_bytes());
        let mut reader = Cursor::new(header);
        assert_eq!(
            read_header(&mut reader).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }
}
