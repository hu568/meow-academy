package com.meow.academy.ui.chat

/**
 * 聊天附件共享工具：附件引用 id / Markdown 链接 / 发送文本构造。
 * ChatScreen 与 ChatViewModel 共用；附件发送给模型时走 contentBlocks（图片）
 * 或 Markdown 链接（其他文件），Room 展示文本始终用 Markdown 形式。
 */

/** 图片扩展名集合（data.files 集中维护），仅这些格式由 `![alt](path)` 渲染为图片块 */
internal fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in com.meow.academy.data.files.IMAGE_EXTENSIONS
}

/**
 * 生成附件引用 id：按扩展名 + 序号，例如 文件.md → md1、第二个 md → md2；
 * 无扩展名用 file1 / file2。
 */
internal fun nextAttachmentRefId(attachments: List<PendingAttachment>, displayName: String): String {
    val ext = displayName.substringAfterLast('.', "").lowercase().ifEmpty { "file" }
    val count = attachments.count { it.refId.startsWith(ext) }
    return "$ext${count + 1}"
}

/**
 * 把上传文件转成聊天里的 Markdown 链接：
 * - 图片 → `![文件名](路径)`（渲染为图片块）；
 * - 其他 → `[文件名](路径)`（可点击链接）。
 * 路径含空格 / 括号时用 `<…>` 包裹链接目标，保证 Markwon/parseStandaloneImage 能完整解析（喵~）。
 */
internal fun markdownLink(name: String, path: String): String {
    val target = if (path.any { it.isWhitespace() || it == '(' || it == ')' }) "<$path>" else path
    return if (isImageFile(name)) "![$name]($target)" else "[$name]($target)"
}

/**
 * 发送前把输入框里的 `[refId]` 引用替换成 Markdown 链接：
 * - 图片 → `![文件名](路径)`（渲染为圆角线框图片块）；
 * - 其他文件 → `[文件名](路径)`（可点击链接）。
 * 如果引用标记被用户删掉，把该附件链接追加到消息末尾，避免附件静默丢失（喵~）。
 */
internal fun buildMessageWithAttachments(input: String, attachments: List<PendingAttachment>): String {
    var text = input
    attachments.forEach { att ->
        val ref = "[${att.refId}]"
        val link = markdownLink(att.displayName, att.path)
        text = if (ref in text) text.replace(ref, link) else "$text\n\n$link"
    }
    return text.trim()
}

/**
 * 构造发送给 DSH 的纯文本 content（不含可上传图片；图片走 image 块）：
 * 把非图片附件（含 bmp/svg 等后端不收的格式）转成 Markdown 链接拼入正文，
 * 可上传图片附件由调用方单独走 attachImages。
 */
internal fun buildTextContentWithNonImages(input: String, attachments: List<PendingAttachment>): String {
    var text = input
    attachments.filterNot { isImageUploadable(it.displayName) }.forEach { att ->
        val ref = "[${att.refId}]"
        val link = markdownLink(att.displayName, att.path)
        text = if (ref in text) text.replace(ref, link) else "$text\n\n$link"
    }
    return text.trim()
}
