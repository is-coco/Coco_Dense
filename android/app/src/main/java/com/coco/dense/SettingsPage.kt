package com.coco.dense

import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable
fun SettingsPage(repo: VaultRepository, sync: WebDavSync, importFile: () -> Unit, exportFile: () -> Unit,
                 runTask: (suspend () -> Unit) -> Unit, notice: (String) -> Unit, refresh: () -> Unit,
                 onSync: (SyncAction, SyncConfig) -> Unit,
                 deleted: List<JSONObject>, restore: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val initial = remember { sync.config() }
    var server by remember { mutableStateOf(initial.server) }; var user by remember { mutableStateOf(initial.user) }
    var davPassword by remember { mutableStateOf(initial.password) }; var path by remember { mutableStateOf(initial.path) }
    var cloudMaster by remember { mutableStateOf("") }; var cloudDataKey by remember { mutableStateOf("") }
    var rememberCloudKey by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }; var newPassword by remember { mutableStateOf("") }
    var nextDataKey by remember { mutableStateOf("") }; var rememberDataKey by remember { mutableStateOf(false) }
    var recoveryQuestion by remember { mutableStateOf("") }; var recoveryAnswer by remember { mutableStateOf("") }
    var showDavPassword by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("") }
    val patternStore = remember { PatternStore(context) }
    var patternEnabled by remember { mutableStateOf(patternStore.enabled) }
    var patternSetup by remember { mutableStateOf(false) }
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var patternHint by remember { mutableStateOf("") }
    val avatarFile = remember { File(context.filesDir, "avatar.png") }
    var avatar by remember { mutableStateOf(if (avatarFile.exists()) decodeAvatar(avatarFile.readBytes()) else null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runTask {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            require(bytes.size <= 3_000_000) { "头像文件不能超过 3 MB" }
            val decoded = withContext(Dispatchers.IO) { decodeAvatar(bytes) }
            require(decoded != null) { "无法读取图片" }
            withContext(Dispatchers.IO) { avatarFile.outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            avatar = decoded; refresh(); notice("头像已保存到本机")
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) Image(avatar!!.asImageBitmap(), "头像", Modifier.size(58.dp).clip(CircleShape))
                else Image(painterResource(R.drawable.coco_brand), "Coco Dense", Modifier.size(58.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("个人保险箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("只在这台设备保存本机设置", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { runCatching { avatarPicker.launch("image/*") }.onFailure { notice("无法打开图片选择器：${it.message}") } }) {
                    Icon(painterResource(R.drawable.tb_edit), "更换头像", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (avatar != null) TextButton(onClick = { avatarFile.delete(); avatar = null; refresh() }) { Text("移除本机头像") }
            Spacer(Modifier.height(26.dp))
            Text("连接与数据", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            SettingsGroup("同步", "上传、下载与云端保险箱", painterResource(R.drawable.tb_cloud_check), false) { section = "sync" }
            Spacer(Modifier.height(8.dp))
            SettingsGroup("数据钥匙", "额外加密凭据", painterResource(R.drawable.tb_key), false) { section = "key" }
            Spacer(Modifier.height(28.dp))
            Text("隐私与维护", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            SettingsGroup("安全", "主密码、图案解锁与找回", painterResource(R.drawable.tb_shield_lock), false) { section = "security" }
            Spacer(Modifier.height(8.dp))
            SettingsGroup("备份与迁移", "导入、导出与本机备份", painterResource(R.drawable.tb_download), false) { section = "backup" }
            Spacer(Modifier.height(8.dp))
            SettingsGroup("回收站", "${deleted.size} 条可恢复记录", painterResource(R.drawable.tb_trash), false) { section = "trash" }
            Spacer(Modifier.height(8.dp))
            SettingsGroup("关于与更新", "当前版本 ${BuildConfig.VERSION_NAME}", painterResource(R.drawable.tb_info_circle), false) { section = "update" }
            Spacer(Modifier.height(30.dp))
        }
        if (section.isNotEmpty()) Dialog(onDismissRequest = { section = ""; cloudMaster = ""; cloudDataKey = "" }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { section = ""; cloudMaster = ""; cloudDataKey = "" }) { Icon(painterResource(R.drawable.tb_arrow_left), "返回") }
                        Spacer(Modifier.width(8.dp))
                        Text(when (section) { "sync" -> "同步"; "key" -> "数据钥匙"; "security" -> "安全";
                            "backup" -> "备份与迁移"; "trash" -> "回收站"; else -> "关于与更新" },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (section == "trash") {
                        RecycleList(deleted, restore)
                    } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (section) {
                            "sync" -> {
                                SettingsField(server, { server = it }, "WebDAV 地址")
                                SettingsField(user, { user = it }, "用户名")
                                SettingsField(davPassword, { davPassword = it }, "应用密码",
                                    if (showDavPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                                    { IconButton(onClick = { showDavPassword = !showDavPassword }) {
                                        Icon(painterResource(if (showDavPassword) R.drawable.tb_eye_off else R.drawable.tb_eye), "显示或隐藏") } })
                                SettingsField(path, { path = it }, "远端路径")
                                Button(onClick = { runTask { withContext(Dispatchers.IO) { sync.saveConfig(SyncConfig(server, user, davPassword, path)) }; notice("同步配置已保存") } }, modifier = Modifier.fillMaxWidth()) { Text("保存同步配置") }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { onSync(SyncAction.DOWNLOAD, SyncConfig(server, user, davPassword, path)) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Icon(painterResource(R.drawable.tb_cloud_download), null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("下载云端")
                                    }
                                    Button(onClick = { onSync(SyncAction.UPLOAD, SyncConfig(server, user, davPassword, path)) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Icon(painterResource(R.drawable.tb_upload), null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp)); Text("上传本机")
                                    }
                                }
                                Text("下载只更新本机；上传会先读取云端并检查冲突，不能安全合并时不会覆盖。首页按钮继续双向同步，编辑后也会自动同步。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider()
                                Text("导入已有云端保险箱", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("仅在本机保险箱没有记录和分组时使用。请先确认桌面端有可解锁的加密备份；此操作只读下载云端文件，验证成功后备份并替换本机空库。之后请用云端主密码解锁手机。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SettingsField(cloudMaster, { cloudMaster = it }, "云端保险箱主密码", PasswordVisualTransformation())
                                SettingsField(cloudDataKey, { cloudDataKey = it }, "云端数据钥匙（如有）", PasswordVisualTransformation())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(rememberCloudKey, { rememberCloudKey = it })
                                    Text("本机记住数据钥匙")
                                }
                                OutlinedButton(onClick = { runTask {
                                    val count = withContext(Dispatchers.IO) {
                                        sync.saveConfig(SyncConfig(server, user, davPassword, path))
                                        sync.importExisting(cloudMaster, cloudDataKey)
                                    }
                                    BiometricStore(context as androidx.fragment.app.FragmentActivity).clear()
                                    patternStore.clear(); patternEnabled = false; RecoveryStore(context).clear()
                                    DeviceSecrets(context).put("remembered-data-key", if (rememberCloudKey && cloudDataKey.isNotBlank()) cloudDataKey else null)
                                    cloudMaster = ""; cloudDataKey = ""
                                    notice("已导入 $count 条记录；以后请使用云端主密码解锁")
                                } }, enabled = cloudMaster.isNotBlank() && deleted.isEmpty() && repo.entries(true).isEmpty() && repo.folders().isEmpty(),
                                    modifier = Modifier.fillMaxWidth()) { Text("验证并导入云端保险箱") }
                            }
                            "key" -> {
                                SettingsField(nextDataKey, { nextDataKey = it }, "新数据钥匙", PasswordVisualTransformation())
                                Row { Checkbox(rememberDataKey, { rememberDataKey = it }); Text("本机记住数据钥匙", modifier = Modifier.padding(top = 12.dp)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { runTask { withContext(Dispatchers.IO) { repo.setDataKey(nextDataKey) }; DeviceSecrets(context).put("remembered-data-key", if (rememberDataKey) nextDataKey else null); BiometricStore(context as androidx.fragment.app.FragmentActivity).clear(); patternStore.clear(); patternEnabled = false; notice("数据钥匙已更新，请重新设置快捷解锁") } }, enabled = nextDataKey.isNotBlank()) { Text("设置") }
                                OutlinedButton(onClick = { runTask { withContext(Dispatchers.IO) { repo.setDataKey("") }; DeviceSecrets(context).put("remembered-data-key", null); BiometricStore(context as androidx.fragment.app.FragmentActivity).clear(); patternStore.clear(); patternEnabled = false; notice("数据钥匙已清除，请重新设置快捷解锁") } }) { Text("清除") }
                                }
                                
                            }
                            "security" -> {
                                SettingsField(oldPassword, { oldPassword = it }, "当前主密码", PasswordVisualTransformation())
                                SettingsField(newPassword, { newPassword = it }, "新主密码", PasswordVisualTransformation())
                                Button(onClick = { runTask { withContext(Dispatchers.IO) { repo.changeMaster(oldPassword, newPassword); RecoveryStore(context).clear() }; BiometricStore(context as androidx.fragment.app.FragmentActivity).clear(); patternStore.clear(); patternEnabled = false; oldPassword = ""; newPassword = ""; notice("主密码已更新，请重新设置安全问题和快捷解锁") } }, enabled = oldPassword.isNotBlank() && newPassword.isNotBlank()) { Text("修改主密码") }
                                val biometric = remember { BiometricStore(context as androidx.fragment.app.FragmentActivity) }
                                if (biometric.available) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { biometric.enable(requireNotNull(repo.master), repo.dataKey, { notice("生物识别已启用") }, notice) }) { Text("启用生物识别") }
                                if (biometric.enabled) TextButton(onClick = { biometric.clear(); notice("生物识别已关闭") }) { Text("关闭") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { patternSetup = true; firstPattern = null; patternHint = "连接至少 4 个点" }) {
                                        Text(if (patternEnabled) "更换图案" else "设置图案解锁")
                                    }
                                    if (patternEnabled) TextButton(onClick = { patternStore.clear(); patternEnabled = false; patternSetup = false; notice("图案解锁已关闭") }) { Text("关闭") }
                                }
                                if (patternSetup) {
                                    Text(if (firstPattern == null) "绘制新图案" else "再次绘制确认", fontWeight = FontWeight.SemiBold)
                                    Text(patternHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        PatternGrid { points ->
                                            if (points.size < 4) patternHint = "请至少连接 4 个点"
                                            else if (firstPattern == null) { firstPattern = points; patternHint = "请再画一次相同图案" }
                                            else if (firstPattern != points) { firstPattern = null; patternHint = "两次图案不一致，请重新设置" }
                                            else runTask {
                                                withContext(Dispatchers.IO) { patternStore.set(points, requireNotNull(repo.master), repo.dataKey) }
                                                patternEnabled = true; patternSetup = false; firstPattern = null; notice("图案解锁已启用")
                                            }
                                        }
                                    }
                                    TextButton(onClick = { patternSetup = false; firstPattern = null }) { Text("取消设置") }
                                }
                                SettingsField(recoveryQuestion, { recoveryQuestion = it }, "安全问题")
                                SettingsField(recoveryAnswer, { recoveryAnswer = it }, "答案", PasswordVisualTransformation())
                                OutlinedButton(onClick = { runTask { RecoveryStore(context).save(requireNotNull(repo.master), recoveryQuestion, recoveryAnswer); notice("本机安全问题已保存") } }, enabled = recoveryQuestion.isNotBlank() && recoveryAnswer.isNotBlank()) { Text("保存安全问题") }
                                
                            }
                            "backup" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = exportFile) { Text("导出加密文件") }
                                OutlinedButton(onClick = importFile) { Text("导入文件") }
                                }
                                OutlinedButton(onClick = { runTask { withContext(Dispatchers.IO) { repo.backup("manual") }; notice("已创建本机备份") } }) { Text("创建本机备份") }
                                
                            }
                            "update" -> {
                                Text("当前版本 ${BuildConfig.VERSION_NAME}")
                                OutlinedButton(onClick = { runTask {
                                val release = withContext(Dispatchers.IO) { ApkUpdater(context).latest() }
                                if (release == null) notice("当前没有可用的安卓安装包") else {
                                val apk = withContext(Dispatchers.IO) { ApkUpdater(context).download(release) }
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")))
                                notice("请允许安装此应用的更新，然后再次点击更新")
                                } else {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                }
                                }
                                } }) { Text("检查并下载更新") }
                                
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                    }
                }
            }
        }
    }
}

private fun decodeAvatar(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (options.outWidth / sample > 512 || options.outHeight / sample > 512) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

@Composable
private fun SettingsGroup(title: String, subtitle: String, icon: androidx.compose.ui.graphics.painter.Painter,
                          expanded: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) painterResource(R.drawable.tb_chevron_up) else painterResource(R.drawable.tb_chevron_right), if (expanded) "收起" else "展开")
        }
    }
}

@Composable
private fun SettingsField(value: String, onChange: (String) -> Unit, label: String,
                          transformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                          trailing: (@Composable () -> Unit)? = null) {
    TextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = transformation, trailingIcon = trailing, shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface))
}
