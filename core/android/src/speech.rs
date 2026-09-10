//! The synthesiser, as the phone reaches it.
//!
//! The browser has run espeak-ng since before the merge: a word no pack holds still gets a
//! transcription there, because the engine can read any word from its own rules. The phone had
//! nothing, so the same word came back bare, and its audio was whatever voice the system
//! happened to have rather than the one that says the word everywhere else.
//!
//! This is the same engine, built native. What it is asked is exactly what the browser asks:
//! a batch of words, one voice, transcriptions out. Nothing here decides which words to ask
//! about - the core says what it is missing and the service passes that on.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::Mutex;

// The parts of espeak-ng's C interface this uses, and no more.
#[allow(non_camel_case_types)]
type espeak_ERROR = c_int;

/// Samples as the engine produces them, gathered by the callback below.
static SAID: Mutex<Vec<i16>> = Mutex::new(Vec::new());

/// What espeak calls with each block of samples. Returning 0 means carry on.
///
/// The engine hands out signed 16-bit samples at the rate it reported when it started; the
/// caller wraps them in a header. Nothing is played here: what the phone does with a sound is
/// the phone's business, and an engine that opened an audio device inside an accessibility
/// service would be an engine holding one open for the life of the service.
extern "C" fn gather(samples: *mut i16, count: c_int, _events: *mut c_void) -> c_int {
    if samples.is_null() || count <= 0 {
        return 0;
    }
    // Safety: espeak promises `count` samples at this pointer for the duration of the call.
    let block = unsafe { std::slice::from_raw_parts(samples, count as usize) };
    if let Ok(mut held) = SAID.lock() {
        held.extend_from_slice(block);
    }
    0
}

extern "C" {
    fn espeak_SetSynthCallback(callback: extern "C" fn(*mut i16, c_int, *mut c_void) -> c_int);
    fn espeak_Synth(
        text: *const c_void,
        size: usize,
        position: u32,
        position_type: c_int,
        end_position: u32,
        flags: u32,
        unique_identifier: *mut u32,
        user_data: *mut c_void,
    ) -> espeak_ERROR;
    fn espeak_Synchronize() -> espeak_ERROR;
    /// Start the engine against a data directory. Returns the sample rate, or -1.
    fn espeak_Initialize(
        output: c_int,
        buflength: c_int,
        path: *const c_char,
        options: c_int,
    ) -> c_int;
    fn espeak_SetVoiceByName(name: *const c_char) -> espeak_ERROR;
    /// One phrase, as phonemes. The pointer walks the text as it consumes it.
    fn espeak_TextToPhonemes(
        textptr: *mut *const c_void,
        textmode: c_int,
        phonememode: c_int,
    ) -> *const c_char;
}

/// Output straight to the caller rather than to an audio device: this asks for transcriptions.
const AUDIO_OUTPUT_SYNCHRONOUS: c_int = 0x02;
/// The text is UTF-8.
const ESPEAKNG_ENCODING_UTF_8: c_int = 1;
/// Phonemes as IPA, with no separator between them.
const PHONEMES_IPA: c_int = 0x02;

/// The engine is one process-wide thing with its own global state, so one caller at a time.
static ENGINE: Mutex<bool> = Mutex::new(false);

/// What the engine said its sample rate was, which the header has to carry.
static RATE: Mutex<i32> = Mutex::new(22050);

/// Start the engine once, against the data unpacked from the apk.
///
/// Returns whether it is usable. A phone with no data directory is not an error to report to a
/// reader: it means the words a pack does not hold stay bare, exactly as before.
pub fn start(data: &str) -> bool {
    let mut started = match ENGINE.lock() {
        Ok(it) => it,
        Err(poisoned) => poisoned.into_inner(),
    };
    if *started {
        return true;
    }
    let Ok(path) = CString::new(data) else {
        return false;
    };
    // Safety: the path outlives the call, and espeak copies what it needs.
    let rate = unsafe { espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.as_ptr(), 0) };
    if rate > 0 {
        if let Ok(mut held) = RATE.lock() {
            *held = rate;
        }
        // Safety: the callback is a plain function with C linkage and outlives the engine.
        unsafe { espeak_SetSynthCallback(gather) };
    }
    *started = rate > 0;
    *started
}

