package com.meow.academy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.DEEPSEEK_PROVIDER
import com.meow.academy.data.model.DEFAULT_DEEPSEEK_MODELS
import com.meow.academy.data.model.ModelProfile

/** API Key 输入框：默认密码态，点击小眼睛切换明文/密文 */
@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key",
                )
            }
        },
    )
}

/** 内置 DeepSeek 官方直连配置（API Key + 模型目录：优先后端 llm/models 权威列表，未就绪回退默认目录） */
@Composable
fun BuiltinConfig(
    vm: ModelManageViewModel,
    currentProvider: String,
    currentModel: String,
    provider: String,
) {
    val apiKey by vm.llmApiKey.collectAsState()
    val runtimeModels by vm.builtinModels.collectAsState()
    val modelsLoading by vm.builtinModelsLoading.collectAsState()
    var keyDraft by remember { mutableStateOf(apiKey) }
    // DataStore 异步加载晚于首帧时补填已保存 Key（用户已输入则不覆盖）
    LaunchedEffect(apiKey) {
        if (keyDraft.isBlank()) keyDraft = apiKey
    }
    // 进入页面自动拉取后端模型目录（llm/models）；DSH 未就绪时展示默认目录
    LaunchedEffect(Unit) {
        if (runtimeModels.isEmpty()) vm.refreshBuiltinModels()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("内置 DeepSeek 官方直连", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ApiKeyField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("DeepSeek API Key") },
            placeholder = { Text("sk-…") },
        )
        Button(onClick = { vm.setApiKey(keyDraft) }, modifier = Modifier.fillMaxWidth()) { Text("保存 Key") }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.refreshBuiltinModels() }, enabled = !modelsLoading) {
                if (modelsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(if (modelsLoading) "获取中…" else "获取模型列表")
            }
        }
        val models: List<ModelProfile> = if (runtimeModels.isEmpty()) DEFAULT_DEEPSEEK_MODELS
            else runtimeModels.map { ModelProfile(id = it.id, name = it.name, input = it.inputModalities) }
        if (runtimeModels.isEmpty() && !modelsLoading) {
            Text(
                "DSH 未就绪，暂显示默认模型目录；点「获取模型列表」从后端同步",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        models.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { vm.toggleDefault(provider, m.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name ?: m.id, style = MaterialTheme.typography.bodyLarge)
                        if (m.supportsImage) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "多模态",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                    Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (currentProvider == DEEPSEEK_PROVIDER && currentModel == m.id) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "设为默认",
                    tint = if (currentProvider == DEEPSEEK_PROVIDER && currentModel == m.id) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 自定义 provider 的配置表单（Base URL / API Key / 启用开关 / 保存删除） */
@Composable
fun ConfigTab(
    isNew: Boolean,
    baseURL: String,
    onBaseURL: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = baseURL,
            onValueChange = onBaseURL,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL（含 /v1）") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
        )
        ApiKeyField(
            value = apiKey,
            onValueChange = onApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            placeholder = { Text(if (enabled || isNew) "sk-…（留空则沿用已保存的 Key）" else "sk-…") },
        )
        Text(
            "协议：OpenAI 兼容（/chat/completions、/models 由运行时自动拼接）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isNew) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("是否启用", style = MaterialTheme.typography.bodyLarge)
                    Text("禁用后不出现在聊天页切换列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        } else {
            Text("新增的提供商默认禁用，保存后可在配置页手动启用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f), enabled = !busy) { Text(if (busy) "保存中…" else "保存") }
        }
    }
}
