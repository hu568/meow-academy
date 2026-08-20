package com.meow.academy.data.files

import android.content.Context
import java.io.File

/** 文件根目录类型：App 内部私有目录 / App 外部专属目录 */
enum class FileRoot { INTERNAL, EXTERNAL }

/** 根目录的中文显示名 */
fun FileRoot.displayName(): String = when (this) {
    FileRoot.INTERNAL -> "App 数据目录"
    FileRoot.EXTERNAL -> "App 外部目录"
}

/**
 * 解析根目录对应 [File]。
 * EXTERNAL 依赖外部存储可用性，不可用时返回 null（喵~）。
 */
fun FileRoot.resolve(context: Context): File? = when (this) {
    FileRoot.INTERNAL -> context.filesDir
    FileRoot.EXTERNAL -> context.getExternalFilesDir(null)
}
