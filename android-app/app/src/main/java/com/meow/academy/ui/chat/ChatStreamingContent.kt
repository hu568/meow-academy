package com.meow.academy.ui.chat

import android.util.Log
import com.meow.academy.rpc.DshError
import com.meow.academy.rpc.DshParams
import com.meow.academy.rpc.DshRpcClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** jsonrpc error.code：预设不存在（data.available 有可用列表；message 本身也含，plan-standard-mode §三.8） */
private const val ERR_PRESET_UNKNOWN = -32001

/** jsonrpc error.code：预设存在但组合挂载失败（data.detail = 逐行原因） */
private const val ERR_PRESET_MOUNT_FAILED = -32002

/**
 * 构造发送给 DSH 的 contentBlocks：
 * - 非图片附件 → Markdown 链接拼入 text 块；
 * - 图片附件 → 读文件转 base64 → session/attachImages 拿 durable refs → image 块。
 *
 * @return null 表示图片准备/上传失败，调用方应回退为纯文本发送（模型看不到图但不丢消息）。
 */
internal suspend fun buildContentBlocks(
    promptText: String,
    attachments: List<PendingAttachment>,
    rpc: DshRpcClient,
    modelController: ChatModelController,
): List<DshParams.ContentBlock>? {
    // 只把后端支持的图片（jpeg/png/webp/gif）走 attachImages；bmp/svg 等按普通附件 Markdown 发送
    val imageAttachments = attachments.filter { isImageUploadable(it.displayName) }
    // 没有图片：非图片附件转 Markdown 拼入文本块即可
    if (imageAttachments.isEmpty()) {
        val text = buildTextContentWithNonImages(promptText, attachments)
        return listOf(DshParams.ContentBlock(type = "text", text = text))
    }

    // 当前模型明确不支持图片 → 直接回退 Markdown（不发起无谓的 attachImages）
    val supportsImage = modelController.modelCatalogMap[modelController.llmModel.value]?.supportsImage ?: true
    if (!supportsImage) {
        Log.w("ChatStreaming", "model ${modelController.llmModel.value} does not support image input, fallback to markdown")
        return null
    }

    // 读 limits（失败也能继续：用默认无限制流程，后端仍会兜底校验）
    val limits = runCatching { ImageLimits.from(rpc.imageLimits()) }.getOrNull()

    // 批量读文件 → base64（超限自动压缩）
    val prepared = prepareImageUploads(imageAttachments, limits)
    val failed = prepared.filter { it.second.isFailure }
    if (failed.isNotEmpty()) {
        Log.w(
            "ChatStreaming",
            "image prepare failed: " + failed.joinToString { "${it.first.displayName}: ${it.second.exceptionOrNull()?.message}" },
        )
        return null
    }
    val uploads = prepared.map { it.second.getOrThrow() }
    if (limits != null && uploads.size > limits.maxImagesPerMessage) {
        Log.w("ChatStreaming", "too many images: ${uploads.size} > ${limits.maxImagesPerMessage}")
        return null
    }

    // 调后端 attachImages → durable refs
    val refs = rpc.attachImages(uploads)
    if (refs == null || refs.size != uploads.size) {
        Log.w("ChatStreaming", "attachImages failed or ref count mismatch")
        return null
    }

    val blocks = mutableListOf<DshParams.ContentBlock>()
    val text = buildTextContentWithNonImages(promptText, attachments)
    if (text.isNotBlank()) {
        blocks += DshParams.ContentBlock(type = "text", text = text)
    }
    refs.forEach { ref ->
        blocks += DshParams.ContentBlock(type = "image", attachment = ref)
    }
    return blocks
}

/**
 * prompt 受理失败的错误透传文本（plan-standard-mode §5.9）。
 * PRESET_UNKNOWN 的 data.available = 可用预设列表；PRESET_MOUNT_FAILED 的 data.detail = 逐行原因
 * （meow-jsonrpc 的 MeowRpcError 结构化载荷），都拼进气泡让错误可读。
 */
internal fun describePromptError(error: DshError): String = buildString {
    append(error.message.ifBlank { "prompt 被拒绝喵（未知错误）" })
    val data = error.data
    if (error.code == ERR_PRESET_UNKNOWN) {
        val available = data?.get("available") as? JsonArray
        if (available != null && available.isNotEmpty()) {
            append("\n可用预设：")
            available.forEachIndexed { i, item ->
                if (i > 0) append("、")
                append(item.jsonPrimitive.contentOrNull ?: item.toString())
            }
        }
    }
    if (error.code == ERR_PRESET_MOUNT_FAILED) {
        val detail = data?.get("detail")?.jsonPrimitive?.contentOrNull
        if (!detail.isNullOrBlank()) append("\n挂载详情：\n").append(detail)
    }
    if (error.code == ERR_PRESET_UNKNOWN || error.code == ERR_PRESET_MOUNT_FAILED) {
        append("\n\n到右侧看板 → 工作设置 → Agent 预设 检查或切换默认喵~")
    }
}
