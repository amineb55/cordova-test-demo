package com.actionverite.live.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.GoldPack
import com.actionverite.live.domain.repository.EconomyRepository
import com.actionverite.live.domain.repository.RemoteConfigRepository
import com.actionverite.live.domain.usecase.economy.GoldPackCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of the Gold wallet / store screen rendered by [WalletScreen]. */
data class WalletUiState(
    /** Realtime Gold balance for the signed-in user. */
    val balance: Long = 0L,
    /** Purchasable Gold packs, sourced from server-driven Remote Config. */
    val packs: List<GoldPack> = emptyList(),
    /** Initial load of balance + config still in flight. */
    val isLoading: Boolean = true,
    /** A reward/purchase action is in flight; disables the action buttons. */
    val isProcessing: Boolean = false,
    /** A recoverable error to surface in a snackbar (null when none). */
    val error: String? = null,
    /** A transient success message to surface in a snackbar, e.g. "+4 Gold". */
    val message: String? = null,
)

/**
 * Drives the Gold wallet / store screen.
 *
 * Wallet mutations are server-authoritative: the balance is observed from
 * [EconomyRepository.observeBalance] and only ever changes as a side effect of a
 * Cloud Function validating the request (anti-cheat). Purchasable packs come from
 * [RemoteConfigRepository] so prices/rewards can change without an app update.
 *
 * The combined balance + packs feed a single [uiState] via [stateIn]; transient
 * UI signals (loading-on-action, success/error messages) are held in a separate
 * mutable flow and merged in, mirroring the canonical Profile MVVM template.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val economyRepository: EconomyRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val goldPackCalculator: GoldPackCalculator,
) : ViewModel() {

    /** Transient, action-driven UI signals merged into [uiState]. */
    private data class Transient(
        val isProcessing: Boolean = false,
        val error: String? = null,
        val message: String? = null,
    )

    private val transient = MutableStateFlow(Transient())

    /** The GoldPackCalculator is exposed so the stateless screen can compute bonus badges. */
    val packCalculator: GoldPackCalculator get() = goldPackCalculator

    /** Current config snapshot, used by reward/purchase actions and bonus badges. */
    fun currentConfig() = remoteConfigRepository.current()

    val uiState: StateFlow<WalletUiState> = combine(
        economyRepository.observeBalance(),
        remoteConfigRepository.observeConfig(),
        transient,
    ) { balance, config, t ->
        WalletUiState(
            balance = balance,
            packs = config.gold.packs,
            isLoading = false,
            isProcessing = t.isProcessing,
            error = t.error,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletUiState())

    /**
     * Grants Gold for a watched rewarded video. The real Ad SSV (Server-Side
     * Verification) token is produced by the rewarded-ad SDK on completion and
     * verified server-side; until that SDK is wired up we pass a placeholder.
     */
    fun watchRewardedVideo() {
        viewModelScope.launch {
            transient.update { it.copy(isProcessing = true, error = null, message = null) }
            // TODO(ads): the rewarded-ad SDK supplies the real Ad SSV token on completion.
            val result = economyRepository.grantRewardedVideo(PLACEHOLDER_AD_SSV_TOKEN)
            handleGrant(result)
        }
    }

    /**
     * Validates a Google Play Billing purchase for [pack] and credits its Gold.
     * The real purchase token is returned by the Play Billing flow; until that is
     * wired up we pass a placeholder.
     */
    fun buyPack(pack: GoldPack) {
        viewModelScope.launch {
            transient.update { it.copy(isProcessing = true, error = null, message = null) }
            // TODO(billing): Google Play Billing supplies the real purchase token.
            val result = economyRepository.purchasePack(pack.id, PLACEHOLDER_PURCHASE_TOKEN)
            handleGrant(result)
        }
    }

    /** Maps a balance-mutation result into a snackbar message or error. */
    private fun handleGrant(result: DomainResult<Long>) {
        when (result) {
            is DomainResult.Success -> transient.update {
                it.copy(
                    isProcessing = false,
                    message = "Balance updated: ${result.data} Gold",
                    error = null,
                )
            }

            is DomainResult.Failure -> transient.update {
                it.copy(
                    isProcessing = false,
                    error = result.error.message ?: "Something went wrong.",
                    message = null,
                )
            }
        }
    }

    /** Clears the one-shot success message after it has been shown. */
    fun onMessageShown() {
        transient.update { it.copy(message = null) }
    }

    /** Clears the one-shot error after it has been shown. */
    fun onErrorShown() {
        transient.update { it.copy(error = null) }
    }

    private companion object {
        /** Stand-in for the Ad SSV token until the rewarded-ad SDK is integrated. */
        const val PLACEHOLDER_AD_SSV_TOKEN = "placeholder-ad-ssv-token"

        /** Stand-in for the Play Billing purchase token until billing is integrated. */
        const val PLACEHOLDER_PURCHASE_TOKEN = "placeholder-purchase-token"
    }
}
