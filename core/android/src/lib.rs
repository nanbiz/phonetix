//! The overlay's way into the core.
//!
//! A thin wrapper and nothing else, for the same reason as the browser's: logic that lived here
//! would exist for one platform only, which is the drift this arrangement exists to prevent.
//!
//! The boundary is crossed per batch, never per word. The overlay re-reads the accessibility
//! tree many times a second and each of those reads stays in Kotlin; the core is asked only
//! when the set of text on screen has changed, which is a few times per screen.

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

mod speech;

/// How many terms two glosses share.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_overlap(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    target: JString,
) -> jlong {
    let (Ok(source), Ok(target)) = (env.get_string(&source), env.get_string(&target)) else {
        return 0;
    };
    let source: String = source.into();
    let target: String = target.into();
    lexcore::gloss::overlap(&source, &target) as jlong
}

/// The candidate sharing most, or -1 when the best does not clearly beat the rest.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_bestOf(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    candidates: JObjectArray,
    margin: jint,
) -> jint {
    let Ok(source) = env.get_string(&source) else {
        return -1;
    };
    let source: String = source.into();
    let Ok(len) = env.get_array_length(&candidates) else {
        return -1;
    };
    let mut owned: Vec<String> = Vec::with_capacity(len as usize);
    for i in 0..len {
        let Ok(item) = env.get_object_array_element(&candidates, i) else {
            return -1;
        };
        let item = JString::from(item);
        let Ok(text) = env.get_string(&item) else {
            return -1;
        };
        owned.push(text.into());
    }
    let refs: Vec<&str> = owned.iter().map(|s| s.as_str()).collect();
    match lexcore::gloss::best_of(&source, &refs, margin.max(0) as usize) {
        Some(i) => i as jint,
        None => -1,
    }
}

/// The core, holding whatever packs the overlay has opened.
///
/// One instance, handed back to Kotlin as a pointer, because the alternative is a global that
/// two services in one process would share without meaning to. Kotlin owns its lifetime and
/// frees it explicitly.
struct Core {
    packs: std::collections::HashMap<String, lexpack::Pack<Vec<u8>>>,
    /// The language model, where the overlay has given it one.
    model: Option<lexcore::detect::Model>,
    /// The homograph classifiers, one per language, where the overlay has given them.
    classifiers: std::collections::HashMap<String, lexcore::homographs::Classifier>,
    /// The batches the overlay is still drawing, kept so what an engine answers joins the
    /// same tokens rather than a second set this side stitched together itself. The same
    /// arrangement as the browser's, so a word filled by an engine is marked the same way on
    /// both platforms.
    batches: std::collections::HashMap<
        u64,
        (
            Vec<lexcore::answer::Token>,
            lexcore::answer::AnnotateOptions,
        ),
    >,
    next_batch: u64,
}

/// The core a pointer names, locked for as long as the guard is held.
///
/// Every call below runs on whichever thread Kotlin makes it from, and they are not the same
/// thread: the screen is read and annotated on one, dictionaries arrive and are opened on
/// another. A pack inserted into the map while another call is walking it is undefined
/// behaviour - a crash when it is lucky - so the core is one thing at a time, and a caller that
/// panicked while holding it does not leave it unusable for every one after.
fn lock_core(core: jlong) -> std::sync::MutexGuard<'static, Core> {
    // Safety: the pointer came from `open` below, Kotlin passes it back unchanged, and it is
    // not used after `close`.
    let lock = unsafe { &*(core as *const std::sync::Mutex<Core>) };
    lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Make one. The pointer it returns is what every call below is given back.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_open(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let core = Box::new(std::sync::Mutex::new(Core {
        packs: std::collections::HashMap::new(),
        model: None,
        classifiers: std::collections::HashMap::new(),
        batches: std::collections::HashMap::new(),
        next_batch: 1,
    }));
    Box::into_raw(core) as jlong
}

/// Let it go. Nothing else here may be called with this pointer afterwards.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_close(
    _env: JNIEnv,
    _class: JClass,
    core: jlong,
) {
    if core == 0 {
        return;
    }
    // Safety: the pointer came from open above and Kotlin passes it back unchanged.
    unsafe { drop(Box::from_raw(core as *mut std::sync::Mutex<Core>)) };
}

