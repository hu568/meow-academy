package com.meow.academy.ui.settings

/**
 * 主题设置对话框：四档模式单选 + 「自定义」时展开种子色选择器。
 *
 * 底层设计：用户只选一个种子色（预设色卡 / HEX 输入），浅色/深色整套色板
 * 由 ui/theme/CustomColorScheme.kt 自动派生；选色即时保存（DataStore），
 * MainActivity 收集后实时生效，方便边选边看。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * 主题对话框：单选模式 + 自定义种子色选择器。
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题") },
        text = {
            Column {
                // ── 四档模式单选 ──
                ThemeMode.entries.forEach { mode ->
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
