// 喵仓 MeowAcademy · app 模块
// SPDX-License-Identifier: GPL-3.0
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.ksp)
}

// ── APK 同步到 release/（与 AGENTS.md 约定一致）──
// assembleDebug 完成后把 APK 复制到仓库根 release/meow-academy-<version>-debug.apk，
// 每次编译后自动更新，无需手动复制。
// release/ 中 APK 被 .gitignore 忽略（~86MB 不入库），RELEASE_NOTES_*.md 才入库。
val syncApkToRelease = tasks.register<Copy>("syncApkToRelease") {
    group = "build"
    description = "复制 debug APK 到仓库根 release/"
    from(layout.buildDirectory.dir("outputs/apk/debug"))
    include("app-debug.apk")
    into(File(rootProject.projectDir.parentFile ?: rootProject.projectDir, "release"))
    rename { "meow-academy-${android.defaultConfig.versionName}-debug.apk" }
}
// AGP 任务延迟注册，需 afterEvaluate 后才能挂 assembleDebug
afterEvaluate {
    tasks.named("assembleDebug") {
        finalizedBy(syncApkToRelease)
    }
}

android {
    namespace = "com.meow.academy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meow.academy"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.2.9"

        // Room 导出 schema 到本地（用于后续迁移）
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // 排除重复的 META-INF 许可文件（Room/commons-compress 等依赖引入）
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.all {
    // prism4j 依赖链引入旧版 annotations-java5，与 org.jetbrains:annotations 冲突
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // 图片加载（Coil：文件缩略图 / 图片浮窗预览 / 后续 MD 与聊天图片共用）
    implementation(libs.coil.compose)

    // 协程 + 序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 数据持久化（设置 + 会话）
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 运行时解压（tar.gz：GZIP 内置 + commons-compress 解 tar）
    implementation(libs.commons.compress)

    // Markdown 渲染（Markwon，View 体系经 AndroidView 接入 Compose）
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    // 公式块（LaTeX，$$…$$ 块/行内）+ 代码语法着色（Prism4j）
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.markwon.inline.parser)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.prism4j)
    kapt(libs.prism4j.bundler)

    // 保活心跳（M2.6）
    implementation(libs.androidx.work.runtime.ktx)

    // 拖拽排序（长按卡片上下拖动 + 边缘自动滚动）
    implementation(libs.reorderable)

    // 旧版 appconfig/markdown-config.js 的一次性 JSONC 迁移求值（Rhino 解释模式，Android 兼容）；
    // 迁移完成后仅剩兜底用途，后续可随存量用户清零移除
    implementation(libs.rhino)

    debugImplementation(libs.androidx.ui.tooling)

    // 块拆分器纯函数单测
    testImplementation(libs.junit)
}
