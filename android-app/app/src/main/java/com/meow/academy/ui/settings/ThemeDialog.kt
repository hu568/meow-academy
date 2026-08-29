package com.meow.academy.ui.settings

/**
 * 主题设置对话框：第一行「使用动态配置」开关 + 四档模式单选（跟随系统 / 浅色 / 深色 / 自定义）。
 *
 * 与聊天背景对话框的开关交互一致：
 * - **开关打开** = 进入动态配置模式（ThemeMode.CONFIG），颜色由 appconfig/theme-config.jsonc 管理，隐藏四档单选；
 * - **开关关闭** = 回到四档单选（跟随系统 / 浅色 / 深色 / 自定义），自定义时展开种子色选择器。
 *
 * 关闭开关时恢复到打开开关前选中的模式（避免回到默认系统模式造成跳变）。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meow.academy.data.settings.PRESET_THEME_SEEDS
import com.meow.academy.data.settings.ThemeMode
import com.meow.academy.data.settings.displayName
import com.meow.academy.data.settings.themeSeedFromHex
import com.meow.academy.data.settings.themeSeedToHex

/**
 * 主题对话框：第一行动态配置开关 + 四档模式单选 + 自定义种子色选择器。
 *
 * @param selectedMode 当前主题模式
 * @param seedColor 当前自定义种子色（ARGB Long，仅 CUSTOM 使用）
 * @param onSelectMode 切换模式（即时保存，不关对话框）
 * @param onSelectSeed 选择/输入种子色（即时保存，不关对话框）
 * @param onDismiss 关闭对话框
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeDialog(
    selectedMode: ThemeMode,
    seedColor: Long,
    onSelectMode: (ThemeMode) -> Unit,
    onSelectSeed: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDynamic = selectedMode == ThemeMode.CONFIG
    // 打开开关前选中的模式：关闭开关时恢复它，避免跳到默认系统模式
    var previousMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    val toggleDynamic: (Boolean) -> Unit = { on ->
        if (on) {
            previousMode = selectedMode
            onSelectMode(ThemeMode.CONFIG)
        } else {
            onSelectMode(previousMode)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题") },
        text = {
            // 内容可滚动：四档单选 + 色卡 + HEX 输入总高可能超出对话框上限，
            // 不滚动时 OutlinedTextField 会被垂直压缩、框内文字只显示一半
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── 第一行：使用动态配置开关（与聊天背景对话框一致） ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { toggleDynamic(!isDynamic) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "使用动态配置",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (isDynamic) {
                                "颜色由 appconfig/theme-config.jsonc 管理"
                            } else {
                                "种子色 / 具体色槽可配置，热更即时生效"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isDynamic, onCheckedChange = toggleDynamic)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ── 动态配置模式：隐藏四档单选（颜色由配置文件管理） ──
                if (!isDynamic) {
                    ThemeMode.entries.filter { it != ThemeMode.CONFIG }.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onSelectMode(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == selectedMode,
                                onClick = { onSelectMode(mode) },
                            )
                            Text(
                                text = mode.displayName(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            // 自定义模式：右侧显示当前种子色小圆预览
                            if (mode == ThemeMode.CUSTOM) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(seedColor.toInt())),
                                )
                            }
                        }
                    }

                    // ── 自定义种子色选择器（仅 CUSTOM 展开） ──
                    if (selectedMode == ThemeMode.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "自定义颜色",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 预设色卡：圆形色块，选中的加粗描边
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PRESET_THEME_SEEDS.forEach { preset ->
                                val selected = preset.argb == seedColor
                                val borderColor = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(preset.argb.toInt()))
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = borderColor,
                                            shape = CircleShape,
                                        )
                                        .clickable { onSelectSeed(preset.argb) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Color.White),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // HEX 输入：合法 6/8 位立即保存
                        var hex by remember(seedColor) { mutableStateOf(themeSeedToHex(seedColor)) }
                        OutlinedTextField(
                            value = hex,
                            onValueChange = { input ->
                                hex = input
                                themeSeedFromHex(input)?.let(onSelectSeed)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("HEX 颜色") },
                            placeholder = { Text("#RRGGBB") },
                            supportingText = {
                                Text(
                                    text = themeSeedFromHex(hex)?.let { themeSeedToHex(it) } ?: "格式：#RRGGBB",
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