/// Read a pack off the disk. Returns the language it is for, or an empty string when the file
/// is not a pack this reader knows.
///
/// The file rather than its bytes: on a phone the pack is a file the app owns, and handing
/// twenty megabytes through JNI to read it back out again is work with nothing to show.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_openPack<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    core: jlong,
    path: JString,
) -> jni::objects::JString<'a> {
    let empty = env.new_string("").unwrap_or_else(|_| {
        JString::from(unsafe { jni::objects::JObject::from_raw(std::ptr::null_mut()) })
    });
    if core == 0 {
        return empty;
    }
    let Ok(path) = env.get_string(&path) else {
        return empty;
    };
    let path: String = path.into();
    let Ok(bytes) = std::fs::read(&path) else {
        return empty;
    };
    let Ok(pack) = lexpack::Pack::open(bytes) else {
        return empty;
    };
    let lang = pack.lang().to_string();
    // Safety: as above.
    let mut guard = lock_core(core);
    let core = &mut *guard;
    core.packs.insert(lang.clone(), pack);
    env.new_string(&lang).unwrap_or(empty)
}

/// The words for something a reader typed in their own language, in the one they are learning,
/// out of the dictionaries alone, best first; empty where they say nothing. See
/// [lexcore::resolve::word_for].
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_wordFor<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    core: jlong,
    text: JString,
    typed_in: JString,
    wanted_in: JString,
) -> jni::objects::JObjectArray<'a> {
    let empty = |env: &mut JNIEnv<'a>| {
        env.new_object_array(0, "java/lang/String", jni::objects::JObject::null())
            .unwrap_or_else(|_| {
                jni::objects::JObjectArray::from(unsafe {
                    jni::objects::JObject::from_raw(std::ptr::null_mut())
                })
            })
    };
    if core == 0 {
        return empty(&mut env);
    }
    let (Ok(text), Ok(typed_in), Ok(wanted_in)) = (
        env.get_string(&text),
        env.get_string(&typed_in),
        env.get_string(&wanted_in),
    ) else {
        return empty(&mut env);
    };
    let (text, typed_in, wanted_in): (String, String, String) =
        (text.into(), typed_in.into(), wanted_in.into());
    let words = {
        let guard = lock_core(core);
        let core = &*guard;
        match core.packs.get(&wanted_in) {
            Some(wanted) => lexcore::resolve::word_for(
                &text,
                &lexcore::answer::Lang(typed_in.clone()),
                wanted,
                core.packs.get(&typed_in),
            ),
            None => Vec::new(),
        }
    };
    let Ok(out) = env.new_object_array(
        words.len() as i32,
        "java/lang/String",
        jni::objects::JObject::null(),
    ) else {
        return empty(&mut env);
    };
    for (at, word) in words.iter().enumerate() {
        if let Ok(value) = env.new_string(word) {
            let _ = env.set_object_array_element(&out, at as i32, value);
        }
    }
    out
}

/// One word, as JSON, because an Answer is a tree and the boundary carries text.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_lookUp<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    core: jlong,
    spelling: JString,
    source: JString,
    target: JString,
    accent: JString,
    // The word before it on the screen, which decides a spelling that is several words.
    before: JString,
    // What the screen drew over the word where it had decided which word it was: the card
    // opens on that reading rather than asking the question the screen already answered.
    drawn: JString,
) -> jni::objects::JString<'a> {
    let empty = env.new_string("").unwrap_or_else(|_| {
        JString::from(unsafe { jni::objects::JObject::from_raw(std::ptr::null_mut()) })
    });
    if core == 0 {
        return empty;
    }
    let (Ok(spelling), Ok(source), Ok(target)) = (
        env.get_string(&spelling),
        env.get_string(&source),
        env.get_string(&target),
    ) else {
        return empty;
    };
    let (spelling, source, target): (String, String, String) =
        (spelling.into(), source.into(), target.into());
    let accent: String = env
        .get_string(&accent)
        .map(|it| it.into())
        .unwrap_or_default();
    let before: String = env
        .get_string(&before)
        .map(|it| it.into())
        .unwrap_or_default();
    let drawn: String = env
        .get_string(&drawn)
        .map(|it| it.into())
        .unwrap_or_default();
    // Safety: as above.
    let guard = lock_core(core);
    let core = &*guard;
    let open = lexcore::resolve::Open {
        source: core.packs.get(&source),
        target: core.packs.get(&target),
        ipa_only: false,
        accent: &accent,
        accent_pack: core.packs.get(&accent),
        said: Some(drawn.as_str()).filter(|it| !it.is_empty()),
        classifier: None,
    };
    let answer = lexcore::resolve::read_in_context(
        &spelling,
        if before.is_empty() {
            None
        } else {
            Some(&before)
        },
        &lexcore::answer::Lang(source),
        &lexcore::answer::Lang(target),
        &open,
    );
    env.new_string(lexcore::json::of(&answer)).unwrap_or(empty)
}

