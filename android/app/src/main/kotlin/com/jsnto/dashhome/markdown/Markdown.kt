package com.jsnto.dashhome.markdown

import android.content.Context
import android.text.Spanned
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

/**
 * Markdown rendering for the Hermes push feed. Mirrors the web: pushed
 * markdown is rendered without raw-HTML support, so pushed content cannot
 * inject HTML (Markwon has no HTML extension enabled).
 */
object Markdown {

    private val cache = HashMap<Context, Markwon>()

    @Synchronized
    fun renderer(context: Context): Markwon =
        cache.getOrPut(context) {
            Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(StrikethroughPlugin.create())
                .build()
        }

    fun render(context: Context, markdown: String): Spanned =
        renderer(context).toMarkdown(markdown)
}
