// The README quick start, line for line (readme_sync.py checks this), wrapped
// in a test.
import uniffi.minigraf_ffi.MiniGrafDb

import kotlin.test.Test
import kotlin.test.assertTrue

class QuickstartTest {
    @Test
    fun readmeQuickStart() {
        val db = MiniGrafDb.openInMemory()
        val result = db.execute("""(transact [[:alice :name "Alice"]])""")
        println(result)  // {"transacted":<tx id>}
        assertTrue(result.startsWith("{\"transacted\":"))
    }
}
