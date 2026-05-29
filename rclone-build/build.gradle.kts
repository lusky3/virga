// Cross-compiles the rclone binary for the three packaged ABIs and drops the
// resulting `librclone.so` files into :core:rclone's jniLibs/ source set. The
// task is incremental on the script + the version inputs, and skips when the
// outputs already exist (so CI only rebuilds on a version bump).

plugins {
    base
}

val rcloneVersion = "v1.69.1"
val ndkVersion = "27.2.12479018"
val minSdk = 26
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val jniLibsDir = rootProject.layout.projectDirectory.dir("core/rclone/src/main/jniLibs")

// Resolve the Android SDK location the same way the AGP plugin does:
// local.properties takes precedence over ANDROID_HOME / ANDROID_SDK_ROOT.
val sdkDir: String? = run {
    val props = java.util.Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) localProps.inputStream().use(props::load)
    props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
}

val buildRcloneBinaries by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compile rclone for each Android ABI and write librclone.so into core/rclone jniLibs"

    val script = rootProject.layout.projectDirectory.file("scripts/build-rclone.sh")
    inputs.file(script)
    inputs.property("rcloneVersion", rcloneVersion)
    inputs.property("ndkVersion", ndkVersion)
    inputs.property("minSdk", minSdk)
    inputs.property("abis", abis.joinToString(" "))
    abis.forEach { abi ->
        outputs.file(jniLibsDir.file("$abi/librclone.so"))
    }

    // Skip entirely when prebuilt binaries are already on disk. The binaries
    // are checked into git for contributor convenience, so the default
    // `./gradlew assemble*` is a no-op for this task. CI rebuilds by deleting
    // the jniLibs/ directory, or by bumping `rcloneVersion`.
    onlyIf {
        abis.any { abi ->
            !jniLibsDir.file("$abi/librclone.so").asFile.exists()
        }
    }

    commandLine("bash", script.asFile.absolutePath)
    environment("RCLONE_VERSION", rcloneVersion)
    environment("NDK_VERSION", ndkVersion)
    environment("MIN_SDK", minSdk.toString())
    environment("ABIS", abis.joinToString(" "))
    sdkDir?.let { environment("ANDROID_HOME", it) }
}

// Surface a uniformly-named task for the :core:rclone module to depend on.
tasks.register("assembleNativeBinaries") {
    group = "build"
    description = "Ensure prebuilt rclone binaries exist for :core:rclone"
    dependsOn(buildRcloneBinaries)
}
