package com.softrmk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.softrmk.ui.Rmk10App

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            Rmk10App(
                uiState = uiState,
                onDismissStartupNotice = viewModel::dismissStartupNotice,
                onPairClick = viewModel::openPairingDialog,
                onDismissDialog = viewModel::dismissPairingDialog,
                onRefreshDiscovery = viewModel::refreshDiscovery,
                onBrokerSelected = viewModel::selectBroker,
                onConfirmPair = viewModel::confirmPairing,
                onTapKey = viewModel::tapKey,
                onStartHold = viewModel::startHold,
                onStopHold = viewModel::stopHold,
                onRotaryStep = viewModel::sendRotary,
            )
        }
    }
}
