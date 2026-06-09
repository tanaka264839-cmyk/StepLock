package com.shosoku.steplock

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── データクラス ──────────────────────────────────────────

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

// ── ユーティリティ ──────────────────────────────────────────

/**
 * ランチャーに表示されるユーザーアプリ一覧を取得する（StepLock 自身を除く）。
 * IO スレッドで呼ぶこと。
 */
private fun getInstalledUserApps(pm: PackageManager): List<AppInfo> {
    val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    @Suppress("QueryPermissionsNeeded")
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != "com.shosoku.steplock" }
        .map {
            AppInfo(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                icon = it.loadIcon(pm)
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label }
        .toList()
}

/** Drawable を Compose の Image で表示するためのコンポーザブル */
@Composable
private fun AppIcon(drawable: Drawable, modifier: Modifier = Modifier) {
    val imageBitmap = remember(drawable) {
        val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        bmp.asImageBitmap()
    }
    Image(
        painter = BitmapPainter(imageBitmap),
        contentDescription = null,
        modifier = modifier
    )
}

// ── 画面本体 ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    // アプリ一覧と保存済み選択をバックグラウンドで読み込む
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { getInstalledUserApps(context.packageManager) }
        apps = list
        checked = BlockedAppsRepository.getBlockedPackages(context)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ブロックするアプリ") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← 戻る") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            BlockedAppsRepository.saveBlockedPackages(context, checked)
                            onBack()
                        }
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        }
    ) { innerPadding ->

        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.padding(innerPadding)) {

                // 選択件数バッジ
                if (checked.isNotEmpty()) {
                    Text(
                        text = "${checked.size} 件選択中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                LazyColumn {
                    items(apps, key = { it.packageName }) { app ->
                        val isChecked = app.packageName in checked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checked = if (isChecked) checked - app.packageName
                                    else checked + app.packageName
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                drawable = app.icon,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = app.label,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + app.packageName
                                    else checked - app.packageName
                                }
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
