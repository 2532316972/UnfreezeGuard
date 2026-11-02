package com.unfreezeguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1B6E5A))) {
                GuardApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var rootOk by remember { mutableStateOf<Boolean?>(null) }   // null=检测中
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var running by remember { mutableStateOf(GuardService.isRunning) }
    var status by remember { mutableStateOf(if (GuardService.isRunning) "守护运行中" else "未运行") }
    var query by remember { mutableStateOf("") }
    var frozenInfo by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        rootOk = withContext(Dispatchers.IO) {
            RootShell.init()
            RootShell.ok
        }
        selected = PkgStore.load(ctx)
        apps = AppListModel.load(ctx)
    }

    DisposableEffect(Unit) {
        GuardService.statusListener = { text ->
            status = text
            running = GuardService.isRunning
        }
        onDispose { GuardService.statusListener = null }
    }

    fun toggleGuard() {
        if (running) {
            ctx.startService(
                Intent(ctx, GuardService::class.java).setAction(GuardService.ACTION_STOP)
            )
        } else {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, GuardService::class.java).setAction(GuardService.ACTION_START)
            )
            running = true
            status = "守护启动中…"
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("防冻结守护") }) }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (rootOk) {
                        null -> "Root 状态：检测中…（首次会弹出授权，请允许）"
                        true -> "Root 状态：已获取"
                        false -> "Root 状态：未获取，守护无法工作"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "已选择 ${selected.size} 个应用（勾选后即时生效，无需重启守护）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { toggleGuard() },
                            enabled = running || (rootOk == true && selected.isNotEmpty())
                        ) {
                            Text(if (running) "停止守护" else "启动守护")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    frozenInfo = withContext(Dispatchers.IO) {
                                        GuardEngine.frozenStateSnapshot()
                                            .ifEmpty { "（没有读到 frozen 状态行：可能当前没有应用被冻结）" }
                                    }
                                }
                            },
                            enabled = rootOk == true
                        ) {
                            Text("检查冻结状态")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索应用名或包名") }
            )

            val filtered = if (query.isBlank()) apps else apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }

            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.packageName }) { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = row.packageName in selected,
                            onCheckedChange = { checked ->
                                val next =
                                    if (checked) selected + row.packageName
                                    else selected - row.packageName
                                selected = next
                                scope.launch { PkgStore.save(ctx, next) }
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                row.packageName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (row.isSystem) {
                            Text(
                                "系统",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    frozenInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { frozenInfo = null },
            confirmButton = {
                TextButton(onClick = { frozenInfo = null }) { Text("关闭") }
            },
            title = { Text("系统冻结状态（frozen 行）") },
            text = {
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        )
    }
}
