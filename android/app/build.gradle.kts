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
 * The behaviour vectors generated from the core's own sprinkle rule. They are read by
 * SprinkleParityTest, which is the thing that keeps this port and the extension agreeing
 * about which words get transcribed.
 */
val bundleVectors by tasks.registering(Copy::class) {
    val vectors = rootProject.file("../shared/sprinkle-vectors.json")
    doFirst {
        require(vectors.exists()) {
            "Missing ${vectors.path}. Run `pnpm gen:vectors` in the repository root."
        }
    }
    from(vectors)
    from(rootProject.file("../shared/ipa-symbols.json"))
    into(layout.buildDirectory.dir("vectors"))
}

tasks.withType<Test>().configureEach {
    dependsOn(bundleVectors)
    systemProperty("phonetix.vectors", layout.buildDirectory.file("vectors/sprinkle-vectors.json").get().asFile.path)
    systemProperty("phonetix.symbols", layout.buildDirectory.file("vectors/ipa-symbols.json").get().asFile.path)
}

val bundleDictionaries by tasks.registering(Copy::class) {
    val dict = rootProject.file("../assets/dictionaries/en.json.gz")
    val common = rootProject.file("../assets/common-words.json")
    doFirst {
        require(dict.exists()) {
            "Missing ${dict.path}. Run `pnpm fetch:dict` in the repository root first."
        }
    }
    from(dict)
    from(common)
    from(rootProject.file("../shared/ipa-symbols.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
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
    // A machine without the Rust toolchain still builds the app; the service checks whether
    // the library loaded and degrades rather than crashing over somebody else's screen.
    isIgnoreExitValue = true
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

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
