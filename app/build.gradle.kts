plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nao.md.project"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nao.md.project"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "3.5.1"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = false
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.resources.excludes += "META-INF/INDEX.LIST"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // Current NewPipeExtractor handles YouTube/InnerTube stream extraction,
    // including signature/throttling changes that a hand-written player client
    // cannot reliably keep up with.
    implementation("com.github.teamnewpipe:newpipeextractor:v0.26.4")
    // NewPipeExtractor v0.26.4 declares nanojson from the TeamNewPipe JitPack group.
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    // NewPipeExtractor calls java.net.URLDecoder.decode(String, Charset), which
    // only exists on API 33+. Only the "nio" desugared-library configuration
    // backports java.net.URLDecoder/URLEncoder, so Android 8/9/10/11/12 devices
    // crash with NoSuchMethodError when the plain desugar_jdk_libs variant is used.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // LAME MP3 encoder (native, via JNI) untuk NaoMp3Encoder.
    //
    // TIDAK dipakai lewat dependency JitPack (com.github.NorthernCaptain:
    // TAndroidLame) karena AAR itu bawa resource demo app lama (attrs.xml
    // dengan actionBarSize dkk) yang bentrok "Duplicate value for resource"
    // dengan Material 1.13.0. AAR itu juga bawa com.android.support:support-v4
    // lama yang bentrok androidx (duplicate class).
    //
    // Sebagai gantinya kita pakai isinya secara langsung, tanpa resource:
    //  - app/libs/androidlame.jar   (classes.jar dari dalam AAR)
    //  - app/src/main/jniLibs/<abi>/libandroidlame.so (native lib dari AAR)
    // Lihat app/libs/README-androidlame.md untuk cara ambil kedua berkas itu
    // dari gradle cache.
    implementation(files("libs/androidlame.jar"))
}
