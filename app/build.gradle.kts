plugins {
    alias(libs.plugins.android.application)
    // AGP 9's built-in Kotlin support means org.jetbrains.kotlin.android must NOT be applied
    // separately -- doing so registers a duplicate "kotlin" extension and fails the build
    // ("Cannot add extension with name 'kotlin', as there is an extension already registered
    // with that name"), found in practice while setting this project up.
    id("com.chaquo.python")
}

android {
    namespace = "com.example.mysailinglogbook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mysailinglogbook"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a only for this spike -- matches the real Samsung S23 test device, no need to
        // also build for x86_64 emulator ABIs right now.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

chaquopy {
    defaultConfig {
        // Matches the build-time Python already installed on this machine (3.14.3) -- Chaquopy
        // needs a local interpreter of this version to run its own build tooling, separate from
        // the one it bundles into the app at runtime.
        version = "3.14"
        // No pip block: nmea2000processor has zero third-party runtime dependencies (see
        // pyproject.toml in the main repo), so nothing to install here.
    }
    // Points straight at the real nmea2log repo's src/ directory (which contains the
    // nmea2000processor package) rather than copying the code into this Android project -- one
    // source of truth for both the desktop CLI and the app, per docs/android-app-plan.md.
    sourceSets {
        getByName("main") {
            srcDir("C:/Users/ruijs/OneDrive/Ayuus/Github/NMEA/src")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Spike 4 (docs/android-app-plan.md): SFTP replacement for upload.py's subprocess.run(["sftp",
    // ...]), which has nothing to shell out to on Android. Not jsch (unmaintained, no Ed25519) --
    // the real upload key here is Ed25519.
    implementation("com.hierynomus:sshj:0.40.0")
    // Registered as a Security provider at startup (see MainActivity) -- without it, sshj fails
    // to authenticate with an Ed25519 key ("no such algorithm: X25519 for provider BC", found in
    // practice), since Android's built-in crypto providers don't consistently support it.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // EncryptedSharedPreferences for SettingsStore -- W2K-2 credentials and boat identity,
    // replacing nmea2log.ini on Android (see docs/android-app-plan.md).
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}