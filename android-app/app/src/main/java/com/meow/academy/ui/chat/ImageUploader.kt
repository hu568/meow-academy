package com.meow.academy.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import com.meow.academy.rpc.DshParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片上传工具：读取文件 → 按 limits 压缩 → canonical base64。
 * 供 ChatViewModel 发送图片附件时使用喵~
 */

/** 后端 imageLimits 响应解析 */
data class ImageLimits(
    val maxImageBytes: Int,           // 单张图片最大字节数
    val maxImagesPerMessage: Int,     // 单条消息最多图片数
    val maxMessageImageBytes: Int,    // 单条消息图片总字节上限
    val maxImagePixels: Long,         // 单张图片最大像素数
    val maxImageDimension: Int,       // 单边最大像素
    val mediaTypes: Set<String>,      // 支持的媒体类型（image/jpeg, image/png, image/webp, image/gif）
) {
    companion object {
        fun from(json: JsonObject?): ImageLimits? {
            if (json == null) return null
            val limits = json["imageLimits"] as? JsonObject ?: return null
            val maxImageBytes = limits["maxImageBytes"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val maxImagesPerMessage = limits["maxImagesPerMessage"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val maxMessageImageBytes = limits["maxMessageImageBytes"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val maxImagePixels = limits["maxImagePixels"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            val maxImageDimension = limits["maxImageDimension"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val mediaTypes = (limits["mediaTypes"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content }
                ?.toSet() ?: return null
            return ImageLimits(
                maxImageBytes = maxImageBytes,
                maxImagesPerMessage = maxImagesPerMessage,
                maxMessageImageBytes = maxMessageImageBytes,
                maxImagePixels = maxImagePixels,
                maxImageDimension = maxImageDimension,
                mediaTypes = mediaTypes,
            )
        }
    }
}

/** 扩展名 → mediaType 映射 */
private fun mediaTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> null
}

/**
 * 是否为后端 attachImages 支持的图片类型（jpeg/png/webp/gif）。
 * 注意：UI 的 [isImageFile] 范围更大（bmp/svg/heic/avif 也能渲染），但后端只收这四种。
 */
fun isImageUploadable(name: String): Boolean = mediaTypeOf(name) != null

/**
 * 获取图片的原始宽高（不解码整图）。
 * @return (width, height) 或 null（无法解析）
 */
private fun getImageDimensions(file: File): Pair<Int, Int>? {
    try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return opts.outWidth to opts.outHeight
        }
    } catch (_: Exception) {}
    return null
}

/**
 * 读取图片 EXIF 旋转角度（0/90/180/270）。
 */
private fun getExifRotation(file: File): Int {
    return try {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) { 0 }
}

/**
 * 计算合适的采样率，使解码后的图片不超过 maxDimension 和 maxPixels。
 */
private fun calcSampleSize(width: Int, height: Int, maxDimension: Int, maxPixels: Long): Int {
    var sampleSize = 1
    // 单边超限
    while ((width / sampleSize) > maxDimension || (height / sampleSize) > maxDimension) {
        sampleSize *= 2
    }
    // 总像素超限
    while ((width.toLong() / sampleSize) * (height.toLong() / sampleSize) > maxPixels) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * 把图片文件转为 DSH 可接受的 base64 上传格式。
 * 如果 limits 不为空，按限制压缩图片；否则直接编码原始字节。
 *
 * @return 失败时返回错误信息；成功返回 [DshParams.ImageUpload]
 */
fun prepareImageUpload(file: File, limits: ImageLimits?): Result<DshParams.ImageUpload> {
    return runCatching {
        if (!file.exists()) error("文件不存在: ${file.name}")
        val mediaType = mediaTypeOf(file.name) ?: error("不支持的文件类型: ${file.name}")
        if (limits != null && mediaType !in limits.mediaTypes) {
            error("不支持的文件类型 ${mediaType}，支持: ${limits.mediaTypes.joinToString()}")
        }

        // GIF 特殊处理：不压缩，只检查大小
        if (mediaType == "image/gif") {
            val rawBytes = file.readBytes()
            if (limits != null && rawBytes.size > limits.maxImageBytes) {
                error("GIF 文件过大（${rawBytes.size} bytes > ${limits.maxImageBytes}）")
            }
            val base64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
            return@runCatching DshParams.ImageUpload(
                mediaType = mediaType,
                data = base64,
                name = file.name,
            )
        }

        // 读取原始尺寸
        val dims = getImageDimensions(file)
        val rawBytes = file.readBytes()

        // 无限制或原始尺寸/字节已满足限制 → 直接编码
        if (limits == null) {
            val base64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
            return@runCatching DshParams.ImageUpload(
                mediaType = mediaType,
                data = base64,
                name = file.name,
            )
        }
        if (rawBytes.size <= limits.maxImageBytes && dims != null) {
            val (w, h) = dims
            if (w <= limits.maxImageDimension && h <= limits.maxImageDimension &&
                w.toLong() * h.toLong() <= limits.maxImagePixels) {
                val base64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                return@runCatching DshParams.ImageUpload(
                    mediaType = mediaType,
                    data = base64,
                    name = file.name,
                )
            }
        }

        // 需要解码 + 压缩
        val exifRotation = getExifRotation(file)
        val sampleSize = if (dims != null) {
            calcSampleSize(dims.first, dims.second, limits.maxImageDimension, limits.maxImagePixels)
        } else 1
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("无法解码图片: ${file.name}")

        // 应用 EXIF 旋转
        val rotated = if (exifRotation != 0) {
            val matrix = Matrix().apply { postRotate(exifRotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it != bitmap) bitmap.recycle() }
        } else bitmap

        // 按输出质量压缩（JPEG 通常比 WebP/PNG 更小，优先用 JPEG）
        val outputMediaType = if (mediaType == "image/png" || mediaType == "image/webp") {
            // 尝试 JPEG 压缩（更小），但需要检查是否有透明度
            val hasAlpha = rotated.hasAlpha()
            if (hasAlpha) "image/png" else "image/jpeg"
        } else "image/jpeg"

        @Suppress("DEPRECATION")
        val format = when (outputMediaType) {
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }

        // 自适应质量：从 85 开始，如果超限则降低质量
        var quality = 85
        var compressedBytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            rotated.compress(format, quality, out)
            compressedBytes = out.toByteArray()
            if (compressedBytes.size <= limits.maxImageBytes || quality <= 30) break
            quality -= 5
        }
        rotated.recycle()

        if (compressedBytes.size > limits.maxImageBytes) {
            error("压缩后仍超出大小限制（${compressedBytes.size} > ${limits.maxImageBytes}），请选择更小的图片")
        }

        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        DshParams.ImageUpload(
            mediaType = outputMediaType,
            data = base64,
            name = file.name,
        )
    }
}

/**
 * 批量准备图片上传：过滤非图片文件，返回图片的 upload 列表。
 * 非图片文件不处理，由调用方以 Markdown 链接方式发送。
 */
fun prepareImageUploads(
    attachments: List<PendingAttachment>,
    limits: ImageLimits?,
): List<Pair<PendingAttachment, Result<DshParams.ImageUpload>>> {
    return attachments
        .filter { isImageUploadable(it.displayName) }
        .map { att -> att to prepareImageUpload(File(att.path), limits) }
}