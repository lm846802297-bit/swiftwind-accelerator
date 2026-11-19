package com.swiftwind.accelerator.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.swiftwind.accelerator.ui.screens.HomeScreen
import com.swiftwind.accelerator.ui.screens.NodesScreen
import com.swiftwind.accelerator.ui.screens.SettingsScreen
import com.swiftwind.accelerator.ui.theme.Deep
import com.swiftwind.accelerator.ui.theme.Ink
import com.swiftwind.accelerator.ui.theme.Pink
import com.swiftwind.accelerator.ui.theme.TextPrimary
import com.swiftwind.accelerator.viewmodel.AppViewModel
import com.swiftwind.accelerator.viewmodel.UiState

@Composable
fun SwiftWindApp(
    ui: UiState,
    viewModel: AppViewModel,
    onToggleConnection: () -> Unit,
    onProbeNodes: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2B102B), Ink, Deep, Ink)
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(260),
                    label = "main-tabs"
                ) { index ->
                    when (index) {
                        0 -> HomeScreen(
                            ui = ui,
                            onToggle = onToggleConnection,
                            onOpenNodes = { tab = 1 }
                        )
                        1 -> NodesScreen(
                            ui = ui,
                            onFetchWarp = viewModel::fetchWarpNode,
                            onSelect = viewModel::selectNode,
                            onDelete = viewModel::deleteNode,
                            onImportText = viewModel::importText,
                            onAddSubscription = viewModel::addSubscription,
                            onProbe = onProbeNodes,
                            onCancelProbe = viewModel::cancelProbe
                        )
                        else -> SettingsScreen(
                            ui = ui,
                            onOpenVpnSettings = onOpenVpnSettings,
                            onOpenPrivacy = onOpenPrivacy
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color(0xFF26122D),
                        contentColor = TextPrimary,
                        actionColor = Pink
                    )
                }
            }
            BottomBar(current = tab, onSelect = { tab = it })
        }
    }
}
