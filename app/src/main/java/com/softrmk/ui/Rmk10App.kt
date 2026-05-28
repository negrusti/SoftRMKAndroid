package com.softrmk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OfflineShare
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.softrmk.MainUiState
import com.softrmk.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Rmk10App(
    uiState: MainUiState,
    onDismissStartupNotice: () -> Unit,
    onPairClick: () -> Unit,
    onDismissDialog: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onBrokerSelected: (String) -> Unit,
    onConfirmPair: () -> Unit,
    onTapKey: (String) -> Unit,
    onStartHold: (String) -> Unit,
    onStopHold: () -> Unit,
    onRotaryStep: (Int) -> Unit,
) {
    MaterialTheme {
        var showMenu by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var showPrivacyPolicy by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = UiPalette.AppBackground
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = UiPalette.PanelBackground,
                            titleContentColor = UiPalette.PrimaryText,
                            navigationIconContentColor = UiPalette.PrimaryText
                        ),
                        title = {
                            Text(
                                text = uiState.selectedMfdText.ifBlank { " " },
                                color = UiPalette.PrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        Icons.Filled.Menu,
                                        contentDescription = stringResource(R.string.menu_content_description),
                                        tint = UiPalette.PrimaryText
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_pair)) },
                                        onClick = {
                                            showMenu = false
                                            onPairClick()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_log)) },
                                        onClick = {
                                            showMenu = false
                                            showLog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_privacy_policy)) },
                                        onClick = {
                                            showMenu = false
                                            showPrivacyPolicy = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_about)) },
                                        onClick = {
                                            showMenu = false
                                            showAbout = true
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ControlSurface(
                        modifier = Modifier.weight(1f),
                        onTapKey = onTapKey,
                        onStartHold = onStartHold,
                        onStopHold = onStopHold,
                        onRotaryStep = onRotaryStep
                    )
                }
            }

            if (uiState.pairingDialog.visible) {
                PairingDialog(
                    state = uiState.pairingDialog,
                    onDismiss = onDismissDialog,
                    onRefresh = onRefreshDiscovery,
                    onBrokerSelected = onBrokerSelected,
                    onConfirm = onConfirmPair,
                )
            }

            if (showLog) {
                LogDialog(
                    lines = uiState.statusLines,
                    onDismiss = { showLog = false }
                )
            }

            if (showPrivacyPolicy) {
                PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
            }

            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }

            if (uiState.startupNoticeVisible) {
                StartupNotice(onDismiss = onDismissStartupNotice)
            }
        }
    }
}

@Composable
private fun ControlSurface(
    modifier: Modifier,
    onTapKey: (String) -> Unit,
    onStartHold: (String) -> Unit,
    onStopHold: () -> Unit,
    onRotaryStep: (Int) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ControlRow {
            WeightedTile(1f) { IconTile("zoom-out", Modifier.fillMaxSize(), onTapKey, Icons.Filled.Remove, 48.dp) }
            WeightedTile(1f) { IconTile("zoom-in", Modifier.fillMaxSize(), onTapKey, Icons.Filled.Add, 48.dp) }
            WeightedTile(1f) { IconTile("waypoint", Modifier.fillMaxSize(), onTapKey, Icons.Filled.Flag, 42.dp) }
        }
        ControlRow {
            WeightedTile(1f) { IconTile("home", Modifier.fillMaxSize(), onTapKey, Icons.Filled.Home, 42.dp) }
            WeightedTile(1f) { IconTile("menu", Modifier.fillMaxSize(), onTapKey, Icons.Filled.Menu, 42.dp) }
            WeightedTile(1f) { IconTile("back", Modifier.fillMaxSize(), onTapKey, Icons.Filled.ArrowBack, 42.dp) }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.95f)
        ) {
            val edgeSwipeWidth = 56.dp
            val edgeInset = 12.dp

            CenterPad(
                modifier = Modifier.fillMaxSize(),
                onTapCenter = { onTapKey("ok") },
                onStartHold = onStartHold,
                onStopHold = onStopHold,
            )
            VerticalSwipeZone(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = -edgeInset)
                    .width(edgeSwipeWidth + edgeInset)
                    .fillMaxHeight(),
                touchSlop = LocalViewConfiguration.current.touchSlop,
                onRotaryStep = onRotaryStep
            )
            VerticalSwipeZone(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = edgeInset)
                    .width(edgeSwipeWidth + edgeInset)
                    .fillMaxHeight(),
                touchSlop = LocalViewConfiguration.current.touchSlop,
                onRotaryStep = onRotaryStep
            )
        }
        ControlRow {
            WeightedTile(1f) { IconTile("power", Modifier.fillMaxSize(), onTapKey, Icons.Filled.PowerSettingsNew, 42.dp) }
            WeightedTile(1f) { IconTile("window", Modifier.fillMaxSize(), onTapKey, Icons.Filled.OfflineShare, 42.dp) }
            WeightedTile(1f) {
                KeyTile(
                    label = stringResource(R.string.control_stby_auto),
                    keyName = "pilot",
                    modifier = Modifier.fillMaxSize(),
                    onTapKey = onTapKey,
                    accent = UiPalette.DangerAccent
                )
            }
        }
    }
}

@Composable
private fun ControlRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun RowScope.WeightedTile(weight: Float, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .weight(weight)
            .aspectRatio(1f),
        content = content
    )
}

@Composable
private fun KeyTile(
    label: String,
    keyName: String,
    modifier: Modifier,
    onTapKey: (String) -> Unit,
    accent: Color = UiPalette.PrimaryText,
) {
    Box(
        modifier = modifier
            .background(brush = UiBrushes.Tile, shape = RoundedCornerShape(UiShapes.TileCorner))
            .border(1.dp, Color(0xFF434447), RoundedCornerShape(UiShapes.TileCorner))
            .clickable { onTapKey(keyName) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = accent,
            textAlign = TextAlign.Center,
            fontWeight = if (label == "+" || label == "-") FontWeight.Light else FontWeight.SemiBold,
            fontSize = when {
                label == "+" || label == "-" -> 38.sp
                label.length <= 2 -> 30.sp
                label.length <= 6 -> 20.sp
                else -> 17.sp
            }
        )
    }
}

@Composable
private fun IconTile(
    keyName: String,
    modifier: Modifier,
    onTapKey: (String) -> Unit,
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    iconSize: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = modifier
            .background(brush = UiBrushes.Tile, shape = RoundedCornerShape(UiShapes.TileCorner))
            .border(1.dp, Color(0xFF434447), RoundedCornerShape(UiShapes.TileCorner))
            .clickable { onTapKey(keyName) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}
