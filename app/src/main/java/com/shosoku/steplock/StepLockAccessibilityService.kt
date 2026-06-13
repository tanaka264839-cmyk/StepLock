package com.shosoku.steplock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StepLockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var blockingView: View? = null

    // 現在ブロック中のパッケージ名
    private var currentlyBlockedPackage: String? = null

    // 直近でフォアグラウンドにいたパッケージ名（タイムアウト時の再チェック用）
    private var lastForegroundPackage: String? = null

    // remainingSeconds監視用スコープ
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        // 制限対象アプリのパッケージ名セット（MainActivityから設定）
        val restrictedPackages = mutableSetOf<String>(
            // デフォルト：テスト用にYouTubeを追加
            "com.google.android.youtube"
        )

        // AccessibilityServiceのインスタンス（シングルトン的に参照）
        var instance: StepLockAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // SharedPreferences に保存済みがあれば上書きして反映（なければデフォルトの YouTube を維持）
        val saved = BlockedAppsRepository.getBlockedPackages(this)
        if (saved.isNotEmpty()) {
            restrictedPackages.clear()
            restrictedPackages.addAll(saved)
        }
        Log.d("StepLockA11y", "✅ onServiceConnected: サービス起動完了 packages=$restrictedPackages")

        // remainingSeconds を監視：0→正数になった瞬間にブロック画面を自動解除
        serviceScope.launch {
            var prevSeconds = StepCounterService.remainingSeconds.value
            StepCounterService.remainingSeconds.collect { seconds ->
                if (prevSeconds == 0 && seconds > 0 && blockingView != null) {
                    Log.d("StepLockA11y", "🔓 タイマー復活(${seconds}秒) → ブロック画面を自動解除")
                    dismissBlockScreen()
                }
                prevSeconds = seconds
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d("StepLockA11y", "📡 event: type=${event.eventType} pkg=${event.packageName}")

        // TYPE_WINDOWS_CHANGED はここでは何もしない。
        // ホームキー / タスク切替は onKeyEvent() が先行検知して解除する。
        // オーバーレイ追加時に TYPE_WINDOWS_CHANGED が発火しても誤dismissしないようにするため。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        Log.d("StepLockA11y", "🔍 window changed: $packageName")

        // システムUIは無視
        if (packageName == "com.android.systemui") return

        // ブロック画面表示中は com.shosoku.steplock のイベントを無視（オーバーレイの誤検知対策）
        if (packageName == "com.shosoku.steplock" && blockingView != null) return

        // フォアグラウンドパッケージを更新（タイムアウト時の再チェック用）
        lastForegroundPackage = packageName

        val isRestricted = restrictedPackages.contains(packageName)
        val isUnlocked = StepCounterService.remainingSeconds.value > 0

        when {
            isRestricted && !isUnlocked -> {
                // 制限対象 かつ 残り時間0 → ブロック
                // バックグラウンドアプリから発火したイベントで誤表示しないよう、
                // ウィンドウリストで「実際にフォアグラウンドにいるか」を確認してから表示する。
                // API34以上: win.packageName を直接使う（win.root?.packageName は不安定）
                // API33以下: win.root?.packageName を使い、失敗時はブロックする（保守的）
                if (blockingView == null) {
                    val isActuallyForeground = windows?.any { win ->
                        win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        win.isActive &&
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            win.packageName?.toString() == packageName
                        } else {
                            try { win.root?.packageName?.toString() == packageName } catch (_: Exception) { true }
                        }
                    } ?: true  // windowsがnull = 取得失敗 → 保守的にブロックする
                    if (isActuallyForeground) showBlockScreen(packageName)
                }
            }
            isRestricted && isUnlocked -> {
                // 制限対象だが時間あり → ブロック解除
                dismissBlockScreen()
            }
            packageName == "com.android.launcher3" ||
            packageName == "com.google.android.apps.nexuslauncher" -> {
                // ホームに戻った → ブロック解除
                dismissBlockScreen()
            }
            !isRestricted && blockingView != null -> {
                // 制限対象でない別アプリが前面 → ブロック解除
                dismissBlockScreen()
            }
        }
    }

    /**
     * ホームキー・タスク切替キー押下を画面遷移より先に検知してブロック解除する。
     * AccessibilityService の onKeyEvent は PhoneWindowManager がホーム処理を開始する
     * より前に呼ばれるため、オーバーレイを1フレーム遅れなしで消せる。
     * false を返すことで通常動作（ホーム遷移等）は維持する。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        if (blockingView != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH -> {
                    Log.d("StepLockA11y", "🏠 ナビキー(${event.keyCode})検知 → 先行ブロック解除")
                    dismissBlockScreen()
                }
            }
        }
        return false
    }

    override fun onInterrupt() {
        dismissBlockScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        dismissBlockScreen()
    }

    /**
     * タイマーが0になった瞬間にViewModelから呼ばれる。
     * 現在フォアグラウンドのアプリが制限対象なら即ブロック画面を表示する。
     * API34以上: windowsリストから実際のフォアグラウンドを取得（lastForegroundPackageより正確）
     * API33以下: lastForegroundPackageにフォールバック
     */
    fun recheckForeground() {
        val seconds = StepCounterService.remainingSeconds.value
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            windows?.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
            }?.packageName?.toString() ?: lastForegroundPackage
        } else {
            lastForegroundPackage
        }
        Log.d("StepLockA11y", "🔔 recheckForeground: pkg=$pkg seconds=$seconds restricted=${restrictedPackages}")
        if (pkg == null) return
        if (restrictedPackages.contains(pkg) && seconds == 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Log.d("StepLockA11y", "🔒 showBlockScreen from recheckForeground: pkg=$pkg")
                if (blockingView == null) showBlockScreen(pkg)
            }
        }
    }

    // ブロック画面を表示
    fun showBlockScreen(packageName: String) {
        if (blockingView != null) return  // 既に表示中

        currentlyBlockedPackage = packageName

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // ブロック画面ビューを動的に生成
        val view = createBlockView()
        blockingView = view

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            blockingView = null
        }
    }

    // ブロック画面を閉じる
    fun dismissBlockScreen() {
        blockingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            blockingView = null
            currentlyBlockedPackage = null
        }
    }

    // ブロック画面のViewを生成（コードでレイアウト構築）
    private fun createBlockView(): View {
        val context = this

        // FrameLayout でフルスクリーン背景 + 中央コンテンツ
        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.argb(230, 0, 0, 0))
        }

        val inner = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 0, 64, 0)
        }

        val lockIcon = TextView(context).apply {
            text = "🔒"
            textSize = 72f
            gravity = Gravity.CENTER
        }

        val titleText = TextView(context).apply {
            text = "利用時間がなくなりました"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }

        val subText = TextView(context).apply {
            val neededSteps = 100  // 1分 = 100歩
            text = "あと $neededSteps 歩 歩くと 1分 使えます"
            textSize = 16f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val homeButton = Button(context).apply {
            text = "ホームに戻る"
            textSize = 16f
            setOnClickListener {
                dismissBlockScreen()
                // ホームに戻る
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            }
        }

        inner.addView(lockIcon)
        inner.addView(titleText)
        inner.addView(subText)
        inner.addView(homeButton)

        root.addView(
            inner,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return root
    }
}
