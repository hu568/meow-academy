package com.meow.academy.data.files

/**
 * 搜索结果条目。
 *
 * @property path 绝对路径
 * @property relativePath 相对搜索根目录的路径（如 `notes/plan-phase2.md`）
 */
data class FileSearchResult(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val relativePath: String,
)
