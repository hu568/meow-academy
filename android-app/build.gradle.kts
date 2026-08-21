// 喵仓 MeowAcademy · 根构建脚本
// SPDX-License-Identifier: GPL-3.0
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ── 许可证元数据（全项目共享，子模块经 extra["license*"] 读取）──
extra["licenseName"] = "GPL-3.0"
extra["licenseUrl"] = "https://www.gnu.org/licenses/gpl-3.0.txt"
