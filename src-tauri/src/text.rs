//! Mojibake repair, ported from `ig_chat_viewer.html:92` (`fix()`) and
//! `ig_chat_rebuild.py:28` (`fix()`).
//!
//! Instagram stores UTF-8 bytes as if they were latin-1 text. Repairing means
//! taking the string's characters as latin-1 bytes and decoding those bytes as
//! UTF-8. Both steps can fail, and when either does the ORIGINAL string is
//! returned untouched -- that is what lets correctly-stored non-latin-1 text
//! (Cyrillic, Arabic, emoji, `café` stored properly) survive unchanged.

/// Repair a mojibaked string.
///
/// Contract, in order:
///  1. If any character is above U+00FF, return the original.
///  2. Convert each character to its single latin-1 byte.
///  3. Decode those bytes as UTF-8. On failure return the original.
///  4. Otherwise return the decoded string.
///
/// Step 2 is a real latin-1 conversion, not `s.as_bytes()`: for characters in
/// U+0080..=U+00FF the byte value is the code point, and any character above
/// U+00FF has already been rejected by step 1.
pub fn fix(s: &str) -> String {
    fix_opt(s).unwrap_or_else(|| s.to_string())
}

/// `None` means "no repair possible, use the original unchanged".
fn fix_opt(s: &str) -> Option<String> {
    let mut bytes = Vec::with_capacity(s.len());
    for c in s.chars() {
        let cp = c as u32;
        if cp > 0xFF {
            // Not representable in latin-1: this was never mojibake.
            return None;
        }
        bytes.push(cp as u8);
    }
    // from_utf8 fails on any invalid UTF-8 sequence, e.g. a lone 0x80.
    String::from_utf8(bytes).ok()
}

/// True when `fix()` would actually change `s`.
pub fn needs_fix(s: &str) -> bool {
    fix_opt(s).is_some_and(|fixed| fixed != s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn repairs_latin1_mojibake() {
        let real = "café";
        let as_latin1: String = real.as_bytes().iter().map(|&b| b as char).collect();
        assert_eq!(fix(&as_latin1), real);
    }

    #[test]
    fn returns_original_when_char_above_u00ff() {
        // Cyrillic and Arabic are above U+00FF and must never be touched.
        assert_eq!(fix("Привет"), "Привет");
        assert_eq!(fix("مرحبا"), "مرحبا");
        assert_eq!(fix("😀🎉"), "😀🎉");
    }

    #[test]
    fn returns_original_when_utf8_decode_fails() {
        // A lone 0x80 is not valid UTF-8 on its own.
        let broken: String = (0x80u8 as char).to_string();
        assert_eq!(fix(&broken), broken);
    }

    #[test]
    fn correctly_stored_text_survives() {
        // A properly-stored `café` is NOT latin-1-representable as mojibake,
        // and decode fails, so it comes back identical.
        assert_eq!(fix("café"), "café");
        assert!(!needs_fix("café"));
    }

    #[test]
    fn pure_ascii_is_unchanged() {
        assert_eq!(fix("plain ascii"), "plain ascii");
    }

    #[test]
    fn empty_string() {
        assert_eq!(fix(""), "");
    }
}
