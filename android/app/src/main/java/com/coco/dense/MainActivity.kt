package com.coco.dense

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private lateinit var repo: VaultRepository
    private var stoppedAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = VaultRepository(this)
        setContent {
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
            val colors = lightColorScheme(primary = Color(0xFF27345B), onPrimary = Color.White, primaryContainer = Color(0xFFE2F2E3),
                    onPrimaryContainer = Color(0xFF1D3150), secondary = Color(0xFF316D63), secondaryContainer = Color(0xFFE6F4ED),
                    background = Color(0xFFF7F9FC), surface = Color.White, surfaceVariant = Color(0xFFEEF1F7),
                    onSurface = Color(0xFF18213A), onSurfaceVariant = Color(0xFF697387), outline = Color(0xFFB2BDD0))
            MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
                App(repo)
            }
        }
    }
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }
    override fun onStart() {
        super.onStart()
        if (::repo.isInitialized && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 300_000) {
            repo.lock(); recreate()
        }
    }
}

@Composable
private fun App(repo: VaultRepository) {
    val context = LocalContext.current
    val sync = remember { WebDavSync(context, repo) }
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var viewing by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf<List<SyncConflict>>(emptyList()) }
    var choices by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var conflictAction by remember { mutableStateOf(SyncAction.QUICK) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importDataKey by remember { mutableStateOf("") }
    var pendingSync by remember { mutableStateOf<Job?>(null) }
    val operationMutex = remember { Mutex() }
    val snackbar = remember { SnackbarHostState() }
    val refresh: () -> Unit = { revision++; Unit }
    val runTask: (suspend () -> Unit) -> Unit = { block ->
        scope.launch {
            busy = true
            try { operationMutex.withLock { block(); refresh() } } catch (e: Exception) { message = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    val runSync: (SyncAction, SyncConfig?, Map<String, Boolean>) -> Unit = { action, candidate, selected ->
        runTask {
            val result = withContext(Dispatchers.IO) {
                if (candidate != null) sync.saveConfig(candidate)
                if (action == SyncAction.DOWNLOAD) sync.download(selected) else sync.sync(selected)
            }
            if (result is SyncResult.NeedsChoice) {
                conflictAction = action
                conflicts = result.conflicts
            } else if (result is SyncResult.Done) {
                message = when (action) {
                    SyncAction.DOWNLOAD -> "已下载并合并到本机，未上传云端（${result.entries} 条）"
                    SyncAction.UPLOAD -> "已合并并上传云端（${result.entries} 条）"
                    SyncAction.QUICK -> "双向同步完成（${result.entries} 条）"
                }
            }
        }
    }
    val afterMutation: () -> Unit = {
        refresh()
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(700)
            if (sync.config().let { it.server.isNotBlank() && it.user.isNotBlank() && it.password.isNotBlank() }) {
                busy = true
                try {
                    val result = operationMutex.withLock { withContext(Dispatchers.IO) { sync.sync() } }
                    if (result is SyncResult.NeedsChoice) { conflictAction = SyncAction.QUICK; conflicts = result.conflicts }
                    else refresh()
                } catch (e: Exception) { message = "自动同步未完成：${e.message}" }
                finally { busy = false }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            pendingImport = bytes
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runTask {
            val bytes = withContext(Dispatchers.IO) { repo.export() }
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            message = "已导出加密备份"
        }
    }

    if (!unlocked) {
        AuthScreen(repo.exists, busy, RecoveryStore(context).question(), BiometricStore(context as androidx.fragment.app.FragmentActivity).enabled,
            PatternStore(context).enabled, { password, key ->
            runTask {
                withContext(Dispatchers.IO) { if (repo.exists) repo.unlock(password, key) else repo.create(password) }
                unlocked = true
            }
        }, { answer, key -> runTask {
            withContext(Dispatchers.IO) { repo.unlock(RecoveryStore(context).recover(answer), key) }
            unlocked = true
        } }, {
            BiometricStore(context as androidx.fragment.app.FragmentActivity).unlock({ password, key -> runTask {
                withContext(Dispatchers.IO) { repo.unlock(password, key) }; unlocked = true
            } }, { message = it })
        }, { pattern -> runTask {
            val (password, key) = withContext(Dispatchers.IO) { PatternStore(context).unlock(pattern) }
            withContext(Dispatchers.IO) { repo.unlock(password, key) }
            unlocked = true
        } })
        if (message.isNotEmpty()) ErrorDialog(message) { message = "" }
        return
    }
    val entries = remember(revision) { repo.entries() }
    val deleted = remember(revision) { repo.entries(true).filter { it.optString("deletedAt").isNotEmpty() } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.coco_brand), null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("COCO DENSE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    Text(if (tab == 0) "保险箱" else "设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (tab == 0) IconButton(onClick = { runSync(SyncAction.QUICK, null, emptyMap()) }, enabled = !busy) {
                    Icon(painterResource(R.drawable.tb_refresh), "双向同步")
                }
                IconButton(onClick = { repo.lock(); unlocked = false }, enabled = !busy,
                    modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) { Icon(painterResource(R.drawable.tb_lock), "锁定", modifier = Modifier.size(21.dp)) }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    BottomAction("保险箱", painterResource(R.drawable.tb_layout_dashboard), tab == 0, Modifier.weight(1f)) { tab = 0 }
                    BottomAction("设置", painterResource(R.drawable.tb_adjustments_horizontal), tab == 1, Modifier.weight(1f)) { tab = 1 }
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(
                onClick = { editing = JSONObject() },
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp)
            ) {
                Icon(painterResource(R.drawable.tb_plus), "新增密码", modifier = Modifier.size(24.dp))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> VaultList(entries, repo.folders(), { viewing = it }, { value ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Coco Dense", value))
                    scope.launch { snackbar.showSnackbar("密码已复制，30 秒后清除剪贴板") }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == value) clipboard.clearPrimaryClip()
                    }, 30_000)
                },
                    { folder -> runTask { withContext(Dispatchers.IO) { repo.putFolder(folder) }; afterMutation() } },
                    { id -> runTask { withContext(Dispatchers.IO) { repo.removeFolder(id) }; afterMutation() } })
                else -> SettingsPage(repo, sync, { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    { exportLauncher.launch("Coco-Dense-vault.json") }, runTask, { message = it }, refresh,
                    { action, config -> runSync(action, config, emptyMap()) },
                    deleted, { entry -> runTask { withContext(Dispatchers.IO) { repo.trash(entry.optString("id"), restore = true) }; afterMutation() } })
            }
        }
    }
    if (editing != null) EntryEditor(editing!!, repo.folders(), onDismiss = { editing = null }, onSave = { entry ->
        runTask { withContext(Dispatchers.IO) { repo.upsert(entry) }; editing = null; viewing = null; afterMutation() }
    })
    if (viewing != null) EntryDetail(viewing!!, onDismiss = { viewing = null }, onEdit = { editing = JSONObject(viewing.toString()) },
        onDelete = { val id = viewing!!.optString("id"); runTask { withContext(Dispatchers.IO) { repo.trash(id) }; viewing = null; afterMutation() } },
        onCopy = { value ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Coco Dense", value))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == value) clipboard.clearPrimaryClip()
            }, 30_000)
            scope.launch { snackbar.showSnackbar("已复制，30 秒后清除剪贴板") }
        })
    if (conflicts.isNotEmpty()) AlertDialog(onDismissRequest = {}, title = { Text("同步冲突") },
        text = { Column { Text("两台设备修改了相同条目，请逐条选择保留版本。")
            conflicts.forEach { conflict ->
                Text(conflict.local?.optString("site")?.ifEmpty { conflict.local?.optString("name") ?: "" }
                    ?: conflict.remote?.optString("site")?.ifEmpty { conflict.remote?.optString("name") ?: "" } ?: conflict.id,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Row { FilterChip(selected = choices[conflict.id] == true, onClick = { choices = choices + (conflict.id to true) }, label = { Text("本机") })
                    Spacer(Modifier.width(8.dp)); FilterChip(selected = choices[conflict.id] == false, onClick = { choices = choices + (conflict.id to false) }, label = { Text("云端") }) }
            }
        } }, confirmButton = { TextButton(enabled = conflicts.all { choices.containsKey(it.id) }, onClick = {
            val selected = choices; conflicts = emptyList(); choices = emptyMap()
            runSync(conflictAction, null, selected)
        }) { Text("合并") } }, dismissButton = { TextButton(onClick = { conflicts = emptyList(); choices = emptyMap() }) { Text("取消") } })
    if (pendingImport != null) AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("导入加密保险箱") },
        text = { Column {
            Text("导入前会备份当前保险箱。请输入备份文件原本使用的凭据。")
            OutlinedTextField(importPassword, { importPassword = it }, label = { Text("备份主密码") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(importDataKey, { importDataKey = it }, label = { Text("备份数据钥匙（如有）") }, visualTransformation = PasswordVisualTransformation())
        } }, confirmButton = { TextButton(enabled = importPassword.isNotBlank(), onClick = {
            val bytes = pendingImport!!; pendingImport = null
            runTask { withContext(Dispatchers.IO) { repo.import(bytes, importPassword, importDataKey); sync.resetBaseline() }; BiometricStore(context as androidx.fragment.app.FragmentActivity).clear(); PatternStore(context).clear(); message = "已导入保险箱，请重新设置快捷解锁" }
        }) { Text("导入") } }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } })
    if (message.isNotEmpty()) ErrorDialog(message) { message = "" }
}

