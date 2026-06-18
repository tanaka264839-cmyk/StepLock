package com.shosoku.steplock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    // デバイス起動からの累計歩数の初期値（アプリ起動時に記録）
    private var initialSteps = -1

    // サービスのコルーチンスコープ（サービスが生きている間ずっと動く）
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "step_counter_channel"
        private const val NOTIFICATION_ID = 1

        // SharedPreferences キー（Bug3：サービス再起動時のデータ復元用）
        private const val PREFS_NAME = "StepLockPrefs"
        private const val KEY_INITIAL_STEPS = "initial_steps"
        private const val KEY_STEP_COUNT = "step_count"
        private const val KEY_REMAINING_SECONDS = "remaining_seconds"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"

        // ServiceとViewModelが共有するStateFlow
        private val _stepCount = MutableStateFlow(0)
        val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

        // 残り秒数（Serviceが管理・ViewModelは表示のみ）
        private val _remainingSeconds = MutableStateFlow(0)
        val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

        // Bug2：制限アプリ使用中フラグ（タイマー消費をコントロール）
        private val _isRestrictedAppActive = MutableStateFlow(false)
        val isRestrictedAppActive: StateFlow<Boolean> = _isRestrictedAppActive.asStateFlow()

        fun setRestrictedAppActive(active: Boolean) {
            _isRestrictedAppActive.value = active
        }

        // エミュレータテスト用：歩数を直接加算する
        fun addTestSteps(steps: Int) {
            val oldCount = _stepCount.value
            val newCount = oldCount + steps
            _stepCount.value = newCount
            // 100歩ごとに60秒追加
            val oldMinutes = oldCount / 100
            val newMinutes = newCount / 100
            if (newMinutes > oldMinutes) {
                _remainingSeconds.value += (newMinutes - oldMinutes) * 60
                Log.d("StepLockService", "🦶 +${steps}歩 → +${(newMinutes - oldMinutes) * 60}秒 (残り${_remainingSeconds.value}秒)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Bug3：SharedPreferencesからデータを復元（または日付変わりでリセット）
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val savedDate = prefs.getString(KEY_LAST_RESET_DATE, "")

        if (savedDate != today) {
            // 日付が変わった → 全リセット
            prefs.edit()
                .putString(KEY_LAST_RESET_DATE, today)
                .putInt(KEY_INITIAL_STEPS, -1)
                .putInt(KEY_STEP_COUNT, 0)
                .putInt(KEY_REMAINING_SECONDS, 0)
                .apply()
            initialSteps = -1
            _stepCount.value = 0
            _remainingSeconds.value = 0
        } else {
            // 同日の再起動 → 保存値を復元
            initialSteps = prefs.getInt(KEY_INITIAL_STEPS, -1)
            _stepCount.value = prefs.getInt(KEY_STEP_COUNT, 0)
            _remainingSeconds.value = prefs.getInt(KEY_REMAINING_SECONDS, 0)
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        // カウントダウンをServiceで管理（ViewModelではなくServiceで動かすことで
        // アプリがバックグラウンドになっても正確に動き続ける）
        startCountdown()
    }

    private fun startCountdown() {
        serviceScope.launch {
            while (true) {
                delay(1000L)
                val before = _remainingSeconds.value
                if (before > 0 && _isRestrictedAppActive.value) {
                    val after = before - 1
                    _remainingSeconds.value = after
                    Log.d("StepLockService", "⏱ countdown: ${after}秒")
                    if (after == 0) {
                        Log.d("StepLockService", "⏰ タイマー0到達 → recheckForeground呼び出し")
                        StepLockAccessibilityService.instance?.recheckForeground()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val totalSteps = event.values[0].toInt()

        if (initialSteps == -1) {
            initialSteps = totalSteps
        }

        val oldCount = _stepCount.value
        val newCount = totalSteps - initialSteps
        _stepCount.value = newCount

        // 100歩ごとに60秒追加（実機用）
        val oldMinutes = oldCount / 100
        val newMinutes = newCount / 100
        if (newMinutes > oldMinutes) {
            _remainingSeconds.value += (newMinutes - oldMinutes) * 60
            Log.d("StepLockService", "🦶 実機: +${(newMinutes - oldMinutes) * 60}秒 (残り${_remainingSeconds.value}秒)")
        }

        // Bug3：データを永続化（サービス再起動時に復元できるようにする）
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INITIAL_STEPS, initialSteps)
            .putInt(KEY_STEP_COUNT, newCount)
            .putInt(KEY_REMAINING_SECONDS, _remainingSeconds.value)
            .apply()

        updateNotification(newCount)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        sensorManager.unregisterListener(this)
    }

    private fun buildNotification(steps: Int): android.app.Notification {
        val remaining = if (steps % 100 == 0 && steps > 0) 0 else 100 - (steps % 100)
        val minutes = steps / 100

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StepLock 計測中")
            .setContentText("歩数：${steps}歩 ／ あと${remaining}歩で1分 ／ 獲得：${minutes}分")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(steps: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(steps))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "StepLock 歩数計測",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "StepLockがバックグラウンドで歩数を計測しています"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
