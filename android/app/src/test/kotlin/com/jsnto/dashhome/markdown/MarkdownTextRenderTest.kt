package com.jsnto.dashhome.markdown

import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jsnto.dashhome.ui.theme.DashhomeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders [MarkdownText] through the real Compose + Markwon stack under
 * Robolectric (JVM, no emulator) and proves the push-feed body is drawn
 * visibly: non-empty text, the theme's onSurface color, and no span that
 * repaints the body transparent or black on the dark card.
 *
 * Regression guard for the "invisible markdown body" defect found on-device:
 * the body TextView was created with `contentColor.value.toInt()` - the LOW
 * 32 bits of Compose's Color.value (the color-space tag) - i.e. 0, so the
 * text was painted transparent and only the blockquote bar (derived from the
 * same base color) was faintly visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownTextRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun findMarkdownView(): TextView {
        fun walk(v: View): TextView? {
            if (v is TextView && v.text.contains("Morning")) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        val found = walk(composeRule.activity.window.decorView)
        assertNotNull("markdown TextView missing from the view tree", found)
        return found!!
    }

    @Test
    fun markdownBodyIsVisibleWithThemeTextColor() {
        composeRule.setContent {
            DashhomeTheme {
                MarkdownText(
                    "**Morning standup** in 30 min.\n\n" +
                        "- Review PR #14\n" +
                        "- Check CI on the android app\n" +
                        "- Update the tracker\n\n" +
                        "> Keep it short.",
                )
            }
        }
        composeRule.waitForIdle()

        val tv = findMarkdownView()
        val text = tv.text
        val expected = Color(0xFFDCE7F5).toArgb()

        println("MarkdownTextRender: text=<${text}>")
        println("MarkdownTextRender: currentTextColor=0x${Integer.toHexString(tv.currentTextColor)}")

        assertTrue("markdown body must not be empty", text.isNotEmpty())

        val spanned = text as Spanned
        val colorSpans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
        colorSpans.forEach { s ->
            println(
                "MarkdownTextRender: ForegroundColorSpan 0x${Integer.toHexString(s.foregroundColor)} " +
                    "[${spanned.getSpanStart(s)}..${spanned.getSpanEnd(s)}]",
            )
        }

        assertEquals(
            "body must use the theme's onSurface color (visible on the dark card)",
            expected,
            tv.currentTextColor,
        )
        // No span may repaint the whole body with an invisible color.
        colorSpans
            .filter { spanned.getSpanStart(it) == 0 && spanned.getSpanEnd(it) == spanned.length }
            .forEach { s ->
                val c = s.foregroundColor
                assertTrue(
                    "a full-range ForegroundColorSpan paints the body invisible: 0x${Integer.toHexString(c)}",
                    (c and 0xFF000000.toInt()) != 0 && (c and 0xFFFFFF) != 0,
                )
            }
    }

    @Test
    fun markdownLinkIsTheGuardedClickableSpanNotARawUrlSpan() {
        composeRule.setContent {
            DashhomeTheme {
                MarkdownText("Morning standup\n\n[open the tracker](mailto:ops@example.com)")
            }
        }
        composeRule.waitForIdle()

        val tv = findMarkdownView()
        val spanned = tv.text as Spanned
        assertEquals(
            "a raw URLSpan lets the platform TextView open the link unguarded",
            0,
            spanned.getSpans(0, spanned.length, URLSpan::class.java).size,
        )
        val links = spanned.getSpans(0, spanned.length, ClickableSpan::class.java)
        assertEquals(1, links.size)
        assertEquals(
            "open the tracker",
            spanned.subSequence(spanned.getSpanStart(links[0]), spanned.getSpanEnd(links[0])).toString(),
        )
    }
}
