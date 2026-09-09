//! Numbers and strings as bytes.
//!
//! Every count in a pack is small and most are tiny - a sense has a handful of marks, an entry
//! a handful of senses - so they are written in as few bytes as they need rather than in eight.
//! The encoding is the usual one: seven bits a byte, the top bit set while more follow.

/// Append a number.
pub fn put(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// Append a string: its length, then its bytes.
pub fn put_str(out: &mut Vec<u8>, text: &str) {
    put(out, text.len() as u64);
    out.extend_from_slice(text.as_bytes());
}

/// Read a number, moving the cursor past it.
pub fn get(bytes: &[u8], at: &mut usize) -> Option<u64> {
    let mut value = 0u64;
    let mut shift = 0u32;
    loop {
        let byte = *bytes.get(*at)?;
        *at += 1;
        // Ten sevens is seventy bits, past what a u64 holds: a longer run than that is a
        // corrupt file rather than a large number.
        if shift >= 64 {
            return None;
        }
        value |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some(value);
        }
        shift += 7;
    }
}

/// Read a string, moving the cursor past it.
pub fn get_str(bytes: &[u8], at: &mut usize) -> Option<String> {
    let len = get(bytes, at)? as usize;
    let end = at.checked_add(len)?;
    let slice = bytes.get(*at..end)?;
    *at = end;
    std::str::from_utf8(slice).ok().map(|s| s.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_survive_the_round_trip() {
        for value in [0u64, 1, 127, 128, 300, 65535, u32::MAX as u64, u64::MAX] {
            let mut bytes = Vec::new();
            put(&mut bytes, value);
            let mut at = 0;
            assert_eq!(get(&bytes, &mut at), Some(value));
            assert_eq!(at, bytes.len(), "the cursor lands past the number");
        }
    }

    #[test]
    fn strings_survive_the_round_trip() {
        let mut bytes = Vec::new();
        put_str(&mut bytes, "Straße");
        put_str(&mut bytes, "");
        put_str(&mut bytes, "書");
        let mut at = 0;
        assert_eq!(get_str(&bytes, &mut at).as_deref(), Some("Straße"));
        assert_eq!(get_str(&bytes, &mut at).as_deref(), Some(""));
        assert_eq!(get_str(&bytes, &mut at).as_deref(), Some("書"));
    }

    #[test]
    fn a_truncated_number_is_refused_rather_than_guessed() {
        let bytes = [0x80u8, 0x80];
        let mut at = 0;
        assert_eq!(get(&bytes, &mut at), None);
    }

    #[test]
    fn a_string_longer_than_what_is_left_is_refused() {
        let mut bytes = Vec::new();
        put(&mut bytes, 50);
        bytes.extend_from_slice(b"short");
        let mut at = 0;
        assert_eq!(get_str(&bytes, &mut at), None);
    }
}
