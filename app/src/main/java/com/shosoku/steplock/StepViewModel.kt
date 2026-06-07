package com.shosoku.steplock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StepUiState(
    val stepCount: Int = 0,
    val remainingSteps: Int = 100,
    val minutesUnlocked: Int = 0,
    val remainingSeconds: Int = 0,   // カウントダウン残り秒数
    val isUnlocked: Boolean = false  // true = 解放中
)

class StepViewModel : ViewModel() {

    // 獲得済み時間の残り秒数（内部管理）
    private val _remainingSeconds = MutableStateFlow(0)

    // 前回チェック時に何分獲得していたか（増分検知用）
    private var prevMinutesEarned = 0

    init {
        // 歩数を監視 → 新たに100歩達成するたびに60秒追加
        viewModelScope.launch {
            StepCounterService.stepCount.collect { steps ->
                val currentMinutes = steps / 100
                if (currentMinutes > prevMinutesEarned) {
                    val newMinutes = currentMinutes - prevMinutesEarned
                    _remainingSeconds.update { it + newMinutes * 60 }
                    prevMinutesEarned = currentMinutes
                }
            }
        }

        // 1秒ごとにカウントダウン
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                _remainingSeconds.update { maxOf(0, it - 1) }
            }
        }
    }

    val uiState: StateFlow<StepUiState> = combine(
        StepCounterService.stepCount,
        _remainingSeconds
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
