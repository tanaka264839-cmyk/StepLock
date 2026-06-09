package com.shosoku.steplock

import android.content.Context
import android.content.SharedPreferences

/**
 * ブロック対象アプリのパッケージ名を SharedPreferences で永続化し、
 * AccessibilityService の restrictedPackages と同期するリポジトリ。
 */
object BlockedAppsRepository {

    private const val PREFS_NAME = "steplock_prefs"
    private const val KEY_BLOCKED = "blocked_packages"

    /** 保存済みのブロック対象パッケージ名セットを返す（未設定なら空セット） */
    fun getBlockedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED, null)?.toSet() ?: emptySet()

    /**
     * 新しいセットを保存し、AccessibilityService が起動中なら即時反映する。
     */
    fun saveBlockedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_BLOCKED, packages).apply()
        // サービス起動中なら companion object に即時反映
        StepLockAccessibilityService.restrictedPackages.apply {
            clear()
            addAll(packages)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
