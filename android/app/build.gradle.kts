plugins {
    id("com.android.application")
}

// Shallow-clone SDL3 (release-3.4.16) matching rafaelkenedy/genma-recomp & Black-Android.
// Its Java sources (SDLActivity, SDLControllerManager) are compiled into the APK; its C side builds libSDL3.so.
val sdlDir = file("../../third_party/SDL")
val sdlTag = "release-3.4.16"
tasks.register<Exec>("fetchSDL") {
    onlyIf { !File(sdlDir, "CMakeLists.txt").exists() }
    commandLine("git", "clone", "--depth", "1", "--branch", sdlTag, "https://github.com/libsdl-org/SDL.git", sdlDir.absolutePath)
}
tasks.named("preBuild") {
    dependsOn("fetchSDL")
}

android {
    namespace = "com.genmarecomp.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.genmarecomp.android"
        minSdk = 29
        targetSdk = 34
        versionCode = if (project.hasProperty("versionCode")) project.property("versionCode").toString().toInt() else 70
        versionName = if (project.hasProperty("versionName")) project.property("versionName").toString() else "0.7.0-android.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                if (project.hasProperty("xbeGenerated")) {
                    arguments += "-DXBE_GENERATED=${project.property("xbeGenerated")}"
                }
                if (project.hasProperty("xbeImage")) {
                    arguments += "-DXBE_IMAGE=${project.property("xbeImage")}"
                }
            }
        }
    }

    val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASS") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ANDROID_KEY_PASS") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fall back to Android debug signing when no release keystore secret is configured (Black-Android pattern)
            signingConfig = if (!ksPath.isNullOrBlank()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // Recompiled x86 code is too slow unoptimized
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(File(sdlDir, "android-project/app/src/main/java"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
