import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}

// A small Java-only Dex bridge: no application or authentication classes run as shell.
val vehicleBridgeBuild = tasks.register<Exec>("buildVehicleShellBridge") {
    val bridgeOutput = layout.buildDirectory.dir("vehicle-shell-bridge")
    inputs.files(rootProject.file("scripts/p7_vehicle_probe.py"),
        rootProject.fileTree("tools/p7-probe") { include("*.java") })
    outputs.file(bridgeOutput.map { it.file("p7-probe.jar") })
    commandLine("python3", rootProject.file("scripts/p7_vehicle_probe.py"),
        "--output", bridgeOutput.get().asFile)
}
abstract class VehicleBridgeAssetsTask : DefaultTask() {
    @get:InputFile abstract val bridgeJar: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun copyBridge() {
        val directory = outputDirectory.get().asFile.toPath()
        Files.createDirectories(directory)
        Files.copy(bridgeJar.get().asFile.toPath(), directory.resolve("p7-probe.jar"),
            StandardCopyOption.REPLACE_EXISTING)
    }
}
val vehicleBridgeAssets = tasks.register<VehicleBridgeAssetsTask>("copyVehicleShellBridge") {
    dependsOn(vehicleBridgeBuild)
    bridgeJar.set(layout.buildDirectory.file("vehicle-shell-bridge/p7-probe.jar"))
    outputDirectory.set(layout.buildDirectory.dir("generated/vehicle-shell-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(vehicleBridgeAssets) { it.outputDirectory }
}
