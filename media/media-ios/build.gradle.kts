// iOS playback: AVPlayer behind MediaPlayerInterface, the counterpart of media-jvm (mpv) and media3
// (ExoPlayer). Bound by core/data's iOS loader; nothing else depends on it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xwhen-guards")
    }

    sourceSets {
        iosMain {
            dependencies {
                implementation(projects.common)
                implementation(projects.domain)
                implementation(projects.ktorExt)
                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.core)
                implementation(libs.okio)
            }
        }
    }
}
