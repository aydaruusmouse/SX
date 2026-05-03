package com.sarif.auto.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

data class UssdStepEvent(
    val stepIndex: Int,
    val isFailure: Boolean,
    val body: String,
    val requestOpener: String,
    val simLabel: String
)

object UssdStateObserver {
    private val _balanceUpdates = MutableSharedFlow<String>(replay = 1)
    val balanceUpdates: SharedFlow<String> = _balanceUpdates.asSharedFlow()

    private val _ussdBusy = MutableStateFlow(false)
    val ussdBusy: StateFlow<Boolean> = _ussdBusy.asStateFlow()

    private val _backoffActive = MutableStateFlow(false)
    val backoffActive: StateFlow<Boolean> = _backoffActive.asStateFlow()

    private val _ussdSteps = MutableSharedFlow<UssdStepEvent>(extraBufferCapacity = 10)
    val ussdSteps: SharedFlow<UssdStepEvent> = _ussdSteps.asSharedFlow()

    suspend fun emitBalance(balancePlain: String) {
        _balanceUpdates.emit(balancePlain)
    }

    suspend fun emitBusy(busy: Boolean) {
        _ussdBusy.value = busy
    }

    suspend fun emitBackoff(active: Boolean) {
        _backoffActive.value = active
    }

    suspend fun emitStep(event: UssdStepEvent) {
        _ussdSteps.emit(event)
    }
}
