package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.TerminalTheme
import dev.macodes.ui.theme.TerminalThemes
import dev.macodes.util.TerminalFont
import dev.macodes.util.monospaceFontFamily

/**
 * Terminal Themes and Fonts Screen (Termius Style):
 * A font dropdown menu at the top, followed by a two-column card layout for themes below.
 */
@Composable
fun SettingsTerminalScreen(
    currentThemeIndex: Int,
    currentFontId: String,
    onChangeTheme: (Int) -> Unit,
    onChangeFont: (String) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var fontMenuOpen by remember { mutableStateOf(false) }
    val selectedFont = TerminalFont.byId(currentFontId)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MacodesHeader(
            title = s.settingsTerminalPageTitle,
            onBack = onBack,
        )

        // Fixed font area; theme grid scrolls independently (LazyVerticalGrid cannot be nested within verticalScroll)
        Column(Modifier.fillMaxSize()) {
            Text(
                s.settingsTerminalFontLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.Xl, top = Spacing.Md, bottom = Spacing.Sm),
            )
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corners.Md))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { fontMenuOpen = true }
                        .padding(horizontal = Spacing.Lg, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedFont.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily =
                            if (selectedFont == TerminalFont.JETBRAINS) {
                                monospaceFontFamily()
                            } else {
                                FontFamily.Monospace
                            },
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                    TerminalFont.entries.forEach { font ->
                        DropdownMenuItem(
                            text = { Text(font.label) },
                            onClick = {
                                fontMenuOpen = false
                                onChangeFont(font.id)
                            },
                        )
                    }
                }
            }

            // Theme two-column grid layout
            Text(
                s.settingsTerminalPalette,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.Xl, top = Spacing.Lg, bottom = Spacing.Sm),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(start = Spacing.Lg, end = Spacing.Lg, bottom = Spacing.Xl),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                items(TerminalThemes.ALL, key = { it.name }) { theme ->
                    ThemeCard(
                        theme = theme,
                        selected = TerminalThemes.ALL.indexOf(theme) == currentThemeIndex,
                        onClick = { onChangeTheme(TerminalThemes.ALL.indexOf(theme)) },
                    )
                }
            }
        }
    }
}

/** Theme preview card: Actual color scheme (background/foreground/16 color blocks) + name + highlight when selected. */
@Composable
private fun ThemeCard(
    theme: TerminalTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Lg))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Corners.Lg))
                } else {
                    Modifier
                },
            ).clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // Preview area: Background + sample foreground text + 16 color blocks
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Md))
                .background(theme.background())
                .padding(10.dp),
        ) {
            Text(
                "Aa 123",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = theme.foreground(),
            )
            Spacer(Modifier.height(Spacing.Sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                theme.ansi.forEach { c ->
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(c)),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            theme.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}
