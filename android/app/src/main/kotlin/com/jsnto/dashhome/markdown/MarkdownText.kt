package com.jsnto.dashhome.markdown

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
            // New content can be taller than the previous push; make sure
            // the view is remeasured so the text is not clipped.
            it.requestLayout()
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    )
}
