package com.shosoku.steplock

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.shosoku.steplock.ui.theme.StepLockTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StepViewModel by viewModels()

    @SuppressLint("InlinedApi")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val activityGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: true
        if (activityGranted) {
            startStepService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        setContent {
            StepLockTheme {
                val uiState by viewModel.uiState.collectAsState()
                var showAppSelect by remember { mutableStateOf(false) }
                var showAccessibilityInfo by remember { mutableStateOf(false) }

                when {
                    showAppSelect -> AppSelectScreen(onBack = { showAppSelect = false })
                    showAccessibilityInfo -> AccessibilityInfoScreen(
                        onBack = { showAccessibilityInfo = false },
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                    else -> StepLockScreen(
                        uiState = uiState,
                        onAppSelectClick = { showAppSelect = true },
                        onAccessibilityInfoClick = { showAccessibilityInfo = true }
                    )
                }
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isEmpty()) {
            startStepService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startStepService() {
        val intent = Intent(this, StepCounterService::class.java)
        startForegroundService(intent)
        // ※ オーバーレイ権限は初回セットアップ時に手動で付与済みのため自動リダイレクトを廃止
    }
}

@Composable
fun StepLockScreen(
    uiState: StepUiState,
    onAppSelectClick: () -> Unit = {},
    onAccessibilityInfoClick: () -> Unit = {}
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "StepLock",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "歩かないと、開けない。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(56.dp))

            Text(
                text = "今日の歩数",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${uiState.stepCount}",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 80.sp
            )
            Text(
                text = "歩",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(40.dp))

            if (uiState.isUnlocked) {
                // 解放中カード（緑）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2E7D32)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🔓 解放中",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "残り ${uiState.remainingSeconds} 秒",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            } else {
                // 待機中カード（青）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🔒 あと",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "${uiState.remainingSteps} 歩",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "で 1分 解放",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "獲得済み：${uiState.minutesUnlocked} 分",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── アプリ選択ボタン ──
            OutlinedButton(
                onClick = onAppSelectClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔧 ブロックするアプリを選ぶ")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── アクセシビリティ機能について（権限の説明画面への入口） ──
            TextButton(
                onClick = onAccessibilityInfoClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔐 アクセシビリティ機能について")
            }

        }
    }
}

/**
 * StepLockがユーザー補助（Accessibility）サービスを使用する理由を説明する画面。
 *
 * Google Playの「ユーザー補助サービスの開示」要件に対応するため、
 * 設定へ遷移する前に、何のために・どの情報を使うのかをユーザーに明示する。
 */
@Composable
fun AccessibilityInfoScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "🔐 アクセシビリティ機能について",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "何のために使うか",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "StepLockは「ブロック対象に設定したアプリ」が今、画面の最前面で開かれているかどうかを検知するために、ユーザー補助（Accessibility）サービスを使用します。" +
                            "歩数が足りていない状態でブロック対象アプリが開かれた場合に、ブロック画面を表示するための仕組みです。",
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "取得する情報",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "現在最前面に表示されているアプリのパッケージ名（アプリの種類）のみを確認します。画面の文字情報や入力内容、操作内容そのものは取得・保存しません。",
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "情報の取り扱い",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "取得した情報は端末内のみで処理され、外部のサーバーや第三者への送信・共有は一切行いません。",
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ユーザー補助の設定を開く")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("戻る")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
