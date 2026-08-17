package com.meow.academy.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.rpc.LlmModelInfo
import com.meow.academy.rpc.LlmModelInput
import kotlinx.coroutines.launch

private const val DEEPSEEK_PROVIDER = "deepseek-official"

private sealed interface ModelRoute {
    data object List : ModelRoute
    data class Detail(val provider: String, val isNew: Boolean = false) : ModelRoute
}

@Composable
fun ModelManageScreen(onBack: () -> Unit) {
    val vm: ModelManageViewModel = viewModel(factory = ModelManageViewModel.factory())
    var route by remember { mutableStateOf<ModelRoute>(ModelRoute.List) }

    LaunchedEffect(Unit) { vm.refresh() }

    BackHandler {
        when (route) {
            ModelRoute.List -> onBack()
            is ModelRoute.Detail -> route = ModelRoute.List
        }
    }

    when (val r = route) {
        ModelRoute.List -> ProvidersScreen(
            vm = vm,
            onBack = onBack,
            onOpen = { route = ModelRoute.Detail(it) },
            onAdd = { route = ModelRoute.Detail("", isNew = true) },
        )
        is ModelRoute.Detail -> ProviderDetailScreen(
            vm = vm,
            provider = r.provider,
            isNew = r.isNew,
            onBack = { route = ModelRoute.List },
        )
    }
}

