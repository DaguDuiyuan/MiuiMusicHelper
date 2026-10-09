plugins {
    id("com.android.application")
}

android {
    namespace = "com.dagu.miimusichelper"
    compileSdk = 36

    defaultConfig {
        // LSPosed keys a module on this id: renaming it means the module has to be
        // disabled/re-enabled and its scope re-picked by hand.
        applicationId = "com.dagu.miimusichelper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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

    lint {
        abortOnError = false
    }
}

dependencies {
    // Modern Xposed API (LSPosed API 102). compileOnly on purpose: the framework
    // provides it at runtime and packaging it would shadow the real one.
    compileOnly("io.github.libxposed:api:102.0.0")
}
