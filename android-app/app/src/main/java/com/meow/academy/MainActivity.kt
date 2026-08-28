package com.meow.academy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.meow.academy.data.settings.DEFAULT_THEME_SEED_ARGB
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.data.settings.ThemeMode
import com.meow.academy.ui.MainScreen
import com.meow.academy.ui.theme.MeowAcademyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MeowAcademyApp
        val repository = app.settingsRepository
        setContent {
            val themeMode by repository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val themeSeedColor by repository.themeSeedColor.collectAsState(initial = DEFAULT_THEME_SEED_ARGB)
            // 主题颜色动态配置（CONFIG 模式用）：FileObserver 热更 → 收集即实时换肤
            val themeConfig by app.themeConfigRepository.config.collectAsState(initial = null)
            MeowAcademyTheme(
                themeMode = themeMode,
                themeSeedColor = themeSeedColor,
                themeConfigRaw = themeConfig,
            ) {
                MainScreen(repository = repository)
            }
        }
    }
}
