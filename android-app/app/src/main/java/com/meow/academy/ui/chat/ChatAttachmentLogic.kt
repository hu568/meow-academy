package com.meow.academy.ui.chat

/**
 * 附件纯变换（plan-chatscreen-refactor §2.4）。
 * 只收纯变换——输入附件/输入框状态，输出新状态；`repository.importDeduplicated`、
 * `snackbarHostState.showSnackbar`、`scope.launch`、`quickVm.recordRecent` 等 IO 与副作用
 * 全部留薄壳回调（对齐 FileEditor「IO/Toast 留薄壳」先例，不做形式主义的"纯函数"包装）。
 */

import com.meow.academy.data.files.FileEntry

object ChatAttachmentLogic {

    /**
     * 快捷文件点击：有则删（清输入框标记）、无则加。
     * 返回（新附件列表, 新输入框文本）；「记最近使用」副作用由薄壳先按 existingByPath 判定再调本函数。
     */
    fun toggleAttach(
        file: FileEntry,
        attachments: List<PendingAttachment>,
        input: String,
    ): Pair<List<PendingAttachment>, String> {
        val existing = existingByPath(attachments, file.path)
        return if (existing == null) {
            (attachments + buildNew(nextAttachmentRefId(attachments, file.name), file.name, file.path)) to input
        } else {
            removeRef(existing.refId, attachments, input)
        }
    }

    /** 附件预览点击：在输入框插入 [refId] 引用标记（空输入不加前导空格） */
    fun insertRef(input: String, refId: String): String =
        if (input.isBlank()) "[$refId]" else "$input [$refId]"

    /** 附件移除：删附件 + 清输入框里对应 [refId] 标记（返回新附件列表 + 新输入框文本） */
    fun removeRef(
        refId: String,
        attachments: List<PendingAttachment>,
        input: String,
    ): Pair<List<PendingAttachment>, String> {
        val newAttachments = attachments.filterNot { it.refId == refId }
        val newInput = input.replace("[$refId]", "").replace(Regex("\\s+"), " ").trim()
        return newAttachments to newInput
    }

    /** 去重判定：附件列表里是否已有同路径（filePicker 静默跳过 vs 失败弹 Snackbar 的分支判定） */
    fun existingByPath(attachments: List<PendingAttachment>, path: String): PendingAttachment? =
        attachments.firstOrNull { it.path == path }

    /** 新附件构造（refId 由 nextAttachmentRefId 复用，ChatAttachments.kt 已有） */
    fun buildNew(refId: String, name: String, path: String): PendingAttachment =
        PendingAttachment(refId, name, path)
}
