/**
 * androidApp/ — the Android application: one Activity, the runtime permission flow, and
 * the object graph that binds :shared's interfaces to the platform implementations.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.surdy.hazri.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.surdy.hazri"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // The simulated source is offered as a third option in the source picker only
            // when this is true, so a release build cannot be accidentally read as real data.
            buildConfigField("boolean", "SIMULATION_AVAILABLE", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "SIMULATION_AVAILABLE", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // The HiveMQ client brings six Netty jars, each with its own copy of these
            // metadata files. None of them is read at runtime on Android, and the merger
            // fails rather than picking one, so they are excluded here.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
