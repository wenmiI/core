import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "org.simpmusic.loginsync"
        compileSdk = 37
        minSdk = 26
    }

    // Android sends, Desktop receives. The QR pairing (login sync, library sync) lives in jvmSharedMain,
    // Android and Desktop only, where it can use javax.crypto directly — no expect/actual for the
    // cipher. iOS gets commonMain alone: the sync account's client, which seals nothing.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.serialization.json)
                // Transport: ktorExt carries `api(ktor-client-core)` and the per-platform engine, the
                // same route listenTogether and kizzy take, so the engine choice stays in one place.
                implementation(projects.ktorExt)
                // Logger.
                implementation(projects.common)
                // The account channel's gzip, on every target.
                implementation(libs.okio)
            }
        }

        jvmMain {
            dependencies {
                // Only the Desktop hosts a server; the APK never ships these.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                // Account sync's host serves audio by byte range.
                implementation(libs.ktor.server.partial.content)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

tasks.withType<CompileArtProfileTask> {
    enabled = false
}
