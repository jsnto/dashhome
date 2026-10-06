package com.jsnto.dashhome.ui

import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import com.jsnto.dashhome.ui.theme.DashhomeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Drag-to-resize pins for the tablet board's handles, mirroring the web
 * dashboard's dividers (frontend/src/App.tsx):
 *
 *  - the board carries a drag handle in every window gap - the column
 *    handle between the two columns, an in-column handle in each
 *    column's window gap - and dragging one moves its split by the
 *    pointer's travel over the split's total size, exactly like the
 *    web's dividers;
 *  - a split is clamped to 0.1..0.9 of the split (the web's
 *    MIN_FRAC/MAX_FRAC), so a window can never be dragged away - honored
 *    at both ends of the column handle;
 *  - an untouched handle keeps its proportions: a column drag leaves the
 *    in-column 1:1 splits, an in-column drag leaves the column split;
 *  - with no drag the default composition is unchanged: the web's 7/12
 *    column split and the 1:1 in-column splits;
 *  - each handle names its resize the way the web's dividers do (the
 *    aria-label on the web's role=separator dividers), so a screen reader
 *    can discover and operate it.
 *
 * The proportions are session-only Compose state (the web keeps them in
 * React state): they reset to the defaults on restart and no resize
 * configuration is persisted - the pins read the sizes straight off the
 * composed board, which opens on the defaults on every fresh start.
 *
 * The tests point the app at an unreachable test server, so every
 * section settles into its deterministic offline state - no live
 * dashhome stack is needed. Expected sizes are normalized from the
 * measured board size, so the pins hold at any display density.
 */
