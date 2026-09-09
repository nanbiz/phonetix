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
}

/// Make one. The pointer it returns is what every call below is given back.
#[no_mangle]
pub extern "system" fn Java_io_github_nanbiz_phonetix_core_Lex_open(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let core = Box::new(Core {
        packs: std::collections::HashMap::new(),
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
    // Safety: as above.
    let core = unsafe { &*(core as *const Core) };
    let open = lexcore::resolve::Open {
        source: core.packs.get(&source),
        target: core.packs.get(&target),
        ipa_only: false,
    };
    let answer = lexcore::resolve::look_up(
        &spelling,
        &lexcore::answer::Lang(source),
        &lexcore::answer::Lang(target),
        &open,
    );
    env.new_string(lexcore::json::of(&answer)).unwrap_or(empty)
}