/// One of the dictionaries the app ships, turned into a pack it can read.
///
/// The bytes in are the file as it ships, gzipped, read out of the app's own assets; the bytes
/// out are a pack, or nothing where the file is not one of ours. Carried rather than fetched:
/// the maps are a fraction of the size of the packs they become, so every language travels
/// with the app and the one being read becomes a pack the first time it is read.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_buildIpaPack<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    lang: JString,
    gzipped: jni::objects::JByteArray<'a>,
    built: jlong,
) -> jni::objects::JByteArray<'a> {
    let empty = env
        .new_byte_array(0)
        .unwrap_or_else(|_| unsafe { jni::objects::JByteArray::from_raw(std::ptr::null_mut()) });
    let Ok(lang) = env.get_string(&lang) else {
        return empty;
    };
    let lang: String = lang.into();
    let Ok(bytes) = env.convert_byte_array(&gzipped) else {
        return empty;
    };
    let Some(pack) = lexcore::packing::ipa_pack(&lang, &bytes, built.max(0) as u64) else {
        return empty;
    };
    let out = pack.iter().map(|b| *b as i8).collect::<Vec<i8>>();
    match env.new_byte_array(out.len() as i32) {
        Ok(array) => {
            if env.set_byte_array_region(&array, 0, &out).is_err() {
                return empty;
            }
            array
        }
        Err(_) => empty,
    }
}

/// Several words asked as one, as JSON.
///
/// No dictionary holds a phrase, so what answers it is the engine and the core is what marks
/// the answer as a machine's. The translation is passed in rather than fetched here: the
/// engine lives on the other side of this boundary already, and asking it twice for the same
/// sentence is a second pass over a model for nothing.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_phrase<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    text: JString,
    said: JString,
    source: JString,
    target: JString,
) -> jni::objects::JString<'a> {
    let empty = env.new_string("").unwrap_or_else(|_| {
        JString::from(unsafe { jni::objects::JObject::from_raw(std::ptr::null_mut()) })
    });
    let (Ok(text), Ok(source), Ok(target)) = (
        env.get_string(&text),
        env.get_string(&source),
        env.get_string(&target),
    ) else {
        return empty;
    };
    let (text, source, target): (String, String, String) =
        (text.into(), source.into(), target.into());
    let said: String = env
        .get_string(&said)
        .map(|it| it.into())
        .unwrap_or_default();
    let answer = lexcore::resolve::phrase(
        &text,
        &said,
        &lexcore::answer::Lang(source),
        &lexcore::answer::Lang(target),
    );
    env.new_string(lexcore::json::of(&answer)).unwrap_or(empty)
}

/// A transcription, symbol by symbol, as JSON.
///
/// The overlay's card offers every sound on its own, and what a sound is called is the core's
/// answer: a table on this side would be the same table twice, which is how one sound ends up
/// with two names.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_symbols<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    ipa: JString<'a>,
) -> jni::objects::JString<'a> {
    let empty = env.new_string("[]").expect("a string the vm can hold");
    let Ok(ipa) = env.get_string(&ipa) else {
        return empty;
    };
    let ipa: String = ipa.into();
    let written = lexcore::json::symbols_of(&lexcore::symbols::explain(&ipa));
    env.new_string(written).unwrap_or(empty)
}

