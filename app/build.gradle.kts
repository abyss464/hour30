plugins {
    id("com.android.application")
}

android {
    namespace = "com.nightabyss.hour30"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nightabyss.hour30"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
