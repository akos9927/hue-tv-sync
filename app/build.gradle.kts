plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions sets GITHUB_RUN_NUMBER; each build gets a higher version so updates install over it.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.qi.huetvsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qi.huetvsync"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"akos9927/hue-tv-sync\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "huetvsync"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    // DTLS 1.2 with a pre-shared key, which the Hue Entertainment API requires.
    implementation("org.bouncycastle:bctls-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
