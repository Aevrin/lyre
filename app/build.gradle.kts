import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: signing.properties locally (gitignored), SIGNING_* env vars in CI.
val signCfg = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun sign(key: String): String? =
    System.getenv("SIGNING_" + key.uppercase())?.trim()?.takeIf { it.isNotEmpty() }
        ?: signCfg.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

val storeFilePath = sign("store_file")

// CI passes -PversionCode=<run number>. Play rejects any versionCode it has already seen.
val versionCodeArg = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
val versionLabel = "0.1" // bump by hand for each phase

val media3 = "1.10.0"

android {
    namespace = "com.aevrin.lyre"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aevrin.lyre"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeArg
        versionName = "$versionLabel.$versionCodeArg"
    }

    signingConfigs {
        if (storeFilePath != null) {
            create("upload") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = sign("store_password")
                keyAlias = sign("key_alias")
                keyPassword = sign("key_password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    // Media3's @UnstableApi parts (bitmap loader, session callbacks) are used on purpose.
    lint {
        disable += "UnsafeOptInUsageError"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Playback engine, lock-screen/notification/Bluetooth controls. Apache-2.0.
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.annotation:annotation:1.11.0")
    // Scrolling lists. Apache-2.0.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Reading and writing tags (ID3, Vorbis/FLAC, MP4, ...). LGPL-2.1.
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Tests only; nothing here ships in the app.
    testImplementation("junit:junit:4.13.2")
}

// An unsigned bundle is useless to Play; fail early with a clear message instead.
val hasSigning = storeFilePath != null
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    doFirst {
        if (!hasSigning) throw GradleException(
            "No upload key configured. Set SIGNING_* env vars or create signing.properties."
        )
    }
}
