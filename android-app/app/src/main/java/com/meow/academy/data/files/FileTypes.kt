package com.meow.academy.data.files

/**
 * 文件类型扩展名常量（喵~）。
 *
 * 图片扩展名同时被文件列表图标分类（[com.meow.academy.ui.files.fileKindOf]）
 * 和打开判定（[FileRepository.isImageFile]）引用，集中放一起避免两处漂移。
 */
val IMAGE_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp",
    "svg", "ico", "heic", "avif",
)