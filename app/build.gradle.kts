plugins {
    alias(libs.plugins.android.application)
    // AGP 9's built-in Kotlin support means org.jetbrains.kotlin.android must NOT be applied
    // separately -- doing so registers a duplicate "kotlin" extension and fails the build
    // ("Cannot add extension with name 'kotlin', as there is an extension already registered
    // with that name"), found in practice while setting this project up.
    id("com.chaquo.python")
}

import java.util.Properties

// Loaded up front (not just where nmea2log.src.dir is read further down) so signingConfigs
// below can also read the release keystore properties from the same, git-ignored file --
// machine-specific/secret values, same reasoning as nmea2log.src.dir's own comment there.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.ayuus.mysailinglogbook"
    compileSdk {
        version = release(37)
    }

    // Only configured when local.properties actually has a release keystore -- absent (a fresh
    // checkout that hasn't generated one yet, or CI without one), the release build type below
    // just falls back to being unsigned/debug-signed rather than failing the whole build over it;
    // Play Console upload (or even just installing a release APK locally) is the first place that
    // would actually need a real signature.
    val releaseStoreFile = localProperties.getProperty("release.storeFile")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }

    defaultConfig {
        // The real, user-visible device/Play-Store identity (what shows up under Android/data/<here>/...
        // on a PC over USB); the same as the namespace above.
        applicationId = "com.ayuus.mysailinglogbook"
        minSdk = 24
        targetSdk = 37
        versionCode = 9
        versionName = "1.1.5"

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
            // A no-op in practice, checked directly (the built AAB has no
            // BUNDLE-METADATA/com.android.tools.build.debugsymbols/ entry despite this): every
            // .so here (libpython/libchaquopy_java/...) is a pre-built third-party binary
            // Chaquopy bundles as-is, not something this Gradle module compiles itself, so AGP
            // has no unstripped intermediate output of its own to attach symbols from -- Play
            // Console's "no debug symbols uploaded" warning is expected and not fixable from this
            // project (Chaquopy does not publish a separate symbol archive for these either).
            // Left in, harmless, for the day this module ever gains native code of its own to
            // build (CMake/ndk-build), which this setting would then actually apply to.
            ndk {
                debugSymbolLevel = "FULL"
            }
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// nmea2log.src.dir points at the nmea2log repo's own src/ directory (the one containing the
// nmea2000processor package) -- read from local.properties (loaded once, up top, alongside the
// release-signing properties above), not hardcoded here, since this repo and nmea2log are two
// separate git repos that only make sense checked out side by side on whichever machine is
// building this app; a path baked into a *tracked* build file would only ever be correct for the
// one machine it was written on.
val nmea2logSrcDir = localProperties.getProperty("nmea2log.src.dir")
    ?: throw GradleException(
        "Set nmea2log.src.dir in local.properties to the nmea2log repo's own src/ directory, " +
            "e.g. nmea2log.src.dir=C\\:\\\\Users\\\\you\\\\...\\\\NMEA\\\\src (Windows) or " +
            "nmea2log.src.dir=/home/you/.../NMEA/src (Linux/macOS)."
    )

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
            srcDir(nmea2logSrcDir)
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