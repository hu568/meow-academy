package com.meow.academy.ui.settings

/**
 * 模型管理详情页：配置/模型页签 + 保存/删除/获取/测试操作编排。
 * 对话框（ModelManageDialogs.kt）与表单（ProviderForms.kt / ModelListTab.kt）独立成文件。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.DEEPSEEK_PROVIDER
import com.meow.academy.data.model.ModelProfile
import com.meow.academy.data.model.slug
import com.meow.academy.rpc.LlmModelInfo
import com.meow.academy.rpc.LlmModelInput
import kotlinx.coroutines.launch

/** 详情页：配置 / 模型页签 + 保存/删除/获取/测试等操作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
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
    val savedApiKeys by vm.providerApiKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val item = items.firstOrNull { it.key == provider }
    val existing = profiles[provider]
    val isBuiltin = provider == DEEPSEEK_PROVIDER
    val wasRegistered = existing != null

    var displayName by remember(provider) { mutableStateOf(if (isNew) "" else (existing?.displayName ?: item?.displayName ?: provider)) }
    var baseURL by remember(provider) { mutableStateOf(existing?.baseURL ?: item?.baseURL ?: "") }
    var apiKey by remember(provider) { mutableStateOf(savedApiKeys[provider] ?: "") }
    var models by remember(provider) { mutableStateOf<MutableList<ModelProfile>>(existing?.models?.toMutableList() ?: mutableListOf()) }
    // 思考参数方言（compat.thinkingFormat）；空串 = 自动，交运行时按端点探测
    var thinkingFormat by remember(provider) { mutableStateOf(existing?.thinkingFormat ?: "") }

    // DataStore 异步加载可能晚于首帧：已保存的 Key 到达后补填（用户已手动输入时不覆盖）
    LaunchedEffect(provider, savedApiKeys[provider]) {
        if (apiKey.isBlank()) apiKey = savedApiKeys[provider] ?: ""
    }

    // 老版本配置的 provider Key 只存在 DSH credential，本地无回显缓存：
    // 打开详情页时若检测到有 credential 引用但本地缓存为空，主动拉一次明文落缓存
    LaunchedEffect(provider, existing?.apiKeyEnv, savedApiKeys[provider]) {
        if (apiKey.isBlank() && existing?.apiKeyEnv != null) {
            vm.ensureProviderApiKey(provider)
        }
    }

    var tab by remember(provider) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showAddModel by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelProfile?>(null) }
    var deletingModel by remember { mutableStateOf<ModelProfile?>(null) }
    var fetchedModels by remember { mutableStateOf<List<LlmModelInfo>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var testingModel by remember { mutableStateOf<String?>(null) }

    val enabled = provider !in disabled

    fun routeKey(): String = if (isNew) slug(displayName) else provider

    /** 模型页自动保存：只提交 models，不覆盖配置页尚未保存的 baseURL/API Key */
    fun autoSaveModels(next: List<ModelProfile>) {
        models = next.toMutableList()
        val key = routeKey()
        scope.launch {
            val err = vm.saveModels(key, next.map { it.toInput() })
            if (err != null) snackbar.showSnackbar("模型保存失败：" + err)
        }
    }

    /** 删除模型：若删除的是当前默认模型，先复位默认回内置 DeepSeek，再从列表移除并保存 */
    fun deleteModel(m: ModelProfile) {
        if (currentProvider == provider && currentModel == m.id) {
            vm.toggleDefault(provider, m.id)
        }
        autoSaveModels(models.filterNot { it.id == m.id })
    }

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
                models = models.map { it.toInput() },
                apiKey = apiKey,
                thinkingFormat = thinkingFormat,
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
                    thinkingFormat = thinkingFormat,
                    onThinkingFormat = { thinkingFormat = it },
                    enabled = enabled,
                    onToggle = { vm.toggleDisabled(provider, !it) },
                    onSave = { doSave() },
                    onDelete = { showDeleteConfirm = true },
                    busy = busy,
                )
                else -> ModelsTab(
                    provider = provider,
                    models = models,
                    onAddModel = { showAddModel = true },
                    onEditModel = { editingModel = it },
                    onDeleteModel = { deletingModel = it },
                    onFetch = {
                        scope.launch {
                            busy = true
                            val r = vm.discoverModels(routeKey(), baseURL, apiKey)
                            busy = false
                            r.fold(
                                onSuccess = { fetchedModels = it },
                                onFailure = { e -> snackbar.showSnackbar("获取失败：" + e.message) },
                            )
                        }
                    },
                    onToggleDefault = { m -> vm.toggleDefault(provider, m.id) },
                    onReorder = { autoSaveModels(it) },
                    currentModel = currentModel,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteProviderDialog(
            displayName = displayName,
            onDelete = {
                showDeleteConfirm = false
                scope.launch {
                    val err = vm.removeProvider(provider)
                    if (err == null) { vm.refresh(); onBack() } else snackbar.showSnackbar(err)
                }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showAddModel) {
        AddModelDialog(
            onAdd = { id -> autoSaveModels(models + ModelProfile(id = id)) },
            onDismiss = { showAddModel = false },
        )
    }

    editingModel?.let { m ->
        EditModelDialog(
            model = m,
            onSave = { updated ->
                autoSaveModels(models.map { if (it.id == m.id) updated else it })
            },
            onTest = {
                scope.launch {
                    testingModel = m.id
                    val r = vm.testModel(provider, m.id)
                    testingModel = null
                    val msg = r.fold(
                        onSuccess = { "✅ " + m.id + " 连接成功" },
                        onFailure = { e -> "❌ " + m.id + " 失败：" + e.message },
                    )
                    snackbar.showSnackbar(msg)
                }
            },
            testing = testingModel == m.id,
            onDismiss = { editingModel = null },
        )
    }

    deletingModel?.let { m ->
        DeleteModelDialog(
            model = m,
            onDelete = {
                deletingModel = null
                deleteModel(m)
            },
            onDismiss = { deletingModel = null },
        )
    }

    fetchedModels?.let { list ->
        FetchedModelsDialog(
            models = list,
            added = models.map { it.id }.toSet(),
            onAdd = { m ->
                if (models.none { it.id == m.id }) {
                    autoSaveModels(models + ModelProfile(id = m.id, name = m.name, input = m.inputModalities))
                }
            },
            onDismiss = { fetchedModels = null },
        )
    }
}

/** ModelProfile → RPC 提交条目（含思考档位声明） */
private fun ModelProfile.toInput(): LlmModelInput = LlmModelInput(
    id = id,
    name = name,
    contextWindow = contextWindow,
    maxTokens = maxTokens,
    input = input,
    reasoningEfforts = reasoningEfforts,
)
