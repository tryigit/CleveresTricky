from pathlib import Path

p = Path("rust/native-core/src/binder_parser.rs")
s = p.read_text()

old = '''pub fn validate_binder_probe(buffer: &[u8], transaction_size: usize) -> bool {
    if buffer.len() < mem::size_of::<u32>() || !(40..=512).contains(&transaction_size) {
        return false;
    }
    let mut position = 0usize;
    while position + mem::size_of::<u32>() <= buffer.len() {
        let Some(command) = safe_read::<u32>(buffer, position) else {
            return false;
        };
        position += mem::size_of::<u32>();
        let payload_size = ioctl_size(command);
        let Some(end) = position.checked_add(payload_size) else {
            return false;
        };
        if end > buffer.len() {
            return false;
        }
        if is_probe_layout_command(command) {
            return payload_size == transaction_size;
        }
        position = end;
    }
    false
}
'''
new = '''pub fn validate_binder_probe(buffer: &[u8], transaction_size: usize) -> bool {
    if buffer.len() < mem::size_of::<u32>() || !(40..=512).contains(&transaction_size) {
        return false;
    }
    let mut position = 0usize;
    let mut matched_layout = false;
    while position + mem::size_of::<u32>() <= buffer.len() {
        let Some(command) = safe_read::<u32>(buffer, position) else {
            return false;
        };
        position += mem::size_of::<u32>();
        let payload_size = ioctl_size(command);
        let Some(end) = position.checked_add(payload_size) else {
            return false;
        };
        if end > buffer.len() {
            return false;
        }
        if is_probe_layout_command(command) {
            if payload_size != transaction_size {
                return false;
            }
            matched_layout = true;
        }
        position = end;
    }
    matched_layout && position == buffer.len()
}
'''
if old not in s:
    raise SystemExit("probe parser marker not found")
s = s.replace(old, new, 1)

old = '''                let parsed = (
                    safe_read::<usize>(transaction, cache.target_ptr_offset),
                    safe_read::<usize>(transaction, cache.cookie_offset),
                    safe_read::<u32>(transaction, cache.code_offset),
                    safe_read::<u32>(transaction, cache.flags_offset),
                    safe_read::<i32>(transaction, cache.sender_pid_offset),
                    safe_read::<u32>(transaction, cache.sender_euid_offset),
                    safe_read::<u64>(transaction, cache.data_size_offset),
                    safe_read::<usize>(transaction, cache.data_ptr_offset),
                );

                if let (
                    Some(target_ptr),
                    Some(cookie),
                    Some(code),
                    Some(flags),
                    Some(sender_pid),
                    Some(sender_euid),
                    Some(data_size),
                    Some(data_buffer),
                ) = parsed
                {
                    let Some(raw_ptr) = (buffer_pointer as usize).checked_add(position) else {
                        return false;
                    };
                    output[count_slice[0]] = RustParsedTransaction {
                        target_ptr,
                        cookie,
                        code,
                        flags,
                        sender_pid,
                        sender_euid,
                        data_size,
                        data_buffer,
                        cmd: command,
                        raw_ptr,
                        raw_size: payload_size,
                        valid: 1,
                    };
                    count_slice[0] += 1;
                }
'''
new = '''                let (
                    Some(target_ptr),
                    Some(cookie),
                    Some(code),
                    Some(flags),
                    Some(sender_pid),
                    Some(sender_euid),
                    Some(data_size),
                    Some(data_buffer),
                ) = (
                    safe_read::<usize>(transaction, cache.target_ptr_offset),
                    safe_read::<usize>(transaction, cache.cookie_offset),
                    safe_read::<u32>(transaction, cache.code_offset),
                    safe_read::<u32>(transaction, cache.flags_offset),
                    safe_read::<i32>(transaction, cache.sender_pid_offset),
                    safe_read::<u32>(transaction, cache.sender_euid_offset),
                    safe_read::<u64>(transaction, cache.data_size_offset),
                    safe_read::<usize>(transaction, cache.data_ptr_offset),
                ) else {
                    return false;
                };
                let Some(raw_ptr) = (buffer_pointer as usize).checked_add(position) else {
                    return false;
                };
                output[count_slice[0]] = RustParsedTransaction {
                    target_ptr,
                    cookie,
                    code,
                    flags,
                    sender_pid,
                    sender_euid,
                    data_size,
                    data_buffer,
                    cmd: command,
                    raw_ptr,
                    raw_size: payload_size,
                    valid: 1,
                };
                count_slice[0] += 1;
'''
if old not in s:
    raise SystemExit("transaction field marker not found")
s = s.replace(old, new, 1)

old = '''    #[test]
    fn validates_a_live_transaction_probe() {
        let payload_size = 64usize;
        let command = (IOC_READ << IOC_DIRECTION_SHIFT)
            | (payload_size as u32) << IOC_SIZE_SHIFT
            | (BINDER_TYPE << IOC_TYPE_SHIFT)
            | REPLY_NUMBER;
        let mut probe = vec![0u8; mem::size_of::<u32>() + payload_size];
        write_at(&mut probe, 0, command);
        assert!(validate_binder_probe(&probe, payload_size));
        assert!(!validate_binder_probe(&probe, payload_size + 8));
    }
'''
new = '''    #[test]
    fn validates_a_live_transaction_probe() {
        let payload_size = 64usize;
        let command = (IOC_READ << IOC_DIRECTION_SHIFT)
            | (payload_size as u32) << IOC_SIZE_SHIFT
            | (BINDER_TYPE << IOC_TYPE_SHIFT)
            | REPLY_NUMBER;
        let mut probe = vec![0u8; mem::size_of::<u32>() + payload_size];
        write_at(&mut probe, 0, command);
        assert!(validate_binder_probe(&probe, payload_size));
        assert!(!validate_binder_probe(&probe, payload_size + 8));
    }

    #[test]
    fn rejects_a_probe_with_trailing_partial_command_bytes() {
        let payload_size = 64usize;
        let command = (IOC_READ << IOC_DIRECTION_SHIFT)
            | (payload_size as u32) << IOC_SIZE_SHIFT
            | (BINDER_TYPE << IOC_TYPE_SHIFT)
            | REPLY_NUMBER;
        let mut probe = vec![0u8; mem::size_of::<u32>() + payload_size + 1];
        write_at(&mut probe, 0, command);
        assert!(!validate_binder_probe(&probe, payload_size));
    }

    #[test]
    fn rejects_a_probe_when_a_later_layout_command_disagrees() {
        let payload_size = 64usize;
        let first_command = (IOC_READ << IOC_DIRECTION_SHIFT)
            | (payload_size as u32) << IOC_SIZE_SHIFT
            | (BINDER_TYPE << IOC_TYPE_SHIFT)
            | REPLY_NUMBER;
        let second_payload_size = payload_size + 8;
        let second_command = (IOC_READ << IOC_DIRECTION_SHIFT)
            | (second_payload_size as u32) << IOC_SIZE_SHIFT
            | (BINDER_TYPE << IOC_TYPE_SHIFT)
            | TRANSACTION_NUMBER;
        let first_end = mem::size_of::<u32>() + payload_size;
        let mut probe = vec![
            0u8;
            first_end + mem::size_of::<u32>() + second_payload_size
        ];
        write_at(&mut probe, 0, first_command);
        write_at(&mut probe, first_end, second_command);
        assert!(!validate_binder_probe(&probe, payload_size));
    }
'''
if old not in s:
    raise SystemExit("probe test marker not found")
s = s.replace(old, new, 1)
p.write_text(s)
