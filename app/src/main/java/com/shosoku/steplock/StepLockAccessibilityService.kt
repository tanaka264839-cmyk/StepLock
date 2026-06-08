package com.shosoku.steplock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView

class StepLockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var blockingView: View? = null

    // 現在ブロック中のパッケージ名
    private var currentlyBlockedPackage: String? = null

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
        Log.d("StepLockA11y", "✅ onServiceConnected: サービス起動完了")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d("StepLockA11y", "📡 event: type=${event.eventType} pkg=${event.packageName}")
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        Log.d("StepLockA11y", "🔍 window changed: $packageName")

        // システムUIは無視
        if (packageName == "com.android.systemui") return

        // ブロック画面表示中は com.shosoku.steplock のイベントを無視（オーバーレイの誤検知対策）
        if (packageName == "com.shosoku.steplock" && blockingView != null) return

        val isRestricted = restrictedPackages.contains(packageName)
        val isUnlocked = StepCounterService.remainingSeconds.value > 0

        when {
            isRestricted && !isUnlocked -> {
                // 制限対象 かつ 残り時間0 → ブロック
                if (blockingView == null) showBlockScreen(packageName)
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

    override fun onInterrupt() {
        dismissBlockScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        dismissBlockScreen()
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
