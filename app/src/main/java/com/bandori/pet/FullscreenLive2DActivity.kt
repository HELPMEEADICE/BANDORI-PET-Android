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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.ui.live2d.Live2DScreen
import com.bandori.pet.ui.theme.BandoriPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FullscreenLive2DActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        I18n.init(appContext)
        setContent {
            val themeSettings = remember { ThemeSettings.load(appContext) }
            var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }

            LaunchedEffect(Unit) {
                selectedModel = withContext(Dispatchers.IO) { loadPersistedModelChoice(appContext) }
            }

            BandoriPetTheme(
                darkTheme = themeSettings.darkMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = themeSettings.dynamicColorEnabled,
            ) {
                Live2DScreen(
                    selectedModel = selectedModel,
                    renderSettings = remember { RenderSettings.load(appContext) },
                    fullScreen = true,
                    onFullScreenChanged = { fullScreen ->
                        if (!fullScreen) finish()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
