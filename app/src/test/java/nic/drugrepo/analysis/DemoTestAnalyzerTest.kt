package nic.drugrepo.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * The DEMO analyzer must be a pure function of the captured bytes (AGENTS.md directive 6):
 * the same frame has to reproduce the same reported result, or a demo re-run cannot be shown
 * to agree with itself. It stays MOCK either way - nothing here measures colour.
 */
class DemoTestAnalyzerTest {

    private val analyzer = DemoTestAnalyzer()

    @Test
    fun sameImageBytesGiveTheSameResult() {
        val a = File.createTempFile("drugrepo-demo", ".jpg").apply { writeBytes(ByteArray(64) { 7 }) }
        val b = File.createTempFile("drugrepo-demo", ".jpg").apply { writeBytes(ByteArray(64) { 7 }) }
        assertEquals(analyzer.analyze(a), analyzer.analyze(b))
        a.delete()
        b.delete()
    }

    @Test
    fun differentImageBytesChangeTheResult() {
        val a = File.createTempFile("drugrepo-demo", ".jpg").apply { writeBytes(ByteArray(64) { 7 }) }
        val b = File.createTempFile("drugrepo-demo", ".jpg").apply { writeBytes(ByteArray(64) { 8 }) }
        assertNotEquals(
            "${analyzer.analyze(a).ciede2000Result}/${analyzer.analyze(a).presumptiveResult}",
            "${analyzer.analyze(b).ciede2000Result}/${analyzer.analyze(b).presumptiveResult}",
        )
        a.delete()
        b.delete()
    }
}
