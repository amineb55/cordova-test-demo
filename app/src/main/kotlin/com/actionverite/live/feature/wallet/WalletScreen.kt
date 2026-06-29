package com.actionverite.live.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.GoldConfig
import com.actionverite.live.domain.model.GoldPack
import com.actionverite.live.domain.usecase.economy.GoldPackCalculator
import com.actionverite.live.ui.components.FullScreenLoading
import com.actionverite.live.ui.components.PrimaryButton

@Composable
fun WalletRoute(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WalletScreen(
        uiState = uiState,
        config = viewModel.currentConfig().gold,
        packCalculator = viewModel.packCalculator,
        onBack = onBack,
        onWatchVideo = viewModel::watchRewardedVideo,
        onBuyPack = viewModel::buyPack,
        onMessageShown = viewModel::onMessageShown,
        onErrorShown = viewModel::onErrorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletScreen(
    uiState: WalletUiState,
    config: GoldConfig,
    packCalculator: GoldPackCalculator,
    onBack: () -> Unit,
    onWatchVideo: () -> Unit,
    onBuyPack: (GoldPack) -> Unit,
    onMessageShown: () -> Unit,
    onErrorShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gold") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading) {
            FullScreenLoading(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                BalanceHeader(balance = uiState.balance)
            }

            item {
                PrimaryButton(
                    text = "Watch a video (+${config.rewardedVideoGold} Gold)",
                    onClick = onWatchVideo,
                    enabled = !uiState.isProcessing,
                )
            }

            if (uiState.packs.isNotEmpty()) {
                item {
                    Text(
                        "Buy Gold",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            items(uiState.packs, key = { it.id }) { pack ->
                PackCard(
                    pack = pack,
                    bonusGold = packCalculator.bonusGold(pack, config),
                    enabled = !uiState.isProcessing,
                    onBuy = { onBuyPack(pack) },
                )
            }
        }
    }
}

@Composable
private fun BalanceHeader(balance: Long) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your balance", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "$balance Gold",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PackCard(
    pack: GoldPack,
    bonusGold: Long,
    enabled: Boolean,
    onBuy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("${pack.gold} Gold", style = MaterialTheme.typography.titleLarge)
                    Text(
                        formatPrice(pack.priceUsd),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (bonusGold > 0) {
                    BonusBadge(bonusGold = bonusGold)
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "Buy ${formatPrice(pack.priceUsd)}",
                onClick = onBuy,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun BonusBadge(bonusGold: Long) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "+$bonusGold bonus",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Formats a USD price like "$1.00" without pulling in a locale-specific currency formatter. */
private fun formatPrice(priceUsd: Double): String = "$" + String.format("%.2f", priceUsd)
