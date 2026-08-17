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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.ModelProfile
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
                                onFailure = { e -> snackbar.showSnackbar("获取失败：" + e.message) },
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
                                onSuccess = { "✅ " + m.id + " 连接成功" },
                                onFailure = { e -> "❌ " + m.id + " 失败：" + e.message },
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
            onAdd = { id -> models = (models + ModelProfile(id = id)).toMutableList() },
            onDismiss = { showAddModel = false },
        )
    }

    editingModel?.let { m ->
        EditModelDialog(
            model = m,
            onSave = { updated ->
                models = models.map { if (it.id == m.id) updated else it }.toMutableList()
            },
            onDismiss = { editingModel = null },
        )
    }

    fetchedModels?.let { list ->
        FetchedModelsDialog(
            models = list,
            added = models.map { it.id }.toSet(),
            onAdd = { m ->
                if (models.none { it.id == m.id }) {
                    models = (models + ModelProfile(id = m.id, name = m.name)).toMutableList()
                }
            },
            onDismiss = { fetchedModels = null },
        )
    }
}