@Composable
private fun BottomAction(label: String, icon: androidx.compose.ui.graphics.painter.Painter, selected: Boolean,
                         modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(width = 52.dp, height = 30.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorDialog(text: String, close: () -> Unit) = AlertDialog(onDismissRequest = close, title = { Text("提示") }, text = { Text(text) }, confirmButton = { TextButton(onClick = close) { Text("知道了") } })

@Composable
private fun AuthScreen(hasVault: Boolean, busy: Boolean, recoveryQuestion: String?, biometricEnabled: Boolean,
                       patternEnabled: Boolean, submit: (String, String) -> Unit, recover: (String, String) -> Unit,
                       biometric: () -> Unit, patternUnlock: (List<Int>) -> Unit) {
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    var dataKey by remember { mutableStateOf(DeviceSecrets(context).get("remembered-data-key") ?: "") }
    var dataKeyOpen by remember { mutableStateOf(dataKey.isNotEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    var patternOpen by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    val ink = Color(0xFF111A30)
    val mint = Color(0xFF2B7464)
    SideEffect {
        WindowCompat.getInsetsController((context as MainActivity).window, context.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF8FAFC), Color(0xFFEFF3F8)))).systemBarsPadding()) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 85.dp, y = (-80).dp).size(220.dp)
            .background(Color(0xFFB7E5CC).copy(alpha = .35f), CircleShape))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 90.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.coco_brand), "Coco Dense", modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Text("COCO DENSE", color = ink, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(60.dp))
            Text(if (hasVault) "欢迎回来。" else "从这里开始。", color = ink,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(if (hasVault) "输入主密码，打开你的保险箱" else "创建主密码，收好所有账号",
                color = Color(0xFF697387), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(36.dp))
            Text("主密码", color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            SecretInput(password, { password = it }, "输入主密码", passwordVisible) { passwordVisible = !passwordVisible }
            if (hasVault) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().clickable { dataKeyOpen = !dataKeyOpen }.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.tb_key), null, modifier = Modifier.size(19.dp), tint = mint)
                    Spacer(Modifier.width(8.dp))
                    Text("使用数据钥匙", color = mint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(painterResource(if (dataKeyOpen) R.drawable.tb_chevron_up else R.drawable.tb_chevron_down), null,
                        modifier = Modifier.size(18.dp), tint = mint)
                }
                if (dataKeyOpen) SecretInput(dataKey, { dataKey = it }, "数据钥匙", false, null)
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = { submit(password, dataKey) }, enabled = password.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = Color.White,
                    disabledContainerColor = Color(0xFFD9E0EA), disabledContentColor = Color(0xFF687588))) {
                if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color.White)
                else {
                    Text(if (hasVault) "解锁保险箱" else "创建保险箱", fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Icon(painterResource(R.drawable.tb_arrow_up_right), null, modifier = Modifier.size(21.dp))
                }
            }
            if (hasVault && biometricEnabled) {
                Spacer(Modifier.height(14.dp))
                Surface(onClick = biometric, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                    color = Color.White, border = BorderStroke(1.dp, Color(0xFFE0E6EE))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(painterResource(R.drawable.tb_fingerprint), null, modifier = Modifier.size(21.dp), tint = ink)
                        Spacer(Modifier.width(9.dp)); Text("使用生物识别", color = ink)
                    }
                }
            }
            if (hasVault && patternEnabled) {
                Spacer(Modifier.height(10.dp))
                Surface(onClick = { patternOpen = true }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                    color = Color.White, border = BorderStroke(1.dp, Color(0xFFE0E6EE))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(painterResource(R.drawable.tb_grid_dots), null, modifier = Modifier.size(21.dp), tint = ink)
                        Spacer(Modifier.width(9.dp)); Text("使用图案解锁", color = ink)
                    }
                }
            }
            if (hasVault && recoveryQuestion != null) {
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { recovery = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("忘记主密码？", color = Color(0xFF5B6A80))
                }
            }
        }
        Text("本机加密保存 · 你的密码只属于你", color = Color(0xFF7F8B9D),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
    if (recovery) AlertDialog(onDismissRequest = { recovery = false }, title = { Text("安全问题找回") },
        text = { Column { Text(recoveryQuestion ?: ""); OutlinedTextField(answer, { answer = it }, label = { Text("答案") }) } },
        confirmButton = { TextButton(onClick = { recovery = false; recover(answer, dataKey) }, enabled = answer.isNotBlank()) { Text("解锁") } },
        dismissButton = { TextButton(onClick = { recovery = false }) { Text("取消") } })
    if (patternOpen) Dialog(onDismissRequest = { patternOpen = false }) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("绘制解锁图案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                PatternGrid { points -> patternUnlock(points) }
                TextButton(onClick = { patternOpen = false }) { Text("使用主密码") }
            }
        }
    }
}

