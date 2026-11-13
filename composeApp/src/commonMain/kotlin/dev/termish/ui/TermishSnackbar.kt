package dev.termish.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Global standardization of Snackbar hosts: **Brand Visual Identity** (dark zinc-gray card with corner radius + white text + brand green action button),
 * Consistent across dark and light themes; does not shift with the screen background. Project-wide tooltips (connection failure / upload/download complete /
 * (Voice input feedback, copy-paste, etc.) should all follow this style to avoid inconsistencies in default formatting across different sections.
 */
@Composable
fun TermishSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState, modifier) { data ->
        BrandSnackbar(data)
    }
}

@Composable
private fun BrandSnackbar(data: SnackbarData) {
    val brand = MaterialTheme.colorScheme.primary
    Snackbar(
        snackbarData = data,
        shape = RoundedCornerShape(14.dp),
        containerColor = Color(0xFF23262D).copy(alpha = 0.98f),
        contentColor = Color(0xFFE4E4E7),
        actionColor = brand,
    )
}
