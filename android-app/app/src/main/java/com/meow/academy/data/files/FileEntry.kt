package com.meow.academy.data.files

/**
 * 文件/目录条目（列表展示用）。
 *
 * @property path 绝对路径
 * @property size 字节数（目录可为 0）
 * @property lastModified 最后修改时间（epoch millis）
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)