/// A transcription as it is shown over a word, given what the reader asked for.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_display<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    ipa: JString<'a>,
    narrow: jni::sys::jboolean,
    hide_stress: jni::sys::jboolean,
) -> jni::objects::JString<'a> {
    let empty = env.new_string("").expect("a string the vm can hold");
    let Ok(text) = env.get_string(&ipa) else {
        return empty;
    };
    let text: String = text.into();
    let shown = lexcore::symbols::display(&text, narrow != 0, hide_stress != 0);
    env.new_string(shown).unwrap_or(empty)
}

/// Whether this occurrence of a word is one the inline layer draws.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_picks(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
    occurrence: jint,
    density: jint,
) -> jni::sys::jboolean {
    let Ok(word) = env.get_string(&word) else {
        return 0;
    };
    let word: String = word.into();
    let picked = lexcore::sprinkle::picks(
        &word.to_lowercase(),
        occurrence.max(0) as u32,
        density.max(1) as u32,
    );
    u8::from(picked)
}

/// What a position on the reader's frequency bar means, as one word in every N.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_densityForPos(
    _env: JNIEnv,
    _class: JClass,
    position: jni::sys::jfloat,
) -> jint {
    lexcore::sprinkle::density_for_pos(position as f64) as jint
}

/// Where on that bar a density sits.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_posForDensity(
    _env: JNIEnv,
    _class: JClass,
    density: jint,
) -> jni::sys::jfloat {
    lexcore::sprinkle::pos_for_density(density.max(1) as u32, 100) as jni::sys::jfloat
}

/// Annotate a screenful of text: one token per word, as JSON.
///
/// The whole screen at once rather than a node at a time, because which words are annotated
/// depends on how often each has already appeared: counting from zero per node would annotate
/// the same word every time it turned up.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_annotate<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    core: jlong,
    texts: JObjectArray<'a>,
    source: JString<'a>,
    target: JString<'a>,
    mode: JString<'a>,
    density: jint,
    narrow: jni::sys::jboolean,
    hide_stress: jni::sys::jboolean,
    accent: JString<'a>,
) -> jni::objects::JString<'a> {
    let empty = env
        .new_string("{\"batch\":0,\"tokens\":[],\"misses\":[]}")
        .expect("a string the vm can hold");
    let (Ok(source), Ok(target), Ok(mode)) = (
        env.get_string(&source),
        env.get_string(&target),
        env.get_string(&mode),
    ) else {
        return empty;
    };
    let (source, target, mode): (String, String, String) =
        (source.into(), target.into(), mode.into());
    let Ok(count) = env.get_array_length(&texts) else {
        return empty;
    };
    let mut runs = Vec::with_capacity(count as usize);
    for at in 0..count {
        let Ok(item) = env.get_object_array_element(&texts, at) else {
            return empty;
        };
        let item = JString::from(item);
        let Ok(text) = env.get_string(&item) else {
            return empty;
        };
        runs.push(lexcore::answer::TextRun {
            id: at as u32,
            text: text.into(),
            lang_hint: None,
        });
    }
    let mut guard = lock_core(core);
    let held = &mut *guard;
    let accent_pack = env
        .get_string(&accent)
        .ok()
        .map(|it| -> String { it.into() })
        .filter(|it| !it.is_empty());
    let accent_name = accent_pack.clone().unwrap_or_default();
    let open = lexcore::resolve::Open {
        source: held.packs.get(&source),
        target: held.packs.get(&target),
        ipa_only: false,
        accent: &accent_name,
        accent_pack: accent_pack.as_ref().and_then(|it| held.packs.get(it)),
        said: None,
        classifier: held.classifiers.get(&source),
    };
    let options = lexcore::answer::AnnotateOptions {
        mode: match mode.as_str() {
            "meaning" => lexcore::answer::InlineMode::Meaning,
            "sound" => lexcore::answer::InlineMode::Sound,
            "both" => lexcore::answer::InlineMode::Both,
            _ => lexcore::answer::InlineMode::Off,
        },
        density: density.max(1) as u32,
        narrow: narrow != 0,
        hide_stress: hide_stress != 0,
        accent: accent_pack.clone(),
        seen: Vec::new(),
    };
    let (tokens, misses) = lexcore::annotate::annotate(
        &runs,
        &lexcore::answer::Lang(source),
        &lexcore::answer::Lang(target),
        &open,
        &options,
    );
    // Kept so the overlay can hand back what its engines answered, under the lock already
    // held: taking it a second time here is this thread waiting on itself for ever.
    let id = held.next_batch;
    held.next_batch += 1;
    // One batch at a time is what a screen is; anything older is a screen that has gone.
    held.batches.clear();
    held.batches.insert(id, (tokens.clone(), options));
    let written = lexcore::json::batch(id, &tokens, &misses);
    env.new_string(written).unwrap_or(empty)
}

