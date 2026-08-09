from pathlib import Path

p = Path("rust/native-core/src/binder_parser.rs")
s = p.read_text()
s = s.replace(
    '''                    safe_read::<usize>(transaction, cache.data_ptr_offset),
                ) else {
                    return false;
                };
''',
    '''                    safe_read::<usize>(transaction, cache.data_ptr_offset),
                )
                else {
                    return false;
                };
''',
    1,
)
s = s.replace(
    '''        let mut probe = vec![
            0u8;
            first_end + mem::size_of::<u32>() + second_payload_size
        ];
''',
    '''        let mut probe = vec![0u8; first_end + mem::size_of::<u32>() + second_payload_size];
''',
    1,
)
p.write_text(s)
