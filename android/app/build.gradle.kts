plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.nanbiz.phonetix"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nanbiz.phonetix"
        // 26 is the floor for both halves of the feature: TYPE_APPLICATION_OVERLAY
        // windows and per-character text bounds from an accessibility node.
        minSdk = 26
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
}

/**
 * The dictionary and the common-word list are the extension's own data, fetched into
 * assets/dictionaries by `pnpm fetch:dict` rather than committed. They are copied in at
 * build time so the app has exactly one source for them and the repository keeps one copy.
 */
/**
 * How English words are said, as a pack.
 *
 * The app used to carry the extension's dictionary as a compressed map it parsed itself,
 * which was a second format only this platform could read. It is a pack now: the same reader
 * the meanings come through, built from the same table.
 */
val bundleDictionaries by tasks.registering(Exec::class) {
    val dict = rootProject.file("../assets/dictionaries/en.json.gz")
    val out = layout.projectDirectory.file("src/main/assets/ipa-en.pack").asFile
    doFirst {
        require(dict.exists()) {
            "Missing ${dict.path}. Run `pnpm fetch:dict` in the repository root first."
        }
        out.parentFile.mkdirs()
    }
    workingDir = rootProject.file("../core")
    commandLine("cargo", "run", "-q", "--release", "-p", "packbuild", "--",
        "ipa", "en", dict.absolutePath, out.absolutePath)
}

tasks.named("preBuild") { dependsOn(bundleDictionaries) }

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
