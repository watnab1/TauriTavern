import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Builds the Rust library with the Release Cargo profile and links it into the
 * Android project without requiring the Tauri CLI options server that backs
 * `tauri android android-studio-script`. The E2E Gradle variant is assembled
 * directly with Gradle, so the Android Studio bridge is not available.
 */
open class E2eBuildTask : DefaultTask() {
    @Input
    var rootDirRel: String? = null

    @Input
    var target: String? = null

    @Input
    var minSdk: Int = 26

    @TaskAction
    fun assemble() {
        val crateDir = File(project.projectDir, rootDirRel ?: "../../../").canonicalFile
        val targetName = target ?: throw GradleException("target cannot be null")
        val targetSpec = targetSpecs[targetName]
            ?: throw GradleException("Unsupported E2E Rust target: $targetName")

        val ndkDir = resolveNdkDir()
        val toolchain = File(ndkDir, "toolchains/llvm/prebuilt/${hostTag()}/bin")
        val clang = File(toolchain, "${targetSpec.clangPrefix}${minSdk}-clang${exeSuffix()}")
        val clangCxx = File(toolchain, "${targetSpec.clangPrefix}${minSdk}-clang++${exeSuffix()}")
        val llvmAr = File(toolchain, "llvm-ar${exeSuffix()}")
        if (!clang.isFile || !clangCxx.isFile || !llvmAr.isFile) {
            throw GradleException(
                "Android NDK toolchain is incomplete for $targetName: $clang, $clangCxx, $llvmAr"
            )
        }

        val rustflags = listOf(
            "-C", "target-feature=+fp16",
            "-Clink-arg=-landroid",
            "-Clink-arg=-llog",
            "-Clink-arg=-lOpenSLES",
        ).joinToString(" ")

        project.exec {
            workingDir = crateDir
            environment("ANDROID_NATIVE_API_LEVEL", minSdk.toString())
            environment("TARGET_AR", llvmAr.absolutePath)
            environment("TARGET_CC", clang.absolutePath)
            environment("TARGET_CXX", clangCxx.absolutePath)
            environment("CARGO_TARGET_${targetSpec.rustEnvTriple}_LINKER", clang.absolutePath)
            environment("CARGO_TARGET_${targetSpec.rustEnvTriple}_RUSTFLAGS", rustflags)
            commandLine(
                "cargo",
                "build",
                "--manifest-path", File(crateDir, "Cargo.toml").absolutePath,
                "--package", "tauritavern",
                "--target", targetSpec.triple,
                "--release",
                "--color", "always",
            )
        }.assertNormalExitValue()

        linkReleaseLib(crateDir, targetSpec)
    }

    private fun linkReleaseLib(crateDir: File, targetSpec: TargetSpec) {
        val workspaceRoot = crateDir.parentFile?.parentFile ?: crateDir
        val cargoTargetRoot =
            System.getenv("CARGO_TARGET_DIR")?.let(::File) ?: File(workspaceRoot, "target")
        val releaseLib = File(
            cargoTargetRoot,
            "${targetSpec.triple}/release/libtauritavern_lib.so"
        )
        if (!releaseLib.isFile) {
            throw GradleException("E2E Rust library was not produced: ${releaseLib.absolutePath}")
        }

        val jniLibsDir = File(project.projectDir, "src/main/jniLibs/${targetSpec.abi}")
        jniLibsDir.mkdirs()

        val libLink = File(jniLibsDir, "libtauritavern_lib.so")
        replaceLinkOrCopy(releaseLib.toPath(), libLink)

        val ndkSysroot = File(
            resolveNdkDir(),
            "toolchains/llvm/prebuilt/${hostTag()}/sysroot/usr/lib/${targetSpec.ndkLibDir}"
        )
        val libCxx = File(ndkSysroot, "libc++_shared.so")
        if (libCxx.isFile) {
            replaceLinkOrCopy(libCxx.toPath(), File(jniLibsDir, "libc++_shared.so"))
        }
    }

    private fun replaceLinkOrCopy(source: Path, destination: File) {
        destination.delete()
        try {
            Files.createSymbolicLink(destination.toPath(), source)
        } catch (_: UnsupportedOperationException) {
            Files.copy(source, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.FileSystemException) {
            Files.copy(source, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resolveNdkDir(): File {
        val candidates = mutableListOf<File>()
        for (envName in listOf("ANDROID_NDK_HOME", "NDK_HOME")) {
            System.getenv(envName)?.let { candidates.add(File(it)) }
        }
        for (envName in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            System.getenv(envName)?.let { sdkRoot ->
                findNewestNdk(File(sdkRoot, "ndk"))?.let { candidates.add(it) }
            }
        }

        return candidates.firstOrNull { it.isDirectory }
            ?: throw GradleException(
                "Android NDK not found. Set ANDROID_NDK_HOME or NDK_HOME (or ANDROID_HOME with an ndk/ subdirectory)."
            )
    }

    private fun findNewestNdk(ndkRoot: File): File? =
        ndkRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "source.properties").isFile }
            ?.maxByOrNull { it.name }

    private fun hostTag(): String = when {
        Os.isFamily(Os.FAMILY_MAC) -> "darwin-x86_64"
        Os.isFamily(Os.FAMILY_WINDOWS) -> "windows-x86_64"
        else -> "linux-x86_64"
    }

    private fun exeSuffix(): String = if (Os.isFamily(Os.FAMILY_WINDOWS)) ".exe" else ""

    private data class TargetSpec(
        val triple: String,
        val rustEnvTriple: String,
        val clangPrefix: String,
        val abi: String,
        val ndkLibDir: String,
    )

    companion object {
        private val targetSpecs = mapOf(
            "aarch64" to TargetSpec(
                triple = "aarch64-linux-android",
                rustEnvTriple = "AARCH64_LINUX_ANDROID",
                clangPrefix = "aarch64-linux-android",
                abi = "arm64-v8a",
                ndkLibDir = "aarch64-linux-android",
            ),
            "armv7" to TargetSpec(
                triple = "armv7-linux-androideabi",
                rustEnvTriple = "ARMV7_LINUX_ANDROIDEABI",
                clangPrefix = "armv7a-linux-androideabi",
                abi = "armeabi-v7a",
                ndkLibDir = "arm-linux-androideabi",
            ),
            "i686" to TargetSpec(
                triple = "i686-linux-android",
                rustEnvTriple = "I686_LINUX_ANDROID",
                clangPrefix = "i686-linux-android",
                abi = "x86",
                ndkLibDir = "i686-linux-android",
            ),
            "x86_64" to TargetSpec(
                triple = "x86_64-linux-android",
                rustEnvTriple = "X86_64_LINUX_ANDROID",
                clangPrefix = "x86_64-linux-android",
                abi = "x86_64",
                ndkLibDir = "x86_64-linux-android",
            ),
        )
    }
}