/// How these words are said, in one voice, as the engine reads them.
///
/// A word at a time rather than one joined text: what comes back has to line up with the words
/// that went in, and a sentence handed to a synthesiser comes back as one string nobody can
/// cut apart again at the boundaries it went in on.
pub fn phonemes(voice: &str, words: &[String]) -> Vec<String> {
    let guard = match ENGINE.lock() {
        Ok(it) => it,
        Err(poisoned) => poisoned.into_inner(),
    };
    if !*guard {
        return words.iter().map(|_| String::new()).collect();
    }
    if let Ok(name) = CString::new(voice) {
        // A voice it does not have is not an error worth failing over: it keeps the last one,
        // and what comes back is still a transcription of the word that was asked about.
        unsafe { espeak_SetVoiceByName(name.as_ptr()) };
    }
    words
        .iter()
        .map(|word| {
            let Ok(text) = CString::new(word.as_str()) else {
                return String::new();
            };
            let mut cursor = text.as_ptr() as *const c_void;
            let mut out = String::new();
            // The engine consumes the text a clause at a time and moves the pointer on, so a
            // word that is more than one clause comes back whole rather than truncated.
            while !cursor.is_null() {
                // Safety: cursor points into `text`, which outlives the loop.
                let said = unsafe {
                    espeak_TextToPhonemes(&mut cursor, ESPEAKNG_ENCODING_UTF_8, PHONEMES_IPA)
                };
                if said.is_null() {
                    break;
                }
                // Safety: espeak returns a NUL-terminated buffer it owns.
                out.push_str(&unsafe { CStr::from_ptr(said) }.to_string_lossy());
            }
            // espeak marks stress and separators its own way; what a reader is shown is the
            // core's display transform, which is applied above this.
            out.replace('_', "").trim().to_string()
        })
        .collect()
}

/// One word spoken, as the bytes of a WAV file.
///
/// Bytes rather than a sound played here: what plays a sound on a phone is the phone's own
/// audio, and the same word is said by the same voice in the browser, which also takes bytes.
pub fn say(voice: &str, word: &str) -> Vec<u8> {
    let guard = match ENGINE.lock() {
        Ok(it) => it,
        Err(poisoned) => poisoned.into_inner(),
    };
    if !*guard || word.is_empty() {
        return Vec::new();
    }
    if let Ok(name) = CString::new(voice) {
        unsafe { espeak_SetVoiceByName(name.as_ptr()) };
    }
    let Ok(text) = CString::new(word) else {
        return Vec::new();
    };
    if let Ok(mut held) = SAID.lock() {
        held.clear();
    }
    // Safety: the text outlives the call, and the engine is synchronised before it returns.
    unsafe {
        espeak_Synth(
            text.as_ptr() as *const c_void,
            word.len() + 1,
            0,
            1, // positions are counted in characters
            0,
            ESPEAKNG_ENCODING_UTF_8 as u32,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        );
        espeak_Synchronize();
    }
    let samples = match SAID.lock() {
        Ok(mut held) => std::mem::take(&mut *held),
        Err(poisoned) => std::mem::take(&mut *poisoned.into_inner()),
    };
    if samples.is_empty() {
        return Vec::new();
    }
    let rate = RATE.lock().map(|it| *it).unwrap_or(22050).max(8000) as u32;
    wav(&samples, rate)
}

/// A WAV header around the samples, so what crosses the boundary is a file and not a contract.
fn wav(samples: &[i16], rate: u32) -> Vec<u8> {
    let bytes = samples.len() * 2;
    let mut out = Vec::with_capacity(44 + bytes);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&((36 + bytes) as u32).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes()); // the size of this block
    out.extend_from_slice(&1u16.to_le_bytes()); // uncompressed
    out.extend_from_slice(&1u16.to_le_bytes()); // one channel
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * 2).to_le_bytes()); // bytes per second
    out.extend_from_slice(&2u16.to_le_bytes()); // bytes per frame
    out.extend_from_slice(&16u16.to_le_bytes()); // bits per sample
    out.extend_from_slice(b"data");
    out.extend_from_slice(&(bytes as u32).to_le_bytes());
    for sample in samples {
        out.extend_from_slice(&sample.to_le_bytes());
    }
    out
}
