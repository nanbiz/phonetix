// Where the synthesiser is, for the linker.
//
// espeak-ng is a native library built per ABI by scripts/build-espeak-android.sh and packaged
// into the apk beside this crate's own. The linker needs to see it at build time; the phone
// finds it at run time because it sits in the same jniLibs directory.
use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if os != "android" {
        // Built for the host by a check or an editor. There is no synthesiser to link and
        // nothing that needs one: the JNI entry points are only reachable from a phone.
        return;
    }
    // The names Android uses for its ABIs, which are not the names Rust uses for its targets.
    let abi = match arch.as_str() {
        "aarch64" => "arm64-v8a",
        "x86_64" => "x86_64",
        "arm" => "armeabi-v7a",
        "x86" => "x86",
        other => other,
    };
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../android/app/src/main/jniLibs")
        .join(abi);
    println!("cargo:rustc-link-search=native={}", root.display());
    println!("cargo:rustc-link-lib=dylib=espeak-ng");
}