/// Fill in what the overlay's engines answered about the words the packs missed.
///
/// The answers go back through the core rather than being drawn beside its tokens, so one
/// answer still drives the page, the card and the audio, and what a machine produced is
/// marked as a machine's in the one place that decides what a reader is told.
/// # Safety
///
/// `tokens` is a jintArray the vm owns for the length of the call, which is what the vm
/// guarantees for every argument it passes across this boundary.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub unsafe extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_complete<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    core: jlong,
    batch: jlong,
    tokens: jni::sys::jintArray,
    glosses: JObjectArray<'a>,
    ipas: JObjectArray<'a>,
    sentences: JObjectArray<'a>,
    source: JString<'a>,
    target: JString<'a>,
    accent: JString<'a>,
    engine: JString<'a>,
) -> jni::objects::JString<'a> {
    let empty = env
        .new_string("{\"batch\":0,\"tokens\":[],\"misses\":[]}")
        .expect("a string the vm can hold");
    if core == 0 {
        return empty;
    }
    let engine: String = env
        .get_string(&engine)
        .map(|it| it.into())
        .unwrap_or_default();
    let text = |it: &JString<'a>, env: &mut JNIEnv<'a>| -> String {
        env.get_string(it).map(|got| got.into()).unwrap_or_default()
    };
    let source = text(&source, &mut env);
    let target = text(&target, &mut env);
    let accent = text(&accent, &mut env);
    // Safety: the array comes from the vm, which owns it for the length of this call. It is
    // borrowed rather than taken, so nothing here frees what the vm will free itself.
    let array = unsafe { jni::objects::JIntArray::from_raw(tokens) };
    let indexes = {
        let Ok(len) = env.get_array_length(&array) else {
            return empty;
        };
        let mut out = vec![0i32; len as usize];
        if env.get_int_array_region(&array, 0, &mut out).is_err() {
            return empty;
        }
        out
    };
    let strings = |array: &JObjectArray<'a>, env: &mut JNIEnv<'a>| -> Vec<String> {
        let Ok(len) = env.get_array_length(array) else {
            return Vec::new();
        };
        (0..len)
            .map(|at| {
                let Ok(item) = env.get_object_array_element(array, at) else {
                    return String::new();
                };
                let text = JString::from(item);
                env.get_string(&text)
                    .map(|it| -> String { it.into() })
                    .unwrap_or_default()
            })
            .collect()
    };
    let said = strings(&glosses, &mut env);
    let sounds = strings(&ipas, &mut env);
    let lines = strings(&sentences, &mut env);
    // Safety: as above.
    let mut guard = lock_core(core);
    let held = &mut *guard;
    let Some((drawn, options)) = held.batches.get_mut(&(batch as u64)) else {
        return empty;
    };
    let results: Vec<lexcore::answer::EngineResult> = indexes
        .iter()
        .enumerate()
        .map(|(at, token)| lexcore::answer::EngineResult {
            token_index: (*token).max(0) as u32,
            gloss: said.get(at).filter(|it| !it.is_empty()).cloned(),
            ipa: sounds.get(at).filter(|it| !it.is_empty()).cloned(),
            sentence: lines.get(at).filter(|it| !it.is_empty()).cloned(),
            engine: engine.clone(),
        })
        .collect();
    // The same packs the batch was drawn with, so a word read again because its sentence has
    // been translated is read by the cascade rather than patched here.
    let open = lexcore::resolve::Open {
        source: held.packs.get(&source),
        target: held.packs.get(&target),
        ipa_only: false,
        accent: &accent,
        accent_pack: held.packs.get(&accent),
        said: None,
        classifier: held.classifiers.get(&source),
    };
    lexcore::annotate::complete(
        drawn,
        &results,
        options,
        &lexcore::answer::Lang(target.clone()),
        &open,
    );
    let written = lexcore::json::batch(batch as u64, drawn, &[]);
    env.new_string(written).unwrap_or(empty)
}

