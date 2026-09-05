plugins {
    id("com.android.application")
}

android {
    namespace = "com.steadyvault.camera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.steadyvault.camera"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        versionCode = 1000139
        versionName = "1.8.131"
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libc++_shared.so",
                "**/libvlc.so",
                "**/libvlcjni.so",
                "**/libaria2c.so",
                "**/libaria2c.zip.so",
                "**/libffmpeg.so",
                "**/libffmpeg.zip.so",
                "**/libffprobe.so",
                "**/libpython.so",
                "**/libpython.zip.so",
                "**/libqjs.so"
            )
        }
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}


configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.core:core:1.19.0",
            "androidx.core:core-ktx:1.19.0"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("org.videolan.android:libvlc-all:3.7.4")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
