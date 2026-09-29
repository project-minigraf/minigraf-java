package io.github.project_minigraf.minigraf

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeLoaderTest {

    private fun dir(os: String, arch: String, musl: Boolean = false) =
        NativeLoader.resolve(os, arch, musl)?.dir

    @Test
    fun testLinuxGlibc() {
        assertEquals("linux/x86_64", dir("Linux", "amd64"))
        assertEquals("linux/x86_64", dir("Linux", "x86_64"))
        assertEquals("linux/aarch64", dir("Linux", "aarch64"))
    }

    @Test
    fun testLinuxMusl() {
        assertEquals("linux-musl/x86_64", dir("Linux", "amd64", musl = true))
        assertEquals("linux-musl/aarch64", dir("Linux", "aarch64", musl = true))
    }

    @Test
    fun testMacosUsesUniversalBinary() {
        assertEquals("macos/universal", dir("Mac OS X", "x86_64"))
        assertEquals("macos/universal", dir("Mac OS X", "aarch64"))
    }

    @Test
    fun testWindows() {
        assertEquals("windows/x86_64", dir("Windows 11", "amd64"))
        assertEquals("windows/aarch64", dir("Windows 11", "aarch64"))
        assertEquals("minigraf_ffi.dll", NativeLoader.resolve("Windows 11", "amd64", false)?.fileName)
    }

    @Test
    fun testUnsupportedArchitecturesAreRejected() {
        for (arch in listOf("riscv64", "ppc64le", "s390x", "x86", "i386", "arm")) {
            assertNull(dir("Linux", arch), "linux/$arch")
        }
        assertNull(dir("Windows 10", "x86"))
    }

    @Test
    fun testUnsupportedOsIsRejected() {
        assertNull(dir("FreeBSD", "amd64"))
        assertNull(dir("SunOS", "amd64"))
    }

    @Test
    fun testFacadeOpensDatabase() {
        val db = Minigraf.openInMemory()
        assertNotNull(db.execute("""(transact [[:alice :name "Alice"]])"""))
    }
}
