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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    // デバイス起動からの累計歩数の初期値（アプリ起動時に記録）
    private var initialSteps = -1

    companion object {
        private const val CHANNEL_ID = "step_counter_channel"
        private const val NOTIFICATION_ID = 1

        // ServiceとViewModelが共有するStateFlow
        private val _stepCount = MutableStateFlow(0)
        val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

        // AccessibilityServiceから参照する残り秒数（ViewModelが更新）
        private val _remainingSeconds = MutableStateFlow(0)
        val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

        fun updateRemainingSeconds(seconds: Int) {
            _remainingSeconds.value = seconds
        }

        // エミュレータテスト用：歩数を直接加算する
        fun addTestSteps(steps: Int) {
            _stepCount.value += steps
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createNotificationChannel()
        // ForegroundServiceとして通知を出す（Android 8以上で必須）
        startForeground(NOTIFICATION_ID, buildNotification(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // STARTがkillされてもOSが再起動してくれる
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val totalSteps = event.values[0].toInt()

        // 初回イベントでアプリ起動時点の累計歩数を記録
        if (initialSteps == -1) {
            initialSteps = totalSteps
        }

        // 「今日」ではなく「このアプリを使い始めてからの歩数」をMVPとして計測
        val steps = totalSteps - initialSteps
        _stepCount.value = steps
        updateNotification(steps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 今回は使わない
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
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
            .setOngoing(true)  // スワイプで消せない
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
            NotificationManager.IMPORTANCE_LOW  // LOW = サウンドなし・常駐向け
        ).apply {
            description = "StepLockがバックグラウンドで歩数を計測しています"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