// ───────────────── 主页：provider 列表 ─────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersScreen(
    vm: ModelManageViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val items by vm.items.collectAsState()
    val disabled by vm.disabled.collectAsState()
    val currentProvider by vm.llmProvider.collectAsState()
    val loading by vm.loading.collectAsState()
    var query by remember { mutableStateOf("") }

    val filtered = items.filter { it ->
        val q = query.trim()
        q.isEmpty() || it.key.contains(q, ignoreCase = true) || it.displayName.contains(q, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "添加自定义提供商") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索提供商") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            Text(
                "模型提供商列表",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) "无匹配的提供商" else "暂无提供商",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.key }) { item ->
                        ProviderCard(
                            item = item,
                            enabled = item.isBuiltin || item.key !in disabled,
                            isDefault = item.key == currentProvider || (item.key == DEEPSEEK_PROVIDER && currentProvider == "deepseek"),
                            onClick = { onOpen(item.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    item: ProviderListItem,
    enabled: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Star, "默认", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Spacer(Modifier.height(2.dp))
                val status = when {
                    !item.registered && !item.isBuiltin -> "未配置 · 点进去填 Key 即可用"
                    enabled -> "${item.modelCount} 个模型 · 已启用"
                    else -> "${item.modelCount} 个模型 · 已禁用"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!item.registered && !item.isBuiltin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier.size(10.dp).background(
                    if (item.isBuiltin || (item.registered && enabled)) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────────────── 详情页：配置 / 模型 ─────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailScreen(
    vm: ModelManageViewModel,
    provider: String,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    val items by vm.items.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val disabled by vm.disabled.collectAsState()
    val currentProvider by vm.llmProvider.collectAsState()
    val currentModel by vm.llmModel.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val item = items.firstOrNull { it.key == provider }
    val existing = profiles[provider]
    val isBuiltin = provider == DEEPSEEK_PROVIDER
    val wasRegistered = existing != null

    var displayName by remember(provider) { mutableStateOf(if (isNew) "" else (existing?.displayName ?: item?.displayName ?: provider)) }
    var baseURL by remember(provider) { mutableStateOf(existing?.baseURL ?: item?.baseURL ?: "") }
    var apiKey by remember(provider) { mutableStateOf("") }
    var models by remember(provider) { mutableStateOf<MutableList<ModelProfile>>(existing?.models?.toMutableList() ?: mutableListOf()) }

    var tab by remember(provider) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showAddModel by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelProfile?>(null) }
    var fetchedModels by remember { mutableStateOf<List<LlmModelInfo>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var testingModel by remember { mutableStateOf<String?>(null) }

    val enabled = provider !in disabled

    fun routeKey(): String = if (isNew) slug(displayName) else provider

    fun doSave() {
        scope.launch {
            val key = routeKey()
            if (key.isBlank()) {
                snackbar.showSnackbar("请填写提供商名称")
                return@launch
            }
            busy = true
            val err = vm.saveProvider(
                provider = key,
                displayName = displayName,
                baseURL = baseURL,
                api = "openai-completions",
                models = models.map { LlmModelInput(it.id, it.name, it.contextWindow, it.maxTokens) },
                apiKey = apiKey,
            )
            busy = false
            if (err == null) {
                // 首次配置的 provider 默认禁用，需手动启用
                if (isNew || !wasRegistered) vm.toggleDisabled(key, true)
                snackbar.showSnackbar(if (isNew || !wasRegistered) "已保存（默认禁用，可在配置页启用）" else "已保存")
                vm.refresh()
            } else {
                snackbar.showSnackbar(err)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加提供商" else displayName) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("提供商名称") },
                singleLine = true,
                enabled = !isBuiltin,
            )

            if (!isBuiltin) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("配置") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("模型") })
                }
            }

            when {
                isBuiltin -> BuiltinConfig(vm = vm, currentProvider = currentProvider, currentModel = currentModel, provider = provider)
                tab == 0 -> ConfigTab(
                    isNew = isNew,
                    baseURL = baseURL,
                    onBaseURL = { baseURL = it },
                    apiKey = apiKey,
                    onApiKey = { apiKey = it },
                    enabled = enabled,
                    onToggle = { vm.toggleDisabled(provider, !it) },
                    onSave = { doSave() },
                    onDelete = { showDeleteConfirm = true },
                    busy = busy,
                )
                else -> ModelsTab(
                    models = models,
                    onAddModel = { showAddModel = true },
                    onEditModel = { editingModel = it },
                    onFetch = {
                        scope.launch {
                            busy = true
                            val r = vm.discoverModels(routeKey(), baseURL, apiKey)
                            busy = false
                            r.fold(
                                onSuccess = { fetchedModels = it },
                                onFailure = { e -> snackbar.showSnackbar("获取失败：${e.message}") },
                            )
                        }
                    },
                    onToggleDefault = { m -> vm.toggleDefault(provider, m.id) },
                    onTest = { m ->
                        scope.launch {
                            testingModel = m.id
                            val r = vm.testModel(provider, m.id)
                            testingModel = null
                            val msg = r.fold(
                                onSuccess = { "✅ ${m.id} 连接成功" },
                                onFailure = { e -> "❌ ${m.id} 失败：${e.message}" },
                            )
                            snackbar.showSnackbar(msg)
                        }
                    },
                    currentModel = currentModel,
                    testingModel = testingModel,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除提供商") },
            text = { Text("确定删除「${displayName}」吗？会同时删除其 API Key。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        val err = vm.removeProvider(provider)
                        if (err == null) { vm.refresh(); onBack() } else snackbar.showSnackbar(err)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
        )
    }

    if (showAddModel) {
        var newId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddModel = false },
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
                    if (id.isNotEmpty()) { models = (models + ModelProfile(id = id)).toMutableList(); showAddModel = false }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddModel = false }) { Text("取消") } },
        )
    }

    editingModel?.let { m ->
        var name by remember(m) { mutableStateOf(m.name ?: "") }
        var ctx by remember(m) { mutableStateOf(m.contextWindow?.toString() ?: "") }
        var maxTok by remember(m) { mutableStateOf(m.maxTokens?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { editingModel = null },
            title = { Text("模型设置 · ${m.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                    OutlinedTextField(ctx, { ctx = it }, label = { Text("上下文窗口") }, singleLine = true)
                    OutlinedTextField(maxTok, { maxTok = it }, label = { Text("最大输出 tokens") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = m.copy(name = name.ifBlank { null }, contextWindow = ctx.toIntOrNull(), maxTokens = maxTok.toIntOrNull())
                    models = models.map { if (it.id == m.id) updated else it }.toMutableList()
                    editingModel = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingModel = null }) { Text("取消") } },
        )
    }

    fetchedModels?.let { list ->
        AlertDialog(
            onDismissRequest = { fetchedModels = null },
            title = { Text("获取到 ${list.size} 个模型") },
            text = {
                LazyColumn(Modifier.height(300.dp)) {
                    items(list) { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (models.none { it.id == m.id }) models = (models + ModelProfile(id = m.id, name = m.name)).toMutableList()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val added = models.any { it.id == m.id }
                            Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(m.id, style = MaterialTheme.typography.bodyMedium)
                                if (m.name != m.id) Text(m.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fetchedModels = null }) { Text("完成") } },
        )
    }
}

@Composable
private fun BuiltinConfig(
    vm: ModelManageViewModel,
    currentProvider: String,
    currentModel: String,
    provider: String,
) {
    val apiKey by vm.llmApiKey.collectAsState()
    var keyDraft by remember { mutableStateOf(apiKey) }
    val models = listOf("deepseek-v4-flash" to "DeepSeek-V4-Flash", "deepseek-v4-pro" to "DeepSeek-V4-Pro")
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("内置 DeepSeek 官方直连", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("DeepSeek API Key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = { vm.setApiKey(keyDraft) }, modifier = Modifier.fillMaxWidth()) { Text("保存 Key") }
        Spacer(Modifier.height(4.dp))
        Text("模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        models.forEach { (id, name) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { vm.toggleDefault(provider, id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (currentProvider == DEEPSEEK_PROVIDER && currentModel == id) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "设为默认",
                    tint = if (currentProvider == DEEPSEEK_PROVIDER && currentModel == id) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfigTab(
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
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            placeholder = { Text(if (enabled || isNew) "sk-…（留空则沿用已保存的 Key）" else "sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
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

@Composable
private fun ModelsTab(
    models: List<ModelProfile>,
    onAddModel: () -> Unit,
    onEditModel: (ModelProfile) -> Unit,
    onFetch: () -> Unit,
    onToggleDefault: (ModelProfile) -> Unit,
    onTest: (ModelProfile) -> Unit,
    currentModel: String,
    testingModel: String?,
) {
    Column(Modifier.fillMaxSize()) {
        if (models.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("还没有模型\n点下方「添加新模型」或「获取模型列表」", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(models, key = { it.id }) { m ->
                    ModelCard(
                        model = m,
                        onEdit = { onEditModel(m) },
                        isDefault = currentModel == m.id,
                        onToggleDefault = { onToggleDefault(m) },
                        onTest = { onTest(m) },
                        testing = testingModel == m.id,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAddModel, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加新模型")
            }
            OutlinedButton(onClick = onFetch, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("获取模型列表")
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelProfile,
    onEdit: () -> Unit,
    isDefault: Boolean,
    onToggleDefault: () -> Unit,
    onTest: () -> Unit,
    testing: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(model.name?.take(1)?.uppercase() ?: model.id.take(1).uppercase(), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name ?: model.id, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleDefault) {
                Icon(
                    if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "设为默认",
                    tint = if (isDefault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTest, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, "测试连接")
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Settings, "设置") }
        }
    }
}
