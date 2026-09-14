plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.nanbiz.phonetix"
    compileSdk = 36
    // The NDK this repository declares in Nix, named rather than left to the plugin's default:
    // the store path is read-only, so a plugin that wants a different version cannot install
    // one and stops the build instead.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.nanbiz.phonetix"
        // 26 is the floor for both halves of the feature: TYPE_APPLICATION_OVERLAY
        // windows and per-character text bounds from an accessibility node.
        // Twenty-eight because the translation engine links against iconv and glob, which
        // Bionic gained there. Below it the engine does not link at all, and an app that
        // cannot translate is not the product this is.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The core is a native library, so the only honest place to test it is a device:
        // a JVM unit test would load the host build and prove nothing about the phone.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The dictionary is already gzipped; compressing it again in the APK only
        // costs build time and gains nothing.
        noCompress += "gz"
    }

    // The translation engine's own way in. The engine itself is prebuilt per ABI by
    // scripts/build-bergamot-android.sh; what is compiled here is the few lines that hand a
    // batch of words to it, which is the only part that changes.
    // The two the engine is built for, which are the two this repository's own native library
    // is built for: a phone and the emulator. Nothing is gained by shipping a 32-bit build of
    // a translation engine to a phone that cannot hold the model anyway.
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // No version pinned: naming one sends the plugin to the SDK to install it, and
            // the SDK here is a read-only store path. local.properties says where cmake is.
        }
    }
}

/**
 * The dictionary and the common-word list are the extension's own data, fetched into
 * assets/dictionaries by `pnpm fetch:dict` rather than committed. They are copied in at
 * build time so the app has exactly one source for them and the repository keeps one copy.
 */
/**
 * How words are said, in every language the product knows.
 *
 * The maps travel as they ship, gzipped, and the app builds a pack from the one it needs the
 * first time it reads a screen in that language. It used to build one pack here, for English,
 * at compile time - which left a reader of anything else with nothing to answer them and a
 * dictionary host to go and find.
 */
val bundleDictionaries by tasks.registering(Copy::class) {
    val from = rootProject.file("../assets/dictionaries")
    doFirst {
        require(from.exists()) {
            "Missing ${from.path}. Run `pnpm fetch:dict` in the repository root first."
        }
    }
    from(from) { include("*.json.gz") }
    into(layout.projectDirectory.dir("src/main/assets/dictionaries"))
}

/**
 * The settings screen, which is the extension's settings screen.
 *
 * One view for the whole product: the rows, the words and the palettes live in src/ui and are
 * built into the app's assets here, so a row added to the popup is a row on the phone. What
 * the app answers it with is in SettingsWeb.kt.
 */
val bundleSettingsUi by tasks.registering(Exec::class) {
    val root = rootProject.file("..")
    workingDir = root
    commandLine("node", "node_modules/vite/bin/vite.js", "build", "-c", "vite.phone.config.ts")
    inputs.dir(File(root, "src/ui"))
    inputs.dir(File(root, "src/phone"))
    inputs.file(File(root, "vite.phone.config.ts"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/ui"))
}

/**
 * The synthesiser, for the words no pack holds.
 *
 * Built from source per ABI, like the core beside it, rather than committed. The script is
 * what knows how: this only makes sure the build has run before the apk is packaged, and does
 * nothing when it already has.
 */
val bundleSpeech by tasks.registering(Exec::class) {
    val marker = layout.projectDirectory.file("src/main/assets/espeak/phontab").asFile
    onlyIf { !marker.exists() }
    workingDir = rootProject.file("..")
    commandLine("scripts/build-espeak-android.sh")
}

/**
 * The homograph classifiers, which decide which word a spelling is.
 *
 * A hundred kilobytes for every language together, so they are carried rather than fetched: a
 * reader should not have to download something to be told which word they are looking at.
 */
val bundleHomographs by tasks.registering(Copy::class) {
    val from = rootProject.file("../assets/homographs")
    doFirst {
        require(from.exists()) {
            "Missing ${from.path}. Run `uv run python tools/build_homographs.py` first."
        }
    }
    from(from) { include("*.hg") }
    into(layout.projectDirectory.dir("src/main/assets/homographs"))
}

/** The language model, which is a build input like the dictionary beside it. */
val bundleModel by tasks.registering(Copy::class) {
    val model = rootProject.file("../assets/eld.bin.gz")
    doFirst { require(model.exists()) { "Missing ${model.path}" } }
    from(model)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(bundleDictionaries, bundleModel, bundleSpeech, bundleHomographs, bundleSettingsUi)
}

// The native core, built from ../../core rather than committed.
//
// A checked-in .so is a build artefact pretending to be source, and it drifts the moment the
// crate changes. This builds it for whichever ABIs the variant wants and puts it where the
// packager looks, so an app can never ship a core older than the crate it came from.
val cargoNdk by tasks.registering(Exec::class) {
    val core = rootProject.file("../core")
    val ndk = "${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")}/ndk/27.0.12077973"
    workingDir = core
    environment("ANDROID_NDK_HOME", ndk)
    commandLine(
        "cargo", "ndk",
        "-t", "x86_64", "-t", "arm64-v8a",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release", "-p", "lexcore-android",
    )
}

/**
 * The app does not build without the core.
 *
 * Everything about what a word means, how it is said and which words are annotated is decided
 * in it, so an APK without the library is an app that cannot do its job. The build says so
 * here rather than the app finding out on somebody's screen.
 */
val coreIsThere by tasks.registering {
    dependsOn(cargoNdk)
    val abis = listOf("x86_64", "arm64-v8a")
    val libs = file("src/main/jniLibs")
    doLast {
        val missing = abis.filterNot { libs.resolve("$it/liblexcore_android.so").exists() }
        require(missing.isEmpty()) {
            "The core did not build for $missing. Install the Rust toolchain and cargo-ndk, " +
                "or run `cargo ndk -t x86_64 -t arm64-v8a build --release -p lexcore-android` " +
                "in ../core."
        }
    }
}

tasks.named("preBuild") { dependsOn(coreIsThere) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // The icons, from the set rather than drawn here. A play triangle and a microphone cut by
    // hand in a canvas are shapes this repository then has to keep legible at every size, and
    // it did not: the microphone read as a lollipop until somebody looked at it closely.
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    // The list most apps are built from, for the test pages only: what a scroll event says
    // about how far it moved differs by container, and the answer for this one decides
    // whether the overlay can follow an ordinary app's list.
    debugImplementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