@RunWith(RobolectricTestRunner::class)
class TabletBoardResizeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun startApp() {
        Prefs.setBase(composeRule.activity, "http://127.0.0.1:1")
        DashApi.baseUrl = "http://127.0.0.1:1"
        composeRule.setContent {
            DashhomeTheme {
                DashhomeRoot()
            }
        }
        composeRule.waitForIdle()
    }

    // --- measured board geometry (px, off the composed board) --------------

    private fun size(tag: String): IntSize =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().size

    /**
     * Drag a handle by (dx, dy) px from its center, in small steps. The
     * performTouchInput coordinate space is the node's local system, so
     * the gesture starts at the node's own center.
     */
    private fun dragHandle(tag: String, dx: Float, dy: Float) {
        val start = Offset(size(tag).width / 2f, size(tag).height / 2f)
        val steps = 20
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(start)
            for (i in 1..steps) {
                moveTo(
                    Offset(
                        start.x + dx * i / steps,
                        start.y + dy * i / steps,
                    ),
                )
            }
            up()
        }
        composeRule.waitForIdle()
    }

    /**
     * The tolerance a drag assertion allows: a drag loses at most the
     * touch slop before the gesture is recognized, and weight rounding
     * loses at most a pixel or two.
     */
    private fun tol(): Int =
        2 * ViewConfiguration.get(composeRule.activity).scaledTouchSlop + 4

    /** The board's total split width: main column + handle + side column. */
    private val boardWidth: Int
        get() =
            size("boardCol-main").width +
                size("boardHandle-col").width +
                size("boardCol-side").width

    // --- default composition ------------------------------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun noDragKeepsTheDefaultWebBoardComposition() {
        startApp()

        // The handles are present in every window gap.
        composeRule.onNodeWithTag("boardHandle-col").assertExists()
        composeRule.onNodeWithTag("boardHandle-main").assertExists()
        composeRule.onNodeWithTag("boardHandle-side").assertExists()

        // The column split is the web's 7/12 / 5/12.
        val available = boardWidth - size("boardHandle-col").width
        val mainW = size("boardCol-main").width
        val sideW = size("boardCol-side").width
        assertTrue(
            "main $mainW !~ ${available * 7f / 12f}",
            abs(mainW - available * 7f / 12f) <= 4f,
        )
        assertTrue(
            "side $sideW !~ ${available * 5f / 12f}",
            abs(sideW - available * 5f / 12f) <= 4f,
        )

        // The in-column splits are 1:1.
        val mainColH = size("boardCol-main").height
        val mainHandleH = size("boardHandle-main").height
        val calH = size("boardWin-calendar").height
        val hermesH = size("boardWin-hermes").height
        assertTrue("cal $calH vs hermes $hermesH", abs(calH - hermesH) <= 1)
        assertTrue(
            "cal $calH !~ ${(mainColH - mainHandleH) / 2f}",
            abs(calH - (mainColH - mainHandleH) / 2f) <= 4f,
        )
        assertTrue(
            "home vs push",
            abs(size("boardWin-home").height - size("boardWin-push").height) <= 1,
        )
    }

    // --- handle accessibility: the web dividers' aria-labels -------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun theHandlesNameTheirResizeLikeTheWebDividers() {
        startApp()

        // The web's dividers expose role=separator plus an aria-label
        // naming the two views they resize; the handles carry the same
        // labels, so a screen reader can discover and operate the resize.
        composeRule
            .onNodeWithTag("boardHandle-main")
            .assertContentDescriptionEquals("Resize Calendar and Hermes")
        composeRule
            .onNodeWithTag("boardHandle-col")
            .assertContentDescriptionEquals("Resize columns")
        composeRule
            .onNodeWithTag("boardHandle-side")
            .assertContentDescriptionEquals("Resize Home and Hermes push")
    }

    // --- the column handle: the column split --------------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun draggingTheColumnHandleChangesTheColumnSplit() {
        startApp()

        val boardW = boardWidth
        val available = boardW - size("boardHandle-col").width
        val mainW0 = size("boardCol-main").width
        val sideW0 = size("boardCol-side").width

        // Drag the column handle right by 30% of the board width: the
        // column split moves by the travel over the board width, like
        // the web's divider.
        val dx = 0.3f * boardW
        dragHandle("boardHandle-col", dx, 0f)

        val mainW1 = size("boardCol-main").width
        val sideW1 = size("boardCol-side").width
        val t = tol().toFloat()
        val expectedMain = available * (7f / 12f + dx / boardW)
        assertTrue("main $mainW1 !~ $expectedMain", abs(mainW1 - expectedMain) <= t)
        assertTrue(
            "side $sideW1 !~ ${available - expectedMain}",
            abs(sideW1 - (available - expectedMain)) <= t,
        )
        assertTrue("main did not grow: $mainW0 -> $mainW1", mainW1 > mainW0 + 50)
        assertTrue("side did not shrink: $sideW0 -> $sideW1", sideW1 < sideW0 - 50)
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun theColumnHandleClampsAtBothEnds() {
        startApp()

        val available = boardWidth - size("boardHandle-col").width
        val t = tol().toFloat()

        // Far past the 0.9 ceiling: the main column is clamped to 90% of
        // the split and can never be dragged away.
        dragHandle("boardHandle-col", 3f * boardWidth, 0f)
        var mainW = size("boardCol-main").width
        var sideW = size("boardCol-side").width
        assertTrue("main $mainW !~ ${available * 0.9f}", abs(mainW - available * 0.9f) <= t)
        assertTrue("side $sideW !~ ${available * 0.1f}", abs(sideW - available * 0.1f) <= t)
        assertTrue("main $mainW <= side $sideW", mainW > sideW * 5)

        // Far past the 0.1 floor: the main column is clamped to 10% of
        // the split.
        dragHandle("boardHandle-col", -6f * boardWidth, 0f)
        mainW = size("boardCol-main").width
        sideW = size("boardCol-side").width
        assertTrue("main $mainW !~ ${available * 0.1f}", abs(mainW - available * 0.1f) <= t)
        assertTrue("side $sideW !~ ${available * 0.9f}", abs(sideW - available * 0.9f) <= t)
        assertTrue("side $sideW <= main $mainW", sideW > mainW * 5)
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun aColumnDragKeepsTheInColumnSplits() {
        startApp()

        dragHandle("boardHandle-col", 0.3f * boardWidth, 0f)

        // The in-column handles are untouched: both columns keep their
        // 1:1 splits.
        assertTrue(
            "cal vs hermes",
            abs(size("boardWin-calendar").height - size("boardWin-hermes").height) <= 1,
        )
        assertTrue(
            "home vs push",
            abs(size("boardWin-home").height - size("boardWin-push").height) <= 1,
        )
    }

    // --- the in-column handles: each column's split -------------------------

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun draggingTheMainColumnHandleChangesThatColumnsSplit() {
        startApp()

        val mainColH = size("boardCol-main").height
        val handleH = size("boardHandle-main").height
        val availableH = mainColH - handleH
        val mainW0 = size("boardCol-main").width
        val sideW0 = size("boardCol-side").width
        val calH0 = size("boardWin-calendar").height
        val hermesH0 = size("boardWin-hermes").height

        // Drag the in-column handle down by 30% of the column height: the
        // column's split moves by the travel over the column height.
        val dy = 0.3f * mainColH
        dragHandle("boardHandle-main", 0f, dy)

        val t = tol().toFloat()
        val calH1 = size("boardWin-calendar").height
        val hermesH1 = size("boardWin-hermes").height
        val expectedCal = availableH * (0.5f + dy / mainColH)
        assertTrue("cal $calH1 !~ $expectedCal", abs(calH1 - expectedCal) <= t)
        assertTrue(
            "hermes $hermesH1 !~ ${availableH - expectedCal}",
            abs(hermesH1 - (availableH - expectedCal)) <= t,
        )
        assertTrue("cal did not grow: $calH0 -> $calH1", calH1 > calH0 + 20)
        assertTrue(
            "hermes did not shrink: $hermesH0 -> $hermesH1",
            hermesH1 < hermesH0 - 20,
        )

        // The untouched column handle keeps the column split.
        assertTrue("main column moved", size("boardCol-main").width == mainW0)
        assertTrue("side column moved", size("boardCol-side").width == sideW0)
    }

    @Config(sdk = [35], qualifiers = "w800dp-h500dp")
    @Test
    fun draggingTheSideColumnHandleChangesThatColumnsSplit() {
        startApp()

        val sideColH = size("boardCol-side").height
        val handleH = size("boardHandle-side").height
        val availableH = sideColH - handleH
        val mainW0 = size("boardCol-main").width
        val sideW0 = size("boardCol-side").width
        val homeH0 = size("boardWin-home").height
        val pushH0 = size("boardWin-push").height

        // Drag the side column's in-column handle up by 30% of the column
        // height: the column's split moves by the travel over the column
        // height (the top window shrinks, the bottom one grows).
        val dy = -0.3f * sideColH
        dragHandle("boardHandle-side", 0f, dy)

        val t = tol().toFloat()
        val homeH1 = size("boardWin-home").height
        val pushH1 = size("boardWin-push").height
        val expectedHome = availableH * (0.5f + dy / sideColH)
        assertTrue("home $homeH1 !~ $expectedHome", abs(homeH1 - expectedHome) <= t)
        assertTrue(
            "push $pushH1 !~ ${availableH - expectedHome}",
            abs(pushH1 - (availableH - expectedHome)) <= t,
        )
        assertTrue("home did not shrink: $homeH0 -> $homeH1", homeH1 < homeH0 - 20)
        assertTrue("push did not grow: $pushH0 -> $pushH1", pushH1 > pushH0 + 20)

        // The untouched column handle keeps the column split.
        assertTrue("main column moved", size("boardCol-main").width == mainW0)
        assertTrue("side column moved", size("boardCol-side").width == sideW0)
    }
}
