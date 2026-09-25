package com.bandori.pet.ui.settings

import android.Manifest
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bandori.pet.I18n
import com.bandori.pet.ThemeSettings
import com.bandori.pet.companion.CompanionClient
import com.bandori.pet.companion.CompanionDiscovery
import com.bandori.pet.companion.CompanionSettings
import com.bandori.pet.companion.CompanionRuntimeStatus
import com.bandori.pet.companion.PairingPayload
import com.bandori.pet.companion.StoredDesktop
import com.bandori.pet.resolveDarkTheme
import com.bandori.pet.ui.theme.BandoriPetTheme
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CompanionSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        I18n.init(applicationContext)
        val imported = intent?.dataString.orEmpty()
        setContent {
            val themeSettings = remember { ThemeSettings.load(applicationContext) }
            BandoriPetTheme(
                darkTheme = themeSettings.darkMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = themeSettings.dynamicColorEnabled,
            ) {
                CompanionSettingsScreen(imported, ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionSettingsScreen(initialPairingText: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val discovery = remember { CompanionDiscovery(appContext) }
    val discovered by discovery.items.collectAsState()
    val connection by CompanionRuntimeStatus.connection.collectAsState()
    val backendStatus by CompanionRuntimeStatus.backends.collectAsState()
    var pairingText by remember { mutableStateOf(initialPairingText) }
    var stored by remember { mutableStateOf(CompanionSettings.load(appContext)) }
    var manualHost by remember { mutableStateOf(stored?.hosts?.firstOrNull().orEmpty()) }
    var status by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var ttsMuted by remember { mutableStateOf(CompanionSettings.ttsMuted(appContext)) }
    var showScanner by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true else status = "需要相机权限才能扫描二维码"
    }
    DisposableEffect(discovery) {
        discovery.start()
        onDispose(discovery::stop)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("桌面互联") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("桌面端负责模型、历史、记忆与 TTS；手机不保存桌面的 API Key。离线后桌面消息会立即从手机内存清除。", style = MaterialTheme.typography.bodyMedium)
            Text("连接状态：$connection · LLM：${backendStatus.llm} · TTS：${backendStatus.tts}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("手机静音 TTS")
                    Text("不改变桌面的 TTS 配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ttsMuted, onCheckedChange = {
                    ttsMuted = it
                    CompanionSettings.setTtsMuted(appContext, it)
                })
            }
            if (discovered.isNotEmpty()) {
                Text("局域网发现", style = MaterialTheme.typography.titleSmall)
                discovered.forEach { desktop ->
                    FilledTonalButton(
                        onClick = {
                            manualHost = desktop.host
                            status = "已选择 ${desktop.name}（${desktop.host}:${desktop.port}）"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${desktop.name} · ${desktop.host}:${desktop.port}") }
                }
            }
            if (stored != null) StoredDesktopCard(
                desktop = requireNotNull(stored),
                host = manualHost,
                onHostChanged = { manualHost = it },
                onSaveHost = {
                    CompanionSettings.updateHost(appContext, manualHost)
                    stored = CompanionSettings.load(appContext)
                    status = "连接地址已保存"
                },
                onReconnect = {
                    CompanionSettings.setRemoteMode(appContext, true)
                    CompanionRuntimeStatus.remoteEnabled.value = true
                    CompanionRuntimeStatus.reconnectEvents.tryEmit(Unit)
                    status = "已请求重新连接；聊天页会同步显示连接结果"
                },
                onForget = {
                    CompanionSettings.forget(appContext)
                    stored = null
                    manualHost = ""
                    status = "已忘记桌面设备"
                },
            ) else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showScanner = true
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("扫描二维码") }
                    TextButton(onClick = { pairingText = "" }, modifier = Modifier.weight(1f)) { Text("手动导入") }
                }
                OutlinedTextField(
                    value = pairingText,
                    onValueChange = { pairingText = it },
                    label = { Text("完整配对串") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("手动主机地址（VPN 场景可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = pairingText.isNotBlank() && !pairing,
                    onClick = {
                        pairing = true
                        status = "正在验证桌面证书并配对…"
                        scope.launch {
                            runCatching {
                                val payload = PairingPayload.parse(pairingText)
                                withContext(Dispatchers.IO) {
                                    CompanionClient.pair(appContext, payload, hostOverride = manualHost.takeIf(String::isNotBlank))
                                }
                            }.onSuccess {
                                stored = it
                                manualHost = it.hosts.firstOrNull().orEmpty()
                                status = "配对成功，可在聊天页切换为桌面模式"
                            }.onFailure { status = "配对失败：${it.message ?: it.javaClass.simpleName}" }
                            pairing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("安全配对") }
            }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (showScanner) {
        AlertDialog(
            onDismissRequest = { showScanner = false },
            title = { Text("扫描桌面配对二维码") },
            text = {
                CameraXQrScanner(
                    onResult = {
                        pairingText = it
                        status = "已读取二维码，请确认桌面地址后配对"
                        showScanner = false
                    },
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                )
            },
            confirmButton = { TextButton(onClick = { showScanner = false }) { Text("取消") } },
        )
    }
}

@Composable
@OptIn(ExperimentalGetImage::class)
private fun CameraXQrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }
    val processing = remember { AtomicBoolean(false) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(scanner, providerFuture) {
        onDispose {
            runCatching { scanner.close() }
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(viewContext)) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || !processing.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                codes.firstNotNullOfOrNull { it.rawValue }?.let(onResult)
                            }
                            .addOnCompleteListener {
                                processing.set(false)
                                imageProxy.close()
                            }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(viewContext))
            }
        },
    )
}

@Composable
private fun StoredDesktopCard(
    desktop: StoredDesktop,
    host: String,
    onHostChanged: (String) -> Unit,
    onSaveHost: () -> Unit,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("已配对：${desktop.name}", style = MaterialTheme.typography.titleMedium)
        Text("实例 ${desktop.instanceId}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("SPKI SHA-256\n${desktop.pinSha256}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = host,
            onValueChange = onHostChanged,
            label = { Text("桌面地址 / VPN 地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = onSaveHost, modifier = Modifier.fillMaxWidth()) { Text("保存连接地址") }
        Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text("重新连接") }
        TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) { Text("忘记此桌面") }
    }
}
