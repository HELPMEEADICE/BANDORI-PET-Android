package com.bandori.pet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.data.DataRepository
import com.bandori.pet.ui.live2d.Live2DScreen
import com.bandori.pet.ui.theme.BandoriPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class FullscreenLive2DActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        val renderSettings = RenderSettings.load(appContext)
        preferRefreshRate(renderSettings.fpsLimit)
        I18n.init(appContext)
        setContent {
            val themeSettings = remember { ThemeSettings.load(appContext) }
            var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }
            val repository = remember { DataRepository(appContext) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                selectedModel = withContext(Dispatchers.IO) { loadPersistedModelChoice(appContext) }
            }

            BandoriPetTheme(
                darkTheme = themeSettings.darkMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = themeSettings.dynamicColorEnabled,
            ) {
                Live2DScreen(
                    selectedModel = selectedModel,
                    renderSettings = renderSettings,
                    fullScreen = true,
                    onRemoteCharacterSelected = { characterId ->
                        scope.launch {
                            val remoteModel = withContext(Dispatchers.IO) {
                                repository.load().characters[characterId]
                                    ?.let(repository::availableModels)
                                    ?.firstOrNull()
                            }
                            if (remoteModel != null) selectedModel = remoteModel
                        }
                    },
                    onFullScreenChanged = { fullScreen ->
                        if (!fullScreen) finish()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun preferRefreshRate(fpsLimit: Int) {
        window.attributes = window.attributes.apply {
            // A dedicated, mostly static full-screen window can otherwise be classified as
            // low-frame-rate content by Android's dynamic refresh-rate policy.
            preferredRefreshRate = fpsLimit.toFloat()
        }
    }
}
