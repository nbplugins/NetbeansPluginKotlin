package io.github.nbplugins.kotlin.nbm.startup

import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [FakeIntellijHome].
 *
 * Extends [NbTestCase] directly (not [utils.KotlinTestCase]) to avoid a circular dependency
 * where KotlinTestCase itself calls [FakeIntellijHome.startUp].
 * Each test manages [FakeIntellijHome.startUp]/[FakeIntellijHome.shutDown] manually.
 */
class FakeIntellijHomeTest : NbTestCase("FakeIntellijHomeTest") {

    override fun tearDown() {
        FakeIntellijHome.shutDown()
        super.tearDown()
    }

    /** After [FakeIntellijHome.startUp], the temp directory and required files must exist. */
    fun testStartUpCreatesHomePath() {
        FakeIntellijHome.startUp()

        val homeStr = System.getProperty("idea.home.path")
        assertNotNull("idea.home.path must be set after startUp", homeStr)

        val home = Path.of(homeStr!!)
        assertTrue("home directory must exist", Files.isDirectory(home))
        assertTrue("build.txt must exist", Files.isRegularFile(home.resolve("build.txt")))
        assertTrue("bin/idea.properties must exist", Files.isRegularFile(home.resolve("bin/idea.properties")))
    }

    /** A second call to [FakeIntellijHome.startUp] must be a no-op (same path returned). */
    fun testStartUpIsIdempotent() {
        FakeIntellijHome.startUp()
        val firstHome = System.getProperty("idea.home.path")

        FakeIntellijHome.startUp()
        val secondHome = System.getProperty("idea.home.path")

        assertEquals("startUp must be idempotent — path must not change", firstHome, secondHome)
    }

    /** After [FakeIntellijHome.shutDown], the directory is deleted and the property cleared. */
    fun testShutDownDeletesDirectory() {
        FakeIntellijHome.startUp()
        val homeStr = System.getProperty("idea.home.path")!!
        val home = Path.of(homeStr)

        FakeIntellijHome.shutDown()

        assertFalse("home directory must be deleted after shutDown", Files.exists(home))
        assertNull("idea.home.path must be cleared after shutDown", System.getProperty("idea.home.path"))
    }

    /** Calling [FakeIntellijHome.shutDown] without a prior [FakeIntellijHome.startUp] must not throw. */
    fun testShutDownWithoutStartUpIsNoop() {
        FakeIntellijHome.shutDown() // must not throw
    }
}
