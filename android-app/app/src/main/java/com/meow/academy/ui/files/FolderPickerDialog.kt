package com.meow.academy.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.ui.theme.LocalThemeExtras
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用目录选择对话框（plan-standard-mode §二.7：从 FileListComponents.TargetDirPicker 抽取的可复用底子）。
 *
 * 与抽取前同款的交互：
 * - 面包屑展示完整绝对路径（根内段可点跳级，根外系统前缀灰显仅展示）；
 * - 行尾「输入路径」按钮：键入完整路径回车跳转（校验存在 + 根内约束）；
 * - 行尾「新建文件夹」按钮：建完自动进入；
 * - 列表只列当前目录的直接子文件夹（隐藏目录跳过），点击逐级进入；
 * - 确认按钮把「当前所处目录」作为选中结果返回（绝对路径）；
 * - 对话框内系统返回键先逐级回上级，退到根后再按才关闭对话框；
 * - [lockedDirs] 源目录自身与子树灰显禁点（防把文件夹移动/复制进自身）。
 *
 * **根约束由 [roots] 注入**（单根 = 传一个元素的列表，如工作设置页约束 filesDir；
 * 文件管理页经 [TargetDirPicker] 包装传 filesDir + App 外部目录双根，语义不变）：
 * 浏览、面包屑跳级、路径输入、返回键逐级回退全部被约束在这些根内。
 *
 * @param title 对话框标题
 * @param confirmLabel 确认按钮文案（如「移动到此处」/「设为默认」）
 * @param initialDir 起始目录（绝对路径）
 * @param roots 可浏览根集合（绝对路径列表，至少一项）
 * @param lockedDirs 不可作为目标的目录集合（自身与子树灰显禁点，喵~）
 * @param onPick 选中回调（返回当前目录绝对路径；调用方负责关闭对话框）
 * @param onDismiss 取消回调
 */
