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
    }

    androidResources {
        // The dictionary is already gzipped; compressing it again in the APK only
        // costs build time and gains nothing.
        noCompress += "gz"
    }
}

/**
 * The dictionary and the common-word list are the extension's own data, fetched into
 * public/dictionaries by `pnpm fetch:dict` rather than committed. They are copied in at
 * build time so the app has exactly one source for them and the repository keeps one copy.
 */
val bundleDictionaries by tasks.registering(Copy::class) {
    val dict = rootProject.file("../public/dictionaries/en.json.gz")
    val common = rootProject.file("../public/common-words.json")
    doFirst {
        require(dict.exists()) {
            "Missing ${dict.path}. Run `pnpm fetch:dict` in the repository root first."
        }
    }
    from(dict)
    from(common)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(bundleDictionaries) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