/// Read the language model off the disk into a core. Returns how many languages it knows,
/// or 0 when the file is not a model.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_openModel(
    mut env: JNIEnv,
    _class: JClass,
    core: jlong,
    path: JString,
) -> jint {
    let Ok(path) = env.get_string(&path) else {
        return 0;
    };
    let path: String = path.into();
    let Ok(bytes) = std::fs::read(path) else {
        return 0;
    };
    let Ok(model) = lexcore::detect::Model::open(&bytes) else {
        return 0;
    };
    let mut guard = lock_core(core);
    let held = &mut *guard;
    let languages = model.languages().len() as jint;
    held.model = Some(model);
    languages
}

/// What language a piece of text is in, as JSON.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_detect<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    core: jlong,
    text: JString<'a>,
) -> jni::objects::JString<'a> {
    let nothing = lexcore::json::guess(&lexcore::detect::Guess {
        language: None,
        reliable: false,
        scores: Vec::new(),
    });
    let empty = env.new_string(&nothing).expect("a string the vm can hold");
    let Ok(text) = env.get_string(&text) else {
        return empty;
    };
    let text: String = text.into();
    let mut guard = lock_core(core);
    let held = &mut *guard;
    let written = match &held.model {
        Some(model) => lexcore::json::guess(&model.detect(&text)),
        None => nothing,
    };
    env.new_string(written).unwrap_or(empty)
}

/// What a screenful of text is in, as JSON.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_readScreen<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    core: jlong,
    text: JString<'a>,
) -> jni::objects::JString<'a> {
    let nothing = lexcore::json::screen(&lexcore::detect::Screen {
        language: None,
        words: 0,
        enough: false,
    });
    let empty = env.new_string(&nothing).expect("a string the vm can hold");
    let Ok(text) = env.get_string(&text) else {
        return empty;
    };
    let text: String = text.into();
    let mut guard = lock_core(core);
    let held = &mut *guard;
    let read = lexcore::detect::read_screen(held.model.as_ref(), &text);
    env.new_string(lexcore::json::screen(&read))
        .unwrap_or(empty)
}

/// Give up a pack, so a dictionary the reader deleted stops answering.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_closePack(
    mut env: JNIEnv,
    _class: JClass,
    core: jlong,
    lang: JString,
) {
    let Ok(lang) = env.get_string(&lang) else {
        return;
    };
    let lang: String = lang.into();
    let mut guard = lock_core(core);
    let held = &mut *guard;
    held.packs.remove(&lang);
}

/// What a Wiktionary page says about a word in one language, as JSON.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_readWiktionary<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    wikitext: JString<'a>,
    lang: JString<'a>,
) -> jni::objects::JString<'a> {
    let empty = env.new_string("null").expect("a string the vm can hold");
    let (Ok(wikitext), Ok(lang)) = (env.get_string(&wikitext), env.get_string(&lang)) else {
        return empty;
    };
    let (wikitext, lang): (String, String) = (wikitext.into(), lang.into());
    match lexcore::wiktionary::parse(&wikitext, Some(&lang)) {
        Some(said) => env.new_string(lexcore::json::said(&said)).unwrap_or(empty),
        None => empty,
    }
}

/// Start the synthesiser against the data unpacked from the apk.
///
/// Said once. What it answers is whether the words no pack holds can be transcribed at all;
/// a phone where it does not start reads exactly as it did before there was one.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_speechStart(
    mut env: JNIEnv,
    _class: JClass,
    data: JString,
) -> jint {
    let Ok(path) = env.get_string(&data) else {
        return 0;
    };
    let path: String = path.into();
    i32::from(speech::start(&path))
}

