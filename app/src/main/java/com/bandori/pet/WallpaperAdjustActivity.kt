package com.bandori.pet

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.live2d.Live2DRenderView
import com.bandori.pet.live2d.Live2DTransform
import com.bandori.pet.ui.live2d.ContentUriImage
import com.bandori.pet.ui.theme.BandoriPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WallpaperAdjustActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(applicationContext)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContent {
            val themeSettings = remember { ThemeSettings.load(applicationContext) }
            BandoriPetTheme(
                darkTheme = themeSettings.darkMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = themeSettings.dynamicColorEnabled,
            ) {
                WallpaperAdjustScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun WallpaperAdjustScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }
    val transform = remember { mutableStateOf(loadWallpaperTransform(appContext)) }
    val renderSettings = remember { RenderSettings.load(appContext) }
    val backgroundUri = remember { loadWallpaperBackgroundUri(appContext) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        selectedModel = withContext(Dispatchers.IO) { loadPersistedModelChoice(appContext) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    ) {
        ContentUriImage(
            uri = backgroundUri,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selectedModel == null) {
            ElevatedCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(I18n.t("wallpaper_no_model"), fontWeight = FontWeight.Bold)
                    Text(I18n.t("wallpaper_no_model_desc"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onClose) { Text(I18n.t("wallpaper_back")) }
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    Live2DRenderView(viewContext).apply {
                        statusChanged = { status = it }
                        transformChanged = { transform.value = it }
                        setInteractionLocked(false)
                        setRenderOptions(renderSettings.fpsLimit, renderSettings.vsyncEnabled)
                        setRenderResolution(renderSettings.renderResolution)
                        setFpsDisplayEnabled(renderSettings.fpsDisplayEnabled)
                        setTransform(transform.value)
                        setModel(selectedModel)
                    }
                },
                update = { view ->
                    view.statusChanged = { status = it }
                    view.transformChanged = { transform.value = it }
                    view.setInteractionLocked(false)
                    view.setRenderOptions(renderSettings.fpsLimit, renderSettings.vsyncEnabled)
                    view.setRenderResolution(renderSettings.renderResolution)
                    view.setFpsDisplayEnabled(renderSettings.fpsDisplayEnabled)
                    view.setTransform(transform.value)
                    view.setModel(selectedModel)
                },
                onRelease = Live2DRenderView::release,
            )
        }

        ElevatedCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(I18n.t("wallpaper_adjust_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(I18n.t("wallpaper_adjust_desc"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        status?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 6.dp,
            ) {
                Text(text = it, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }

        ElevatedCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        transform.value = Live2DTransform()
                    },
                ) {
                    Text(I18n.t("wallpaper_reset"))
                }
                Spacer(Modifier.height(1.dp))
                TextButton(modifier = Modifier.weight(1f), onClick = onClose) {
                    Text(I18n.t("wallpaper_cancel"))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        saveWallpaperTransform(appContext, transform.value)
                        onClose()
                    },
                ) {
                    Text(I18n.t("wallpaper_save"))
                }
            }
        }
    }
}
