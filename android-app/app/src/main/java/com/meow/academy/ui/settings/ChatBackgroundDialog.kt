package com.meow.academy.ui.settings

/**
 * 聊天背景设置对话框：无背景 / 内置渐变预设 / 从相册选择图片。
 *
 * 交互与 ThemeDialog 一致：点选即保存（不关框），便于边选边看；
 * 相册图片会先拷贝到 App 私有目录（filesDir/chat-bg/），避免 content URI 授权失效。
 */

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meow.academy.R
import com.meow.academy.data.settings.CHAT_BG_NONE
import com.meow.academy.data.settings.CHAT_BG_PRESETS
import com.meow.academy.data.settings.copyImageToAppStorage

/**
 * 聊天背景对话框。
 *
 * @param current 当前持久化字符串（"none" / "preset:<id>" / "file:<absPath>"）
 * @param onSelect 选中即回调（由调用方持久化，不自动关闭）
 * @param onDismiss 关闭对话框
 */
@Composable
fun ChatBackgroundDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 用本地 State 承接“当前选中项”：AlertDialog 窗口内容有时不会随父级参数变化重组，
    // 点选后立即改本地状态，保证 RadioButton 勾选效果即时刷新（持久化仍由 onSelect 负责）。
    var selectedRaw by remember(current) { mutableStateOf(current) }
    val choose: (String) -> Unit = { raw ->
        selectedRaw = raw
        onSelect(raw)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val saved = copyImageToAppStorage(context, uri)
            if (saved != null) {
                choose(saved)
            } else {
                Toast.makeText(context, "保存图片失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("聊天背景") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                BgOptionRow(
                    label = stringResource(R.string.chat_bg_none),
                    selected = selectedRaw == CHAT_BG_NONE,
                    preview = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                    },
                    onClick = { choose(CHAT_BG_NONE) },
                )
                CHAT_BG_PRESETS.forEach { preset ->
                    BgOptionRow(
                        label = preset.name,
                        selected = selectedRaw == "preset:${preset.id}",
                        preview = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            preset.argbColors.map { Color(it.toInt()) },
                                        ),
                                    ),
                            )
                        },
                        onClick = { choose("preset:${preset.id}") },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                BgOptionRow(
                    label = stringResource(R.string.chat_bg_custom_image),
                    selected = selectedRaw.startsWith("file:"),
                    preview = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.AddPhotoAlternate,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                    onClick = { launcher.launch("image/*") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 背景选项行：预览块 + 名称 + 单选点 */
@Composable
private fun BgOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        preview()
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}
