package com.meow.academy.ui.settings

/**
 * 聊天背景设置对话框：无背景 / 渐变预设 / 从相册选择图片 + 「使用动态配置」开关。
 *
 * 交互与 ThemeDialog 一致：点选即保存（不关框），便于边选边看。
 *
 * 两种管理模式：
 * - **简单模式（不勾选）**：预设用 Kotlin 内置，自定义图片拷贝到 `appconfig/images/bg.jpg`
 *   （固定文件名，直接覆盖替换），选择结果写 DataStore；
 * - **动态配置模式（勾选）**：预设来自 theme-config.jsonc `backgrounds.presets`，
 *   自定义图片拷贝到 `appconfig/images/`（时间戳文件名），选择结果写回 JSONC `backgrounds.active`。
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
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import com.meow.academy.data.settings.CHAT_BG_FIXED_FILE_NAME
import com.meow.academy.data.settings.CHAT_BG_NONE
import com.meow.academy.data.settings.ChatBgPreset
import com.meow.academy.data.settings.copyImageDynamicToAppStorage
import com.meow.academy.data.settings.copyImageToAppStorage
import com.meow.academy.data.settings.fixedChatBgRaw

/**
 * 聊天背景对话框。
 *
 * @param current        当前持久化字符串（简单模式 DataStore / 动态模式 JSONC active）
 * @param presets        当前模式下的可用预设列表
 * @param dynamicEnabled 「使用动态配置」开关当前状态
 * @param onToggleDynamic 切换开关（由调用方持久化）
 * @param onSelect       选中即回调（由调用方按模式写入 DataStore / JSONC，不自动关闭）
 * @param onDismiss      关闭对话框
 */
@Composable
fun ChatBackgroundDialog(
    current: String,
    presets: List<ChatBgPreset>,
    dynamicEnabled: Boolean,
    onToggleDynamic: (Boolean) -> Unit,
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
            // 动态模式：时间戳文件名；简单模式：固定文件名（覆盖替换）
            val saved = if (dynamicEnabled) {
                copyImageDynamicToAppStorage(context, uri)
            } else {
                copyImageToAppStorage(context, uri)
            }
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
                // ── 使用动态配置开关 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onToggleDynamic(!dynamicEnabled) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "使用动态配置",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (dynamicEnabled) {
                                "背景由 appconfig/theme-config.jsonc 管理"
                            } else {
                                "简单模式：固定文件 bg.jpg"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = dynamicEnabled, onCheckedChange = onToggleDynamic)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 动态配置模式：折叠所有背景选项，背景由配置文件管理（与主题对话框一致）
                if (dynamicEnabled) {
                    Text(
                        text = "动态配置模式下，背景由 appconfig/theme-config.jsonc 管理。\n" +
                            "可让 AI 修改 backgrounds.active，或放置图片到 appconfig/images/。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
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
                    presets.forEach { preset ->
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
                    // 自定义图片：点击卡片 = 直接应用已有的图片（不弹相册）；旁边「上传」按钮 = 覆盖/新增图片
                    val customImageRaw = fixedChatBgRaw()
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
                        // 简单模式固定文件始终存在引用：点击卡片直接应用 bg.jpg
                        onClick = { choose(customImageRaw) },
                        trailing = {
                            IconButton(onClick = { launcher.launch("image/*") }) {
                                Icon(
                                    Icons.Outlined.Upload,
                                    contentDescription = "上传图片",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            RadioButton(
                                selected = selectedRaw.startsWith("file:"),
                                onClick = { choose(customImageRaw) },
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 背景选项行：预览块 + 名称 + 尾随组件（默认单选点；自定义图片行传上传按钮 + 单选点） */
@Composable
private fun BgOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
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
        if (trailing != null) {
            trailing()
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
