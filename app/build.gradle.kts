plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    // NOTE: applicationId below is "in.nic.drugrepo" as specified. The namespace cannot be:
    // "in" is a hard Kotlin keyword, so `package in.nic.drugrepo` does not parse. The
    // namespace is an internal identifier only (R class package, relative manifest names);
    // applicationId is the externally visible install identifier and is unchanged.
    namespace = "nic.drugrepo"
    compileSdk = 37

    defaultConfig {
        applicationId = "in.nic.drugrepo"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-prototype"
        ndkVersion = "30.0.16248370"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        getByName("debug") {
            externalNativeBuild {
                cmake {
                    // TODO.md Phase 0 task 4: the C++ assert harness ships in debug APKs only.
                    // Passed as an explicit flag rather than inferred from CMAKE_BUILD_TYPE in
                    // CMakeLists.txt, because AGP 9.4 maps the release variant to
                    // CMAKE_BUILD_TYPE=RelWithDebInfo and a build-type test would then wrongly
                    // include the harness in release.
                    arguments += "-DVISION_NATIVE_TEST=ON"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // TODO.md Phase 0 task 3. Room persistence scaffolding and its SQLite support.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlite)
    ksp(libs.room.compiler)

    // TODO.md Phase 0 task 3. SQLCipher provides the at-rest encryption for the local
    // chain (architecture.md section 3). The passphrase handling is deliberately NOT here:
    // it must be Keystore-wrapped in TODO Phase 1/6, not a literal in this build file.
    implementation(libs.sqlcipher.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Room's schema JSON is a migration-testing artifact and must be reviewed and committed
// alongside schema changes; keep it out of the build directory so it survives `clean`.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}