package com.shosoku.steplock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StepUiState(
    val stepCount: Int = 0,
    val remainingSteps: Int = 100,
    val minutesUnlocked: Int = 0,
    val remainingSeconds: Int = 0,
    val isUnlocked: Boolean = false
)

class StepViewModel : ViewModel() {

    // カウントダウンと秒の管理はStepCounterService（フォアグラウンドサービス）が担当。
    // ViewModelはServiceのStateFlowをそのまま表示用に変換するだけ。

    val uiState: StateFlow<StepUiState> = combine(
        StepCounterService.stepCount,
        StepCounterService.remainingSeconds
    ) { steps, seconds ->
        val stepsInCycle = steps % 100
        val remaining = if (stepsInCycle == 0 && steps > 0) 100 else 100 - stepsInCycle
        StepUiState(
            stepCount = steps,
            remainingSteps = remaining,
            minutesUnlocked = steps / 100,
            remainingSeconds = seconds,
            isUnlocked = seconds > 0
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StepUiState()
    )
}