@Composable
private fun SecretInput(value: String, onValueChange: (String) -> Unit, placeholder: String,
                            visible: Boolean, toggle: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE0E6EE))) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
                visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = TextStyle(color = Color(0xFF111A30), fontSize = 16.sp), cursorBrush = SolidColor(Color(0xFF2B7464)),
                modifier = Modifier.weight(1f), decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(placeholder, color = Color(0xFF8793A5)); inner() }
                })
            if (toggle != null) IconButton(onClick = toggle) {
                Icon(painterResource(if (visible) R.drawable.tb_eye_off else R.drawable.tb_eye), "显示或隐藏",
                    tint = Color(0xFF5B6A80), modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun VaultList(entries: List<JSONObject>, folders: List<JSONObject>, open: (JSONObject) -> Unit,
                      copyPassword: (String) -> Unit, saveFolder: (JSONObject) -> Unit, deleteFolder: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<JSONObject?>(null) }
    var priorityFilter by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }
    val tags = entries.flatMap { it.optString("tags").split(',').map(String::trim).filter(String::isNotEmpty) }.distinct().sorted()
    val shown = entries.filter {
        (selectedFolder.isEmpty() || it.optString("folderId") == selectedFolder) &&
            (priorityFilter.isEmpty() || it.optString("priority", "green") == priorityFilter) &&
            (!favoritesOnly || it.optBoolean("favorite")) &&
            (tagFilter.isEmpty() || it.optString("tags").split(',').map(String::trim).contains(tagFilter)) &&
            (query.isBlank() || listOf("site", "account", "url", "tags").any { field -> it.optString(field).contains(query, true) })
    }.sortedWith(compareByDescending<JSONObject> { it.optBoolean("pinned") }.thenByDescending { it.optBoolean("favorite") }.thenBy { it.optString("site") })
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            TextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(painterResource(R.drawable.tb_search), null) }, placeholder = { Text("搜索密码或账号") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.tb_x), "清除搜索") } },
                singleLine = true, shape = RoundedCornerShape(19.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} 条记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { filtersOpen = true }) { Icon(painterResource(R.drawable.tb_adjustments_horizontal), "筛选和分组") }
                    DropdownMenu(expanded = filtersOpen, onDismissRequest = { filtersOpen = false }) {
                        DropdownMenuItem(text = { Text("新建分组") }, leadingIcon = { Icon(painterResource(R.drawable.tb_folder_plus), null) }, onClick = { newFolder = true; filtersOpen = false })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(if (favoritesOnly) "✓ 仅看收藏" else "仅看收藏") }, onClick = { favoritesOnly = !favoritesOnly; filtersOpen = false })
                        listOf("red" to "紧急", "yellow" to "重要").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(if (priorityFilter == value) "✓ $label" else label) }, onClick = { priorityFilter = if (priorityFilter == value) "" else value; filtersOpen = false })
                        }
                        if (priorityFilter.isNotEmpty() || favoritesOnly || tagFilter.isNotEmpty())
                            DropdownMenuItem(text = { Text("清除筛选") }, onClick = { priorityFilter = ""; favoritesOnly = false; tagFilter = ""; filtersOpen = false })
                    }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { VaultPill("全部", selectedFolder.isEmpty() && !favoritesOnly) { selectedFolder = ""; favoritesOnly = false } }
            item { VaultPill("收藏", favoritesOnly) { favoritesOnly = !favoritesOnly } }
            items(folders, key = { it.optString("id") }) { folder -> VaultPill(folder.optString("name"), selectedFolder == folder.optString("id")) {
                selectedFolder = folder.optString("id")
            } }
            if (selectedFolder.isNotEmpty()) item { VaultPill("管理分组", false) {
                editingFolder = folders.firstOrNull { it.optString("id") == selectedFolder }
                folderName = editingFolder?.optString("name") ?: ""
            } }
        }
        if (tags.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tags) { tag -> VaultPill("#$tag", tagFilter == tag) { tagFilter = if (tagFilter == tag) "" else tag } }
        }
        if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (entries.isEmpty()) R.drawable.tb_key else R.drawable.tb_search), null,
                        modifier = Modifier.size(29.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Text(if (entries.isEmpty()) "还没有记录" else "没有找到记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(if (entries.isEmpty()) "点击右下角的 +，添加第一个账号。" else "换个关键词，或清除当前筛选。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(shown, key = { it.optString("id") }) { entry ->
                Card(onClick = { open(entry) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(start = 14.dp, top = 13.dp, bottom = 13.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text(entry.optString("site", "?").take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.optString("site", "未命名"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.optString("account").ifEmpty { "点击查看详情" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (entry.optBoolean("pinned")) Icon(painterResource(R.drawable.tb_pin), "置顶", modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { copyPassword(entry.optString("password")) }) { Icon(painterResource(R.drawable.tb_copy), "复制密码", tint = MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
        }
    }
    if (newFolder) AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("新建分组") },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("名称") }) },
        confirmButton = { TextButton(onClick = { saveFolder(JSONObject().put("name", folderName.trim()).put("priority", "green")); folderName = ""; newFolder = false }, enabled = folderName.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { newFolder = false }) { Text("取消") } })
    if (editingFolder != null) AlertDialog(onDismissRequest = { editingFolder = null }, title = { Text("管理分组") },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("名称") }) },
        confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = {
            saveFolder(JSONObject(editingFolder.toString()).put("name", folderName.trim())); editingFolder = null
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { deleteFolder(editingFolder!!.optString("id")); selectedFolder = ""; editingFolder = null }) { Text("删除分组") } })
}

