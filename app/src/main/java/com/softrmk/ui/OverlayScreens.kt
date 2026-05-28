package com.softrmk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.softrmk.PairingDialogState
import com.softrmk.R
import com.softrmk.discovery.DiscoveredBroker

@Composable
fun PairingDialog(
    state: PairingDialogState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onBrokerSelected: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = UiPalette.OverlayBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(UiPalette.OverlayContentBackground)
        ) {
            OverlayHeader(stringResource(R.string.pairing_title))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(state.statusText, color = UiPalette.SecondaryText)
                if (state.discovering || state.submitting) {
                    Text(
                        text = if (state.discovering) {
                            stringResource(R.string.pairing_searching)
                        } else {
                            stringResource(R.string.pairing_submitting)
                        },
                        color = UiPalette.PrimaryText
                    )
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.brokers) { broker ->
                        BrokerRow(
                            broker = broker,
                            selected = broker.targetId == state.selectedTargetId,
                            onSelect = { onBrokerSelected(broker.targetId) }
                        )
                        HorizontalDivider(color = UiPalette.Divider)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !state.submitting,
                            colors = whiteTextButtonColors()
                        ) {
                            Text(stringResource(R.string.pairing_close))
                        }
                        TextButton(
                            onClick = onRefresh,
                            enabled = !state.discovering && !state.submitting,
                            colors = whiteTextButtonColors()
                        ) {
                            Text(stringResource(R.string.pairing_refresh))
                        }
                        Button(
                            onClick = onConfirm,
                            enabled = state.selectedTargetId != null && !state.discovering && !state.submitting,
                            colors = whiteButtonColors()
                        ) {
                            Text(stringResource(R.string.pairing_confirm))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StartupNotice(
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = UiPalette.OverlayBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(UiPalette.OverlayContentBackground)
                .padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, shape = RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.startup_notice_series),
                    color = Color.Black,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
                Text(
                    text = stringResource(R.string.startup_notice_axiom),
                    color = Color.Black,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = whiteButtonColors()
                    ) {
                        Text(stringResource(R.string.startup_notice_ok))
                    }
                }
            }
        }
    }
}

@Composable
fun LogDialog(
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = UiPalette.OverlayBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(UiPalette.OverlayContentBackground)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiPalette.PanelBackground)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.log_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss,
                    colors = whiteTextButtonColors()
                ) {
                    Text(stringResource(R.string.pairing_close))
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PrivacyPolicyDialog(
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = UiPalette.OverlayBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(UiPalette.OverlayContentBackground)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiPalette.PanelBackground)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.privacy_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss,
                    colors = whiteTextButtonColors()
                ) {
                    Text(stringResource(R.string.pairing_close))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.privacy_intro),
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    )
                }
                item { PrivacySection(R.string.privacy_developer_heading, R.string.privacy_developer_body) }
                item { PrivacySection(R.string.privacy_summary_heading, R.string.privacy_summary_body) }
                item { PrivacySection(R.string.privacy_uses_heading, R.string.privacy_uses_body) }
                item { PrivacySection(R.string.privacy_storage_heading, R.string.privacy_storage_body) }
                item { PrivacySection(R.string.privacy_sends_heading, R.string.privacy_sends_body) }
                item { PrivacySection(R.string.privacy_sharing_heading, R.string.privacy_sharing_body) }
                item { PrivacySection(R.string.privacy_retention_heading, R.string.privacy_retention_body) }
                item { PrivacySection(R.string.privacy_security_heading, R.string.privacy_security_body) }
                item { PrivacySection(R.string.privacy_children_heading, R.string.privacy_children_body) }
                item { PrivacySection(R.string.privacy_changes_heading, R.string.privacy_changes_body) }
            }
        }
    }
}

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName ?: "?"
    val versionCode = packageInfo.longVersionCode

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = UiPalette.OverlayBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(UiPalette.OverlayContentBackground)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiPalette.PanelBackground)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.about_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss,
                    colors = whiteTextButtonColors()
                ) {
                    Text(stringResource(R.string.pairing_close))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.about_app_name),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.about_copyright),
                        color = UiPalette.SecondaryText,
                        fontSize = 14.sp
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.about_contact),
                        color = UiPalette.SecondaryText,
                        fontSize = 14.sp
                    )
                }
                item {
                    Text(
                        text = stringResource(
                            R.string.about_version,
                            versionName,
                            versionCode
                        ),
                        color = UiPalette.SecondaryText,
                        fontSize = 14.sp
                    )
                }
                item { PrivacySection(R.string.about_repo_heading, R.string.about_repo_body) }
                item { PrivacySection(R.string.about_license_heading, R.string.about_license_body) }
                item { PrivacySection(R.string.about_disclaimer_heading, R.string.about_disclaimer_body) }
            }
        }
    }
}

@Composable
private fun OverlayHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UiPalette.PanelBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PrivacySection(
    headingRes: Int,
    bodyRes: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(headingRes),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(bodyRes),
            color = UiPalette.SecondaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun BrokerRow(
    broker: DiscoveredBroker,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color.White,
                unselectedColor = UiPalette.RadioUnselected,
                disabledSelectedColor = UiPalette.RadioDisabledSelected,
                disabledUnselectedColor = UiPalette.RadioDisabledUnselected
            )
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("${broker.displayName} (${broker.ipAddresses.firstOrNull() ?: broker.brokerUrl})", color = Color.White)
        }
    }
}
