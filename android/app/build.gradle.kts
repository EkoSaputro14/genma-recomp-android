plugins {
    id("com.android.application")
}

android {
    namespace = "com.genmarecomp.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.genmarecomp.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 70
        versionName = "v0.7.0-android.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASS") ?: "genmarecomp"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "genma"
                keyPassword = System.getenv("ANDROID_KEY_PASS") ?: "genmarecomp"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
