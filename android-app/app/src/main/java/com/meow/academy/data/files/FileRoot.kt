package com.meow.academy.data.files

import android.content.Context
import com.meow.academy.runtime.RuntimeExtractor
import java.io.File

/** 文件根目录类型：工作区（App 内部）/ App 外部专属目录 */
enum class FileRoot { INTERNAL, EXTERNAL }

/** 根目录的中文显示名 */
fun FileRoot.displayName(): String = when (this) {
    FileRoot.INTERNAL -> "工作区"
    FileRoot.EXTERNAL -> "App 外部目录"
}

/**
 * 解析根目录对应 [File]。
 * phase4：INTERNAL 语义改为「工作区」（filesDir/workspace），
 * 文件管理页默认只见工作区，不再暴露 .dsh/、meow-runtime/ 等系统目录。
 * EXTERNAL 依赖外部存储可用性，不可用时返回 null（喵~）。
 */
fun FileRoot.resolve(context: Context): File? = when (this) {
    FileRoot.INTERNAL -> RuntimeExtractor.workspaceDir(context)
    FileRoot.EXTERNAL -> context.getExternalFilesDir(null)
}
