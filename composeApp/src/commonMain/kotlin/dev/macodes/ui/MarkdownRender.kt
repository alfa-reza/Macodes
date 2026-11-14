package dev.macodes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer (pure Kotlin + AnnotatedString, zero third-party dependencies):
 * Cover common syntax—headings / code blocks / inline code / bold / italics / links / quotes /
 * Unordered and ordered lists / separators. Tables and nested syntax should remain exactly as in the source code (do not render tables).
 * For use with the "Render Mode" Markdown preview in SFTP file management; the rendered result can be copied.
 */
fun renderMarkdown(
    src: String,
    baseFontSize: TextUnit = 14.sp,
    codeBg: Color = Color(0x14000000),
    linkColor: Color = Color(0xFF1E88E5),
): AnnotatedString {
    val b = AnnotatedString.Builder()
    var inCodeBlock = false
    for (line in src.lines()) {
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                inCodeBlock = !inCodeBlock
                b.append("\n")
            }
            inCodeBlock ->
                b.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        fontSize = baseFontSize * 0.92f,
                    ),
                ) { b.append(line) }
            else -> renderBlockLine(b, line, trimmed, baseFontSize, codeBg, linkColor)
        }
        b.append("\n")
    }
    return b.toAnnotatedString()
}

private fun renderBlockLine(
    b: AnnotatedString.Builder,
    raw: String,
    trimmed: String,
    base: TextUnit,
    codeBg: Color,
    linkColor: Color,
) {
    when {
        // Heading: Levels 1–6 (the more # symbols, the smaller the font size)
        trimmed.startsWith("#") -> {
            val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
            val text = trimmed.drop(level).trimStart()
            val size = base * (1.55f - level * 0.12f)
            inline(
                b,
                text,
                base,
                codeBg,
                linkColor,
                baseStyle =
                    SpanStyle(
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }
        // Blockquote: Prefix block + italics
        trimmed.startsWith(">") -> {
            val text = trimmed.removePrefix(">").trimStart().ifBlank { " " }
            b.withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { b.append("▍ ") }
            inline(b, text, base, codeBg, linkColor, baseStyle = SpanStyle(fontStyle = FontStyle.Italic))
        }
        // Separator
        trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
            b.withStyle(SpanStyle(color = linkColor.copy(alpha = 0.4f))) { b.append("─".repeat(32)) }
        }
        // Unordered list
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
            b.withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { b.append("• ") }
            inline(b, trimmed.drop(2), base, codeBg, linkColor)
        }
        // Ordered list
        trimmed.matches(Regex("\\d+\\.\\s.*")) -> {
            val idx = trimmed.indexOf('.')
            b.withStyle(
                SpanStyle(color = linkColor, fontWeight = FontWeight.Bold),
            ) { b.append("${trimmed.substring(0, idx)}. ") }
            inline(b, trimmed.substring(idx + 1).trimStart(), base, codeBg, linkColor)
        }
        // Regular paragraph
        else -> inline(b, raw, base, codeBg, linkColor)
    }
}

/** Inline syntax: **bold** / *italics* / `inline code` / [text](link). */
private fun inline(
    b: AnnotatedString.Builder,
    text: String,
    base: TextUnit,
    codeBg: Color,
    linkColor: Color,
    baseStyle: SpanStyle = SpanStyle(),
) {
    val token = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`|\\[[^\\]]+\\]\\([^)]*\\))")
    var last = 0
    for (m in token.findAll(text)) {
        b.withStyle(baseStyle) { b.append(text.substring(last, m.range.first)) }
        val tok = m.value
        when {
            tok.startsWith(
                "**",
            ) -> b.withStyle(baseStyle + SpanStyle(fontWeight = FontWeight.Bold)) { b.append(tok.trim('*')) }
            tok.startsWith("`") ->
                b.withStyle(
                    baseStyle +
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = base * 0.92f),
                ) { b.append(tok.trim('`')) }
            tok.startsWith("[") -> {
                val inner = tok.substring(1, tok.indexOf(']'))
                b.withStyle(
                    baseStyle + SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ) { b.append(inner) }
            }
            else -> b.withStyle(baseStyle + SpanStyle(fontStyle = FontStyle.Italic)) { b.append(tok.trim('*')) }
        }
        last = m.range.last + 1
    }
    b.withStyle(baseStyle) { b.append(text.substring(last)) }
}
