package com.bandori.pet.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import com.bandori.pet.I18n
import com.bandori.pet.ThemeSettings
import com.bandori.pet.resolveDarkTheme
import com.bandori.pet.ui.theme.BandoriPetTheme

class LlmSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContext = applicationContext
        I18n.init(appContext)
        setContent {
            val themeSettings = remember { ThemeSettings.load(appContext) }
            BandoriPetTheme(
                darkTheme = themeSettings.darkMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = themeSettings.dynamicColorEnabled,
            ) {
                LlmSettingsScreen(onBack = ::finish)
            }
        }
    }
}
