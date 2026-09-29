package io.github.project_minigraf.minigraf

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Extracts the platform-appropriate native library from JAR resources and points
 * the UniFFI bindings at it.
 *
 * The generated bindings call [load] automatically before the native library is
 * first resolved, so callers never need to invoke it. It is idempotent.
 */
object NativeLoader {
    private const val ISSUES_URL = "https://github.com/project-minigraf/minigraf-java/issues"

    @Volatile private var loaded = false

    /** Resource directory and file name of the native library for one platform. */
    internal data class NativeLib(val dir: String, val fileName: String)

    /**
     * Maps JVM platform properties to the bundled native, or `null` when no native
     * is bundled for that platform. Pure, so it can be unit-tested for any platform.
     */
    internal fun resolve(osName: String, osArch: String, isMusl: Boolean): NativeLib? {
        val os = osName.lowercase()
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64", "x86-64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return null
        }
        return when {
            os.startsWith("linux") && isMusl -> NativeLib("linux-musl/$arch", "libminigraf_ffi.so")
            os.startsWith("linux") -> NativeLib("linux/$arch", "libminigraf_ffi.so")
            os.startsWith("mac") -> NativeLib("macos/universal", "libminigraf_ffi.dylib")
            os.startsWith("windows") -> NativeLib("windows/$arch", "minigraf_ffi.dll")
            else -> null
        }
    }

    /** musl libc (e.g. Alpine) installs its dynamic loader as /lib/ld-musl-<arch>.so.1. */
    private fun isMusl(): Boolean =
        File("/lib").listFiles { f -> f.name.startsWith("ld-musl-") }?.isNotEmpty() == true

    @Synchronized
    fun load() {
        if (loaded) return

        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")
        val isLinux = osName.lowercase().startsWith("linux")
        val lib = resolve(osName, osArch, isLinux && isMusl())
            ?: throw UnsupportedOperationException(
                "Unsupported platform: $osName / $osArch. " +
                "Bundled natives cover Linux (glibc and musl) and Windows on x86_64/aarch64, " +
                "and macOS universal2. Please file an issue at $ISSUES_URL"
            )

        val resourcePath = "/natives/${lib.dir}/${lib.fileName}"
        val bytes = NativeLoader::class.java.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: throw UnsatisfiedLinkError(
                "Native library not found in JAR: $resourcePath. " +
                "This build of minigraf-jvm does not include a native for this platform; " +
                "please file an issue at $ISSUES_URL"
            )

        val isWindows = osName.lowercase().startsWith("windows")
        val dest = if (isWindows) extractCached(bytes, lib.fileName) else extractTemp(bytes, lib.fileName)

        // JNA's Native.register() uses findLibraryName() which reads this property.
        // Providing an absolute path causes JNA to dlopen/LoadLibrary directly from
        // the extracted file, bypassing the system library search path.
        System.setProperty("uniffi.component.minigraf_ffi.libraryOverride", dest.absolutePath)
        loaded = true
    }

    /** Extracts into a fresh temp directory that is removed when the JVM exits. */
    private fun extractTemp(bytes: ByteArray, fileName: String): File {
        val tmpDir = Files.createTempDirectory("minigraf_native").toFile()
        tmpDir.deleteOnExit()
        val dest = File(tmpDir, fileName)
        dest.deleteOnExit()
        dest.writeBytes(bytes)
        return dest
    }

    /**
     * Windows cannot delete a DLL while it is loaded, so deleteOnExit would leave a
     * directory behind on every run. Instead, extract once into a directory keyed
     * by the library's hash and reuse it across JVMs. The existing file is only
     * reused if its hash still matches.
     */
    private fun extractCached(bytes: ByteArray, fileName: String): File {
        val hash = sha256(bytes)
        val dir = File(System.getProperty("java.io.tmpdir"), "minigraf-native-${hash.take(16)}")
        val dest = File(dir, fileName)
        if (dest.isFile && sha256(dest.readBytes()) == hash) return dest

        var part: File? = null
        return try {
            dir.mkdirs()
            part = File.createTempFile("$fileName.", ".part", dir)
            part.writeBytes(bytes)
            // A concurrent JVM may have loaded dest, which locks it; the move then fails.
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dest
        } catch (e: Exception) {
            part?.delete()
            if (dest.isFile && sha256(dest.readBytes()) == hash) dest else extractTemp(bytes, fileName)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
