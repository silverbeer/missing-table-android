package com.missingtable.scorer.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every start-match path must send a half duration (SB-676).
 *
 * SB-645 put a half-length dialog on LiveScreen, but LineupScreen kept its own
 * START MATCH that sent `halfDuration = null` — so the route a scorer actually
 * takes (set the lineup, then start) silently used the server's 45 default for
 * every age group. Two entry points, one of them fixed.
 *
 * This is a source check rather than a behavioural test because the defect was
 * structural: a second copy of a flow that only one copy got fixed. A UI test
 * over one screen would not have caught it, for the same reason review didn't.
 */
class StartMatchPathsTest {

    private val uiDir = File("src/main/java/com/missingtable/scorer/ui")

    private fun sources(): List<File> =
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the ui source tree is where this test thinks it is`() {
        // Guard the guard: a moved package must fail loudly, not vacuously pass.
        assertTrue("ui sources not found at ${uiDir.absolutePath}", sources().size > 5)
    }

    @Test
    fun `no start_first_half is sent without a half duration`() {
        val offenders = sources().filter { file ->
            val text = file.readText()
            // A start_first_half call whose halfDuration is explicitly null.
            Regex("""start_first_half"[\s\S]{0,200}?halfDuration\s*=\s*null""")
                .containsMatchIn(text)
        }
        assertTrue(
            "these construct start_first_half with halfDuration = null, which " +
                "leaves the server default of ${HalfDuration.FALLBACK}: " +
                offenders.joinToString { it.name },
            offenders.isEmpty(),
        )
    }

    /**
     * Files that actually *send* a kickoff, not ones that merely mention the
     * action — OptimisticLive reads "start_first_half" when folding the pending
     * queue, which is not a start path.
     */
    private fun startPaths(): List<File> = sources().filter { file ->
        Regex("""ClockRequest\(\s*"start_first_half"""").containsMatchIn(file.readText())
    }

    @Test
    fun `every screen that starts a match uses the shared dialog`() {
        val starters = startPaths()
        assertTrue("expected at least two start paths, found ${starters.map { it.name }}", starters.size >= 2)
        val withoutDialog = starters.filterNot { "StartMatchDialog" in it.readText() }
        assertEquals(
            "these start a match without the shared half-length dialog: " +
                withoutDialog.joinToString { it.name },
            emptyList<String>(),
            withoutDialog.map { it.name },
        )
    }
}
