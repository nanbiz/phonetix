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
}

/// Make one. The pointer it returns is what every call below is given back.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_open(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let core = Box::new(Core {
        packs: std::collections::HashMap::new(),
        model: None,
    });
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
    unsafe { drop(Box::from_raw(core as *mut Core)) };
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
    let core = unsafe { &mut *(core as *mut Core) };
    core.packs.insert(lang.clone(), pack);
    env.new_string(&lang).unwrap_or(empty)
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
    // Safety: as above.
    let core = unsafe { &*(core as *const Core) };
    let open = lexcore::resolve::Open {
        source: core.packs.get(&source),
        target: core.packs.get(&target),
        ipa_only: false,
        accent: &accent,
        accent_pack: core.packs.get(&accent),
    };
    let answer = lexcore::resolve::look_up(
        &spelling,
        &lexcore::answer::Lang(source),
        &lexcore::answer::Lang(target),
        &open,
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
    let held = unsafe { &mut *(core as *mut Core) };
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
    };
    let options = lexcore::answer::AnnotateOptions {
        mode: match mode.as_str() {
            "gloss" => lexcore::answer::InlineMode::Gloss,
            "gloss+ipa" => lexcore::answer::InlineMode::GlossIpa,
            "ipa" => lexcore::answer::InlineMode::Ipa,
            "replace" => lexcore::answer::InlineMode::Replace,
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
    let written = lexcore::json::batch(0, &tokens, &misses);
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
    let held = unsafe { &mut *(core as *mut Core) };
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
    let held = unsafe { &mut *(core as *mut Core) };
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
    let held = unsafe { &mut *(core as *mut Core) };
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
    let held = unsafe { &mut *(core as *mut Core) };
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
