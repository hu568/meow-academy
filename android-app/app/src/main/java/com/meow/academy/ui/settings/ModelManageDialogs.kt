package com.meow.academy.ui.settings

/**
 * 模型管理页的 5 种对话框组件：
 * 删除提供商确认 / 删除模型确认 / 添加模型 / 编辑模型 / 获取到的远端模型列表。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.ModelProfile
import com.meow.academy.rpc.LlmModelInfo

/** 删除提供商确认对话框 */
@Composable
fun DeleteProviderDialog(
    displayName: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除提供商") },
        text = { Text("确定删除「" + displayName + "」吗？会同时删除其 API Key。") },
        confirmButton = {
            TextButton(onClick = { onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除模型确认对话框 */
@Composable
fun DeleteModelDialog(
    model: ModelProfile,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除模型") },
        text = { Text("确定删除「" + (model.name ?: model.id) + "」（" + model.id + "）吗？") },
        confirmButton = {
            TextButton(onClick = { onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 手动添加模型对话框（输入模型 ID） */
@Composable
fun AddModelDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加新模型") },
        text = {
            Column {
                OutlinedTextField(newId, { newId = it }, label = { Text("模型 ID") }, singleLine = true)
                Text("如 gpt-4o / moonshot-v1-8k", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = newId.trim()
                if (id.isNotEmpty()) {
                    onAdd(id)
                    onDismiss()
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 思考档位全集（pi-ai ModelThinkingLevel；off 恒定包含且不可取消） */
private val EFFORT_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** 档位中文短标签（对话框 chips 用） */
private val EFFORT_LEVEL_LABELS = mapOf(
    "off" to "关",
    "minimal" to "极简",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "超高",
    "max" to "最强",
)

/** 思考档位声明模板（key=档位，value=wire 拼写；off 固定 null=不发参数） */
private val REASONING_TEMPLATES: List<Pair<String, Map<String, String?>>> = listOf(
    "OpenAI 系" to mapOf("off" to null, "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high"),
    "DeepSeek 系" to mapOf("off" to null, "low" to "low", "high" to "high", "max" to "max"),
    "Qwen" to mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high"),
    "GLM / 智谱" to mapOf("off" to null, "high" to "high"),
)

/** 编辑模型对话框（显示名称 / 上下文窗口 / 最大输出 tokens / 图片输入开关 / 思考强度 / 测试连接） */
@Composable
fun EditModelDialog(
    model: ModelProfile,
    onSave: (ModelProfile) -> Unit,
    onDismiss: () -> Unit,
    onTest: (() -> Unit)? = null,
    testing: Boolean = false,
) {
    var name by remember(model) { mutableStateOf(model.name ?: "") }
    var ctx by remember(model) { mutableStateOf(model.contextWindow?.toString() ?: "") }
    var maxTok by remember(model) { mutableStateOf(model.maxTokens?.toString() ?: "") }
    var supportsImage by remember(model) { mutableStateOf(model.input?.contains("image") ?: true) }
    // 思考档位声明：null=不声明（模型按无思考能力处理）；非空=支持思考（off 恒在，value=null）
    var reasoningOn by remember(model) { mutableStateOf(model.reasoningEfforts != null) }
    var efforts by remember(model) {
        mutableStateOf(model.reasoningEfforts ?: mapOf("off" to null, "low" to "low", "high" to "high"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型设置 · " + model.id) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(ctx, { ctx = it }, label = { Text("上下文窗口") }, singleLine = true)
                OutlinedTextField(maxTok, { maxTok = it }, label = { Text("最大输出 tokens") }, singleLine = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("支持图片输入（多模态）", style = MaterialTheme.typography.bodyLarge)
                        Text("开启后聊天页可将图片以视觉块发送给模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = supportsImage, onCheckedChange = { supportsImage = it })
                }
                // ── 思考强度声明（第三方 provider 的模型默认无声明，需在此开启） ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("支持思考强度", style = MaterialTheme.typography.bodyLarge)
                        Text("开启后聊天页可切换思考档位（thinkingFormat 在提供商配置页选择）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = reasoningOn, onCheckedChange = { reasoningOn = it })
                }
                if (reasoningOn) {
                    Text(
                        "思考档位（「关」恒定包含，代表关闭思考）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        EFFORT_LEVELS.forEach { level ->
                            FilterChip(
                                selected = level in efforts,
                                // off 恒定选中：DSH 侧未声明 off 时「关闭思考」档会直接不可用
                                enabled = level != "off",
                                onClick = {
                                    efforts = if (level in efforts) efforts - level else efforts + (level to level)
                                },
                                label = { Text(EFFORT_LEVEL_LABELS[level] ?: level) },
                            )
                        }
                    }
                    Text(
                        "各档位 wire 值（一般保持默认即可；「关」固定不发参数）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EFFORT_LEVELS.filter { it != "off" && it in efforts }.forEach { level ->
                        OutlinedTextField(
                            value = efforts[level] ?: level,
                            onValueChange = { efforts = efforts + (level to it.ifBlank { level }) },
                            label = { Text(EFFORT_LEVEL_LABELS[level] ?: level) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "套用模板（同时填好上参数据）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        REASONING_TEMPLATES.forEach { (label, template) ->
                            AssistChip(
                                onClick = { efforts = template },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (onTest != null) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 保留 image 之外的既有模态，只切换 image 的开关
                val baseInput = model.input?.filter { it != "image" }?.ifEmpty { listOf("text") } ?: listOf("text")
                val input = if (supportsImage) (baseInput + "image").distinct() else baseInput
                val updated = model.copy(
                    name = name.ifBlank { null },
                    contextWindow = ctx.toIntOrNull(),
                    maxTokens = maxTok.toIntOrNull(),
                    input = input,
                    // off 恒定写入（value=null：DSH 语义 = 支持该档但不发参数）；仅勾「关」也合法（只能开/关思考）
                    reasoningEfforts = if (reasoningOn) efforts + ("off" to null) else null,
                )
                onSave(updated)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 获取到的远端模型列表对话框（点击条目添加到 models） */
@Composable
fun FetchedModelsDialog(
    models: List<LlmModelInfo>,
    added: Set<String>,
    onAdd: (LlmModelInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("获取到 " + models.size + " 个模型") },
        text = {
            LazyColumn(Modifier.height(300.dp)) {
                items(models) { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onAdd(m) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isAdded = m.id in added
                        Icon(if (isAdded) Icons.Filled.Check else Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.id, style = MaterialTheme.typography.bodyMedium)
                            val displayName = m.name
                            if (displayName != null && displayName != m.id) {
                                Text(displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
