package com.jsnto.dashhome.markdown

import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders [markdown] (the Hermes push content) in a styled TextView via
 * Markwon - the mobile counterpart of the web's react-markdown panel.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spanned = remember(markdown) { Markdown.render(context, markdown) }
    // Compose packs the ARGB in the TOP 32 bits of Color.value (the low
    // 32 bits hold the color-space tag), so toArgb() - not value.toInt() -
    // yields the paint color the platform TextView needs.
    val contentColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(contentColor)
                textSize = 15f
                setLineSpacing(6f * ctx.resources.displayMetrics.density, 1.15f)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(
                    (12 * ctx.resources.displayMetrics.density).toInt(),
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    (12 * ctx.resources.displayMetrics.density).toInt(),
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                )
            }
        },
        update = {
            it.text = spanned
            it.guardLinkSpans()
            // New content can be taller than the previous push; make sure
            // the view is remeasured so the text is not clipped.
            it.requestLayout()
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    )
}

/** A markdown link that opens its URL without crashing when no app handles the scheme. */
private class SafeUrlSpan(private val url: String) : ClickableSpan() {
    override fun onClick(widget: View) {
        try {
            widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No app handles this scheme; the push feed keeps working.
        }
    }
}

/**
 * Swaps the rendered link spans (Markwon's LinkSpan is a URLSpan the
 * platform TextView would otherwise handle itself, unguarded) for
 * [SafeUrlSpan] over the same ranges, so a link tap opens the URL through
 * the guarded intent. Markwon's render output is an immutable Spanned,
 * so the text is copied (spans included) into a SpannableStringBuilder
 * first.
 */
private fun TextView.guardLinkSpans() {
    val src = text as? Spanned ?: return
    val urls = src.getSpans(0, src.length, URLSpan::class.java)
    if (urls.isEmpty()) return
    val out = SpannableStringBuilder(src)
    urls.forEach { span ->
        val start = src.getSpanStart(span)
        val end = src.getSpanEnd(span)
        out.removeSpan(span)
        out.setSpan(SafeUrlSpan(span.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    text = out
}
