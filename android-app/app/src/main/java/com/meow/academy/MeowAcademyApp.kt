package com.meow.academy

import android.app.Application
import com.meow.academy.data.settings.SettingsRepository

/** 喵学堂 Application：持有全局单例（DataStore 仓库等） */
class MeowAcademyApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
