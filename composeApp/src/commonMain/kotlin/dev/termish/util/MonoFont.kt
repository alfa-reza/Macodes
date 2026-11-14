package dev.termish.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.fira_code_bold
import dev.termish.generated.resources.fira_code_regular
import dev.termish.generated.resources.jetbrains_mono_bold
import dev.termish.generated.resources.jetbrains_mono_regular
import dev.termish.generated.resources.pt_mono_regular
import dev.termish.generated.resources.source_code_pro_bold
import dev.termish.generated.resources.source_code_pro_regular
import dev.termish.generated.resources.ubuntu_mono_bold
import dev.termish.generated.resources.ubuntu_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * Terminal monospace font options. Each font must include both regular and bold font weights.
 * (Bold rendering is supported in the terminal), and use a monospaced font (consistency in sizing is a prerequisite for correct rendering in the terminal).
 */
@Immutable
enum class TerminalFont(
    val id: String,
    val label: String,
) {
    JETBRAINS("jetbrains", "JetBrains Mono"),
    FIRA("fira", "Fira Code"),
    SOURCE_CODE("sourcecode", "Source Code Pro"),
    UBUNTU("ubuntu", "Ubuntu Mono"),
    PT_MONO("ptmono", "PT Mono"),
    ;

    companion object {
        fun byId(id: String): TerminalFont = entries.firstOrNull { it.id == id } ?: JETBRAINS
    }
}

/**
 * The current terminal font (provided by AppRoot based on settings). monospaceFontFamily() reads from it,
 * After toggling fonts, the entire tree (canvas/toolbar/page header) automatically undergoes reassembly.
 */
val LocalTerminalFont = staticCompositionLocalOf { TerminalFont.JETBRAINS }

/**
 * Terminal monospaced font: Use the font bundled with the app (OFL / Ubuntu Font License).
 *
 * Why you shouldn’t depend on system fonts:
 * - On Android, `FontFamily.Monospace` / `Typeface.MONOSPACE` on some OEMs
 *   (ColorOS, MIUI, EMUI, etc.) will parse fonts that are not strictly monospaced, and different device models’
 *   Inconsistent glyph widths cause terminal text and the cursor—which are drawn based on “grid width”—to be misaligned overall.
 *   (Typical issue: Works normal on the emulator, but on a physical device, the cursor moves beyond the end of the row.)
 *
 * After the font is embedded, the same font file is used to measure (perform row and column calculations) and draw on every device, ensuring consistent metrics across all Android devices.
 */
@Composable
fun monospaceFontFamily(): FontFamily {
    val font = LocalTerminalFont.current
    return when (font) {
        TerminalFont.JETBRAINS ->
            FontFamily(
                Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
                Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
            )
        TerminalFont.FIRA ->
            FontFamily(
                Font(Res.font.fira_code_regular, FontWeight.Normal),
                Font(Res.font.fira_code_bold, FontWeight.Bold),
            )
        TerminalFont.SOURCE_CODE ->
            FontFamily(
                Font(Res.font.source_code_pro_regular, FontWeight.Normal),
                Font(Res.font.source_code_pro_bold, FontWeight.Bold),
            )
        TerminalFont.UBUNTU ->
            FontFamily(
                Font(Res.font.ubuntu_mono_regular, FontWeight.Normal),
                Font(Res.font.ubuntu_mono_bold, FontWeight.Bold),
            )
        // PT Mono has only a single weight: Bold requests are synthesized from regular (Compose faux-bold), which is acceptable
        TerminalFont.PT_MONO ->
            FontFamily(
                Font(Res.font.pt_mono_regular, FontWeight.Normal),
                Font(Res.font.pt_mono_regular, FontWeight.Bold),
            )
    }
}

/**
 * Font families used for wide characters (such as Chinese and other CJK characters).
 *
 * JetBrains Mono does not include CJK glyphs. On Android, wide characters fall back to the system default family (FontFamily.Default).
 */
@Composable
expect fun cjkFontFamily(): FontFamily
