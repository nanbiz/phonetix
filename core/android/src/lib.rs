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
    let Ok(source) = env.get_string(&source) else { return -1 };
    let source: String = source.into();
    let Ok(len) = env.get_array_length(&candidates) else { return -1 };
    let mut owned: Vec<String> = Vec::with_capacity(len as usize);
    for i in 0..len {
        let Ok(item) = env.get_object_array_element(&candidates, i) else { return -1 };
        let item = JString::from(item);
        let Ok(text) = env.get_string(&item) else { return -1 };
        owned.push(text.into());
    }
    let refs: Vec<&str> = owned.iter().map(|s| s.as_str()).collect();
    match lexcore::gloss::best_of(&source, &refs, margin.max(0) as usize) {
        Some(i) => i as jint,
        None => -1,
    }
}
