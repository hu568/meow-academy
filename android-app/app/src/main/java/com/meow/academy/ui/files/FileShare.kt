package com.meow.academy.ui.files

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 📤 文件分享（导出）工具（喵~）
 *
 * 把 App 私有目录里的本地文件经 FileProvider 转成 content:// Uri，
 * 并构造系统分享 Intent：
 * - 单个文件 → ACTION_SEND（type 按扩展名猜具体 MIME，接收方能直接打开）；
 * - 多个文件 → ACTION_SEND_MULTIPLE（type 用通配符，多选/目录已在 [FileRepository.zipForShare]
 *   压成单个 zip，所以走到这里的一般只有 1 个；保留多文件分支以防将来直发多个）。
 *
 * 关键：App 私有目录文件不能直接用 file:// 交给外部 App（Android 7+ 抛 FileUriExposedException），
 * 必须经 FileProvider 换成 content:// 并带 FLAG_GRANT_READ_URI_PERMISSION 临时读授权（喵~）。
 */
object FileShare {

    /** FileProvider authority，与 AndroidManifest 的 `${applicationId}.fileprovider` 对应 */
    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** 本地文件 → content:// Uri；落在 FileProvider 声明范围之外返回 null（正常不会） */
    fun shareUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, authority(context), file)
    }.getOrNull()

    /** 构造分享 Intent；文件列表为空或全部转换失败返回 null */
    fun buildShareIntent(context: Context, files: List<File>): Intent? {
        val uris = files.mapNotNull { shareUri(context, it) }
        if (uris.isEmpty()) return null
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(uris.single())
                putExtra(Intent.EXTRA_STREAM, uris.single())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 让接收方在 Intent 上也能读到完整 ClipData 的 MIME（多文件时各条目更精确）
            clipData = ClipData.newRawUri(null, uris.first())
        }
    }

    /**
     * 拉起系统分享面板（chooser）。返回 null 表示成功；非 null 为失败原因（用于 snackbar 反馈）。
     * 自动处理 context 是 Activity 与否：不是则补 NEW_TASK（chooser 起新任务）。
     */
    fun startShare(context: Context, files: List<File>): String? {
        val intent = buildShareIntent(context, files) ?: return "分享失败：无法生成分享链接"
        val chooser = Intent.createChooser(intent, chooserTitle(files))
        val activity = context.findActivity()
        return try {
            if (activity != null) {
                activity.startActivity(chooser)
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
            null
        } catch (e: Exception) {
            "没有可处理此分享的应用"
        }
    }

    /** 分享面板标题：1 个文件用文件名，多个用「N 个文件」 */
    fun chooserTitle(files: List<File>): String =
        if (files.size == 1) "分享「${files.first().name}」" else "分享 ${files.size} 个文件"

    /** 按扩展名猜 MIME；猜不到回退 text/plain（zip 等二进制会落在 MimeTypeMap 内） */
    private fun mimeOf(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "text/plain"
    }

    /** 沿 ContextWrapper 链向上找 Activity；找到返回，找不到返回 null */
    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}