@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    initialDir: String,
    roots: List<String>,
    lockedDirs: List<String> = emptyList(),
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentDir by remember { mutableStateOf(File(initialDir)) }
    var editing by remember { mutableStateOf(false) }
    // null = 加载中；只列直接子文件夹（隐藏目录跳过，与列表页过滤规则一致，喵~）
    var children by remember(currentDir) { mutableStateOf<List<File>?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val breadcrumbScroll = rememberScrollState()
    val extras = LocalThemeExtras.current

    fun isLocked(dir: File?): Boolean {
        if (dir == null) return false
        val dp = dir.absolutePath.trimEnd('/')
        return lockedDirs.any { locked ->
            val lp = File(locked).absolutePath.trimEnd('/')
            dp == lp || dp.startsWith("$lp/")
        }
    }

    // 进入目录后异步加载直接子文件夹
    LaunchedEffect(currentDir) {
        val dir = currentDir
        val result = withContext(Dispatchers.IO) {
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith('.') }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
        if (currentDir == dir) children = result
    }

    // 面包屑自动滚到最右，保证当前目录段可见（与主界面路径面包屑同款处理，喵~）
    LaunchedEffect(currentDir) {
        withFrameNanos { }
        breadcrumbScroll.scrollTo(breadcrumbScroll.maxValue)
    }

    // 系统返回键：对话框内先逐级回上级（限定注入的根集合内），退到根后不拦（走 AlertDialog 默认关闭）
    BackHandler(enabled = currentDir != null && !editing) {
        val parent = currentDir?.parentFile
        if (parent != null && isWithinAnyRoot(parent.absolutePath, roots)) {
            currentDir = parent
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (editing) {
                    // ── 路径输入态：直接键入目标路径（限注入的根集合内，喵~） ──
                    PickerPathEditField(
                        roots = roots,
                        currentPath = currentDir.absolutePath,
                        lockedDirs = lockedDirs,
                        onNavigate = {
                            editing = false
                            currentDir = it
                        },
                        onCancel = { editing = false },
                    )
                } else {
                    // ── 面包屑（完整绝对路径，与主界面同款语义）+ 路径编辑 + 新建文件夹 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val cur = currentDir
                        val segments = remember(cur, roots) {
                            buildBreadcrumbSegments(cur.absolutePath) { p -> isWithinAnyRoot(p, roots) }
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .horizontalScroll(breadcrumbScroll)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            segments.forEachIndexed { index, segment ->
                                if (index != 0) {
                                    Text(
                                        " › ",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                BreadcrumbCrumb(
                                    label = segment.label,
                                    isCurrent = index == segments.lastIndex,
                                    enabled = segment.navigable,
                                    tint = if (segment.navigable) {
                                        extras.quickBarColor ?: MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) { currentDir = File(segment.path) }
                            }
                        }
                        // 路径编辑：输入完整路径回车跳转（主界面面包屑同款入口，喵~）
                        IconButton(onClick = { editing = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "输入路径",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 新建文件夹：建完自动进入（Windows 选择对话框同款能力，喵~）
                        IconButton(onClick = { showNewFolder = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = "新建文件夹",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // ── 当前目录的直接子文件夹 ──
                val list = children
                when {
                    list == null -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    list.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "空文件夹（可直接选此处）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(list, key = { it.absolutePath }) { dir ->
                            val locked = isLocked(dir)
                            val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (locked) Modifier else Modifier.clickable { currentDir = dir }),
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = if (locked) disabledColor else MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        dir.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (locked) disabledColor else Color.Unspecified,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(currentDir.absolutePath) },
                enabled = !isLocked(currentDir),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showNewFolder) {
        NewNameDialog(
            title = "新建文件夹",
            confirmLabel = "创建",
            initialValue = "",
            onConfirm = { name ->
                showNewFolder = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        val dir = File(currentDir, name)
                        dir.isDirectory || dir.mkdir()
                    }
                    if (created) currentDir = File(currentDir, name)
                }
            },
            onDismiss = { showNewFolder = false },
        )
    }
}

/**
 * 路径是否落在 [roots] 任一根内（canonicalPath 前缀判断，防路径逃逸）。
 * 与 FileRepository.isWithinRoot 同语义，但根集合由调用方注入（喵~）。
 */
internal fun isWithinAnyRoot(path: String, roots: List<String>): Boolean {
    val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
    return roots.any { root ->
        val rootFile = File(root)
        val rootCanonical = runCatching { rootFile.canonicalPath }.getOrDefault(rootFile.absolutePath)
        canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
    }
}

/**
 * 导航用路径归一化（限 [roots] 内）：校验目录存在，canonical 形式映射回与根一致的
 * absolutePath 形式（/data/user/0 ↔ /data/data symlink 差异防御，与 FileRepository 同款）。
 * 目录不存在 / 不是文件夹 / 不在任何根内 → null（喵~）。
 */
private fun normalizeInRoots(path: String, roots: List<String>): String? {
    val file = File(path)
    if (!file.isDirectory) return null
    val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    for (rootPath in roots) {
        val rootFile = File(rootPath)
        val rootAbsolute = rootFile.absolutePath
        val rootCanonical = runCatching { rootFile.canonicalPath }.getOrDefault(rootAbsolute)
        if (canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)) {
            val relative = canonical.removePrefix(rootCanonical).trim('/')
            return if (relative.isEmpty()) rootAbsolute else "$rootAbsolute/$relative"
        }
    }
    return null
}

/**
 * 面包屑单段：[isCurrent] 当前目录高亮加粗不可点；[enabled] 为 false 灰显禁点（根外的前缀段，喵~）。
 * [tint] 非当前段的颜色（可点段主色/快捷色，禁点段弱化灰）。
 */
@Composable
private fun BreadcrumbCrumb(
    label: String,
    isCurrent: Boolean,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else tint,
        fontWeight = if (isCurrent) FontWeight.Bold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = !isCurrent && enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * 选择器内嵌路径输入行：起始为当前路径（光标在末尾，自动聚焦拉起键盘），
 * 回车校验后跳转——必须是存在的目录、在 [roots] 根集合内、
 * 且不在 [lockedDirs] 源目录子树中；失败红字提示并留在输入态（喵~）。
 */
@Composable
private fun PickerPathEditField(
    roots: List<String>,
    currentPath: String,
    lockedDirs: List<String>,
    onNavigate: (File) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(currentPath, TextRange(currentPath.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    fun submit() {
        val raw = text.text.trim()
        if (raw.isEmpty()) return
        scope.launch {
            val normalized = withContext(Dispatchers.IO) { normalizeInRoots(raw, roots) }
            val locked = normalized?.let { n ->
                val np = n.trimEnd('/')
                lockedDirs.any { locked ->
                    val lp = File(locked).absolutePath.trimEnd('/')
                    np == lp || np.startsWith("$lp/")
                }
            } == true
            when {
                normalized == null -> error = "路径不存在或不是文件夹"
                locked -> error = "不能选本次操作的源目录内部"
                else -> onNavigate(File(normalized))
            }
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            error = null
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        label = { Text("输入路径") },
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        trailingIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "取消输入")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
    )
}
