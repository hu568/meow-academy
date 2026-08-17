package com.meow.academy.ui.settings

/**
 * 模型管理页：路由（列表 ↔ 详情）+ provider 列表页。
 * 从原 ModelManageScreen.kt（657 行）原子拆出，详情页/表单/对话框各在独立文件。
 */

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private sealed interface ModelRoute {
    data object List : ModelRoute
    data class Detail(val provider: String, val isNew: Boolean = false) : ModelRoute
}

/** 模型管理页：路由（列表 ↔ 详情）+ 返回键处理 */
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

/** 单个 provider 卡片（头像 + 名称 + 状态 + 箭头） */
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
                    enabled -> item.modelCount.toString() + " 个模型 · 已启用"
                    else -> item.modelCount.toString() + " 个模型 · 已禁用"
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