/// How a batch of words is said, in one voice.
///
/// A batch, because the overlay asks about a screen at a time and a call per word would cross
/// the boundary a hundred times for one page.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_speechPhonemes<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    voice: JString<'a>,
    words: JObjectArray<'a>,
) -> JObjectArray<'a> {
    let empty = env
        .new_object_array(0, "java/lang/String", jni::objects::JObject::null())
        .expect("an array the vm can hold");
    let Ok(voice) = env.get_string(&voice) else {
        return empty;
    };
    let voice: String = voice.into();
    let Ok(count) = env.get_array_length(&words) else {
        return empty;
    };
    let mut asked: Vec<String> = Vec::with_capacity(count as usize);
    for at in 0..count {
        let Ok(item) = env.get_object_array_element(&words, at) else {
            asked.push(String::new());
            continue;
        };
        let text: String = env
            .get_string(&JString::from(item))
            .map(|it| it.into())
            .unwrap_or_default();
        asked.push(text);
    }
    let said = speech::phonemes(&voice, &asked);
    let Ok(out) = env.new_object_array(
        said.len() as jint,
        "java/lang/String",
        jni::objects::JObject::null(),
    ) else {
        return empty;
    };
    for (at, text) in said.iter().enumerate() {
        if let Ok(value) = env.new_string(text) {
            let _ = env.set_object_array_element(&out, at as jint, value);
        }
    }
    out
}

/// One word spoken, as the bytes of a WAV file.
///
/// Bytes rather than a sound: what plays a sound is the phone's own audio, and the browser
/// takes the same bytes from the same engine, so the word is said by one voice everywhere.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_speechSay<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    voice: JString<'a>,
    word: JString<'a>,
) -> jni::sys::jbyteArray {
    let empty = env
        .new_byte_array(0)
        .map(|it| it.into_raw())
        .unwrap_or(std::ptr::null_mut());
    let (Ok(voice), Ok(word)) = (env.get_string(&voice), env.get_string(&word)) else {
        return empty;
    };
    let (voice, word): (String, String) = (voice.into(), word.into());
    let wav = speech::say(&voice, &word);
    let bytes: Vec<i8> = wav.into_iter().map(|b| b as i8).collect();
    let Ok(array) = env.new_byte_array(bytes.len() as jint) else {
        return empty;
    };
    if env.set_byte_array_region(&array, 0, &bytes).is_err() {
        return empty;
    }
    array.into_raw()
}

/// Read a language's homograph classifier off the disk. Returns how many spellings it knows.
///
/// A trained decision list for the words a language writes the same and says differently.
/// Optional: without one a homograph is still decided by the word before it where that
/// decides, and asked about where it does not.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_openHomographs(
    mut env: JNIEnv,
    _class: JClass,
    core: jlong,
    lang: JString,
    path: JString,
) -> jint {
    if core == 0 {
        return 0;
    }
    let (Ok(lang), Ok(path)) = (env.get_string(&lang), env.get_string(&path)) else {
        return 0;
    };
    let (lang, path): (String, String) = (lang.into(), path.into());
    let Ok(bytes) = std::fs::read(&path) else {
        return 0;
    };
    let Ok(it) = lexcore::homographs::Classifier::open(&bytes) else {
        return 0;
    };
    let known = it.len() as jint;
    // Safety: the pointer came from open above and Kotlin passes it back unchanged.
    let mut guard = lock_core(core);
    let core = &mut *guard;
    core.classifiers.insert(lang, it);
    known
}

#[cfg(test)]
mod tests {
    /// No call takes the core's lock twice.
    ///
    /// The lock is not re-entrant: a call that takes it and then asks for it again waits on
    /// itself for ever, and the overlay stops reading the screen with nothing anywhere saying
    /// why. Every call here takes it once and does all its work under that one guard.
    #[test]
    fn no_call_takes_the_core_twice() {
        let whole = include_str!("lib.rs");
        // The calls only, not this test, whose own text names the lock.
        let source = whole.split("#[cfg(test)]").next().unwrap_or(whole);
        let calls = source.split("extern \"system\" fn ").skip(1);
        for call in calls {
            let name = call.split('(').next().unwrap_or("?");
            let body = call.split("\nextern \"system\" fn ").next().unwrap_or(call);
            let taken = body.matches("lock_core(core)").count();
            assert!(taken <= 1, "{name} takes the core's lock {taken} times");
        }
    }
}