@Composable
private fun VaultPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecycleList(entries: List<JSONObject>, restore: (JSONObject) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 22.dp)) {
        Text("误删的记录可以在这里找回", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp, bottom = 22.dp))
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.tb_trash), null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                Text("回收站是空的", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("这里没有待恢复的记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(entries) { entry -> Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Text(entry.optString("site", "?").take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.optString("site", "未命名"), fontWeight = FontWeight.SemiBold)
                        Text(entry.optString("account"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { restore(entry) }) { Text("恢复") }
                }
            } }
        }
    }
}

@Composable
private fun EntryDetail(entry: JSONObject, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onCopy: (String) -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.tb_arrow_left), "返回") }
                    Text("记录详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.tb_trash), "移入回收站", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        Text(entry.optString("site", "?").take(1).uppercase(), style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(entry.optString("site", "未命名"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(entry.optString("account"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(25.dp))
                    Button(onClick = { onCopy(entry.optString("password")) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)) {
                        Icon(painterResource(R.drawable.tb_copy), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp)); Text("复制密码", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(30.dp))
                    Text("登录信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                            DetailField("账号", entry.optString("account"), R.drawable.tb_user_circle) { onCopy(entry.optString("account")) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Row(Modifier.fillMaxWidth().heightIn(min = 78.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.tb_key), null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(21.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("密码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (showPassword) entry.optString("password") else "••••••••••••", style = MaterialTheme.typography.bodyLarge)
                                }
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(painterResource(if (showPassword) R.drawable.tb_eye_off else R.drawable.tb_eye), "显示或隐藏密码")
                                }
                            }
                            if (entry.optString("url").isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                DetailField("网址", entry.optString("url"), R.drawable.tb_arrow_up_right) { onCopy(entry.optString("url")) }
                            }
                        }
                    }
                    if (entry.optString("tags").isNotEmpty() || entry.optString("notes").isNotEmpty()) {
                        Spacer(Modifier.height(28.dp))
                        Text("其他信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                                if (entry.optString("tags").isNotEmpty()) DetailField("标签", entry.optString("tags"), R.drawable.tb_bookmark) { onCopy(entry.optString("tags")) }
                                if (entry.optString("notes").isNotEmpty()) DetailField("备注", entry.optString("notes"), R.drawable.tb_edit) { onCopy(entry.optString("notes")) }
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).height(54.dp),
                        shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Icon(painterResource(R.drawable.tb_edit), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("编辑记录", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("移入回收站？") },
        text = { Text("这条记录之后仍可恢复。") }, confirmButton = { TextButton(onClick = onDelete) { Text("移入回收站") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun DetailField(label: String, value: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifEmpty { "未填写" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.tb_copy), "复制$label", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryEditor(original: JSONObject, folders: List<JSONObject>, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var site by remember { mutableStateOf(original.optString("site")) }; var account by remember { mutableStateOf(original.optString("account")) }
    var password by remember { mutableStateOf(original.optString("password")) }; var url by remember { mutableStateOf(original.optString("url")) }
    var tags by remember { mutableStateOf(original.optString("tags")) }; var notes by remember { mutableStateOf(original.optString("notes")) }
    var folder by remember { mutableStateOf(original.optString("folderId")) }; var priority by remember { mutableStateOf(original.optString("priority", "green")) }
    var favorite by remember { mutableStateOf(original.optBoolean("favorite")) }; var pinned by remember { mutableStateOf(original.optBoolean("pinned")) }
    var folderMenu by remember { mutableStateOf(false) }; var showPassword by remember { mutableStateOf(false) }
    val save: () -> Unit = {
        onSave(original.put("site", site.trim()).put("account", account.trim()).put("password", password)
            .put("url", url.trim()).put("tags", tags.trim()).put("notes", notes.trim())
            .put("folderId", folder).put("priority", priority).put("favorite", favorite).put("pinned", pinned))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.tb_arrow_left), "返回") }
                    Text(if (original.optString("id").isEmpty()) "新建记录" else "编辑记录",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("登录信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    EditField(site, { site = it }, "名称 *")
                    EditField(account, { account = it }, "账号")
                    EditField(password, { password = it }, "密码",
                        transformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(painterResource(if (showPassword) R.drawable.tb_eye_off else R.drawable.tb_eye), "显示或隐藏")
                                }
                                IconButton(onClick = {
                                    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#%^&*"
                                    val random = SecureRandom()
                                    password = (1..18).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
                                }) { Icon(painterResource(R.drawable.tb_wand), "生成密码") }
                            }
                        })
                    EditField(url, { url = it }, "网址")
                    Spacer(Modifier.height(12.dp))
                    Text("整理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Box {
                        Surface(onClick = { folderMenu = true }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.tb_folder), null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("分组", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(folders.firstOrNull { it.optString("id") == folder }?.optString("name") ?: "未分组")
                                }
                                Icon(painterResource(R.drawable.tb_chevron_right), null)
                            }
                        }
                        DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(text = { Text("未分组") }, onClick = { folder = ""; folderMenu = false })
                            folders.forEach { f -> DropdownMenuItem(text = { Text(f.optString("name")) }, onClick = { folder = f.optString("id"); folderMenu = false }) }
                        }
                    }
                    Text("优先级", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("green" to "普通", "yellow" to "重要", "red" to "紧急").forEach { (value, label) ->
                            VaultPill(label, priority == value) { priority = value }
                        }
                    }
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.tb_star), null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(12.dp)); Text("加入收藏", modifier = Modifier.weight(1f))
                                Switch(favorite, { favorite = it })
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.tb_pin), null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(12.dp)); Text("置顶记录", modifier = Modifier.weight(1f))
                                Switch(pinned, { pinned = it })
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("补充信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    EditField(tags, { tags = it }, "标签，以逗号分隔")
                    EditField(notes, { notes = it }, "备注", singleLine = false, minLines = 3)
                    Spacer(Modifier.height(25.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Button(onClick = save, enabled = site.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).height(56.dp),
                        shape = RoundedCornerShape(17.dp)) { Text("保存记录", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun EditField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true,
                      minLines: Int = 1, transformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                      trailing: (@Composable () -> Unit)? = null) {
    TextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines,
        visualTransformation = transformation, trailingIcon = trailing, shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface))
}
