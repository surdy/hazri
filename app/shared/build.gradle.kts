import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * shared/ — everything that is not a platform capability.
 *
 * commonMain holds the domain (smoothing, distance, verdicts, surveys), the ESPresense
 * protocol helpers, the simulated signal source, the repository, the view models and the
 * whole Compose Multiplatform UI. androidMain holds only the two platform seams that
 * cannot be written once: the Kable BLE scanner behind `DirectScanSource` and the HiveMQ
 * client behind `MqttGateway`.
 *
 * iOS targets are deliberately absent. Adding `iosArm64()` and `iosSimulatorArm64()` here
 * is the whole change on the build side — every platform-dependent declaration is already
 * an `expect`/`actual` pair or an interface, so the iOS work is `DirectScanSource.ios.kt`
 * (Kable publishes the same API for Darwin), an `MqttGateway` actual, and a `FileStore`
 * actual. They are left out of this pass because `:shared:allTests` would then want to run
 * the simulator test binaries, which is a longer loop than this build is asked to keep green.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // `expect`/`actual` classes are still flagged Beta by the compiler (KT-61573). There is
    // exactly one in this module — DirectScanSource — and it is the shape the brief asks
    // for, so the warning is acknowledged here rather than repeated on every build.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "dev.surdy.hazri.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            implementation(libs.compose.ui.backhandler)
            api(libs.compose.material3)
            implementation(libs.compose.components.resources)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kable.core)
            implementation(libs.hivemq.mqtt.client)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.surdy.hazri.generated.resources"
    publicResClass = false
    generateResClass = auto
}
