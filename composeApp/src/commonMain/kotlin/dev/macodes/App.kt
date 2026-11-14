package dev.macodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.macodes.data.HostRepository
import dev.macodes.ui.AppRoot

@Composable
fun App() {
    val repository = remember { HostRepository() }
    AppRoot(repository)
}
