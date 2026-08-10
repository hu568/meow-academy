package com.meow.academy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.data.settings.ThemeMode
import com.meow.academy.ui.MainScreen
import com.meow.academy.ui.theme.MeowAcademyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as MeowAcademyApp).settingsRepository
        setContent {
            val themeMode by repository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            MeowAcademyTheme(themeMode = themeMode) {
                MainScreen(repository = repository)
            }
        }
    }
}
