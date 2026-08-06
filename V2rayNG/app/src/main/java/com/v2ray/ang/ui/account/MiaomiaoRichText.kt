package com.v2ray.ang.ui.account

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

@Composable
fun MiaomiaoRichText(
    source: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textColor = color.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                includeFontPadding = false
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.12f)
            }
        },
        update = { view ->
            view.text = HtmlCompat.fromHtml(
                MiaomiaoRichTextRenderer.toHtml(source),
                HtmlCompat.FROM_HTML_MODE_COMPACT,
            )
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
        },
    )
}

internal object MiaomiaoRichTextRenderer {
    private val unsafeBlock = Regex(
        "<(script|style|iframe|object|embed)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val unsafeLink = Regex(
        "href\\s*=\\s*(['\"])(?!https://)[^'\"]*\\1",
        RegexOption.IGNORE_CASE,
    )
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val bullet = Regex("^[-*+]\\s+(.+)$")
    private val markdownImage = Regex("!\\[([^]]*)]\\(https://[^)]+\\)")
    private val markdownLink = Regex("\\[([^]]+)]\\((https://[^)]+)\\)")
    private val boldAsterisk = Regex("\\*\\*(.+?)\\*\\*")
    private val boldUnderscore = Regex("__(.+?)__")
    private val inlineCode = Regex("`([^`]+)`")

    fun toHtml(source: String?): String {
        val safe = unsafeLink.replace(unsafeBlock.replace(source.orEmpty(), ""), "")
        return safe.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> "<br>"
                    heading.matches(trimmed) -> {
                        val match = heading.matchEntire(trimmed)!!
                        val level = match.groupValues[1].length
                        "<h$level>${inlineMarkdown(match.groupValues[2])}</h$level>"
                    }
                    bullet.matches(trimmed) -> {
                        val item = bullet.matchEntire(trimmed)!!.groupValues[1]
                        "<p>• ${inlineMarkdown(item)}</p>"
                    }
                    else -> "${inlineMarkdown(trimmed)}<br>"
                }
            }
    }

    private fun inlineMarkdown(value: String): String {
        var result = markdownImage.replace(value, "$1")
        result = markdownLink.replace(result, "<a href=\"$2\">$1</a>")
        result = boldAsterisk.replace(result, "<b>$1</b>")
        result = boldUnderscore.replace(result, "<b>$1</b>")
        return inlineCode.replace(result, "<code>$1</code>")
    }
}
