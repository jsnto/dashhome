package com.jsnto.dashhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiException

/**
 * The web's `<Note>`: a one-line setup/error note with an optional code
 * prefix. [color] defaults to the surface-variant tone; error notes use
 * the error color.
 */
@Composable
fun Note(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Error note from an [ApiException]: "code — detail". */
@Composable
fun ErrorNote(err: ApiException, modifier: Modifier = Modifier) {
    val text = if (err.detail.isNullOrBlank()) err.code else "${err.code} — ${err.detail}"
    Note(text = text, color = MaterialTheme.colorScheme.error, modifier = modifier)
}

/**
 * A small tab chip (the web's `.tab` / `.tab on`), used for the calendar
 * tabs, the push-history tabs, and the settings section nav. Carries the
 * selected state in semantics (the web's aria-pressed), so a screen
 * reader - and the tests - can see the active tab.
 */
@Composable
fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSel = selected
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { this[SemanticsProperties.Selected] = isSel },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A dimmed single-line ellipsized chip (calendar day cells). */
@Composable
fun EventChip(
    label: String,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = background,
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF0B1220),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** A colored calendar-label chip used in event detail rows. */
@Composable
fun CalendarLabelChip(label: String, background: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = background,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF0B1220),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Centered loading indicator row. */
@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A day-cell background: primary-tinted when today or selected. */
@Composable
fun dayCellBackground(modifier: Modifier, today: Boolean, selected: Boolean): Modifier =
    modifier.background(
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
    )
