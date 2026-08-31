package com.meow.academy.ui.files

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.meow.academy.ui.theme.LocalThemeExtras
import java.io.File
import kotlin.math.roundToInt

/** 面包屑分段：label 展示名 + 点击跳转的累计路径 + 是否可点击跳转（可用根外的前缀段仅作展示） */
internal data class BreadcrumbSegment(val label: String, val path: String, val navigable: Boolean)

/**
 * 把当前绝对路径按 '/' 逐层拆成面包屑分段（/data/user/0/…/files/workspace/uploads 全层级可见），
 * 位于可用根（filesDir / 外部目录）内的段可点击跳转，根外系统前缀段灰色仅展示。
 * 直接拆绝对路径、不依赖任何根基准的前缀匹配，天然无「假段」（喵~）。
 */
internal fun buildBreadcrumbSegments(path: String, isNavigable: (String) -> Boolean): List<BreadcrumbSegment> {
    val normalized = File(path).absolutePath
    val segments = mutableListOf<BreadcrumbSegment>()
    var current = ""
    normalized.split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = "$current/$part"
        segments += BreadcrumbSegment(part, current, isNavigable(current))
    }
    Log.d("FilesNav", "breadcrumb: path=[$normalized] segments=${segments.joinToString("|") { it.label }}")
    return segments
}

/**
 * 绝对路径面包屑：完整路径逐层展示（/data/user/0/…/files/workspace/uploads），
 * 可用根内的段可点击跳转，根外系统前缀段灰色仅展示；点击右侧编辑按钮可输入完整路径回车跳转。
 * 放在文件管理上方，替代原来的纯文本路径展示（喵~）。
 */
@Composable
fun EditableBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    isNavigable: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var editing by remember(path) { mutableStateOf(false) }
    // 用 TextFieldValue 而非 String：编辑框需要 selection（光标位置）做自动滚动（喵~）
    var draft by remember(path) { mutableStateOf(TextFieldValue(path)) }
    val scrollState = rememberScrollState()

    // 路径变化时自动滚到最右端，保证当前目录段可见
    LaunchedEffect(path) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    // 编辑态下系统返回键优先退出编辑，而不是返回上级目录
    BackHandler(enabled = editing) { editing = false }

    val extras = LocalThemeExtras.current

    if (editing) {
        PathEditField(
            value = draft,
            onValueChange = { draft = it },
            onGo = {
                val target = draft.text.trim()
                onNavigate(target)
                editing = false
            },
            onCancel = { editing = false },
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        val segments = remember(path, isNavigable) { buildBreadcrumbSegments(path, isNavigable) }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                segments.forEachIndexed { index, segment ->
                    Text(
                        text = segment.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            index == segments.lastIndex -> MaterialTheme.colorScheme.onSurface
                            segment.navigable -> extras.quickBarColor ?: MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant // 根外前缀：弱化仅展示
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (segment.navigable) {
                                    Modifier.clickable { onNavigate(segment.path) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    if (index != segments.lastIndex) {
                        Text(
                            text = "/",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 1.dp),
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    editing = true
                    // 光标直接置于路径末尾（喵~）：TextFieldValue 默认 selection 在开头，
                    // 显式给 TextRange(path.length)，配合 PathEditField 的光标跟随滚动直达最右端
                    draft = TextFieldValue(path, TextRange(path.length))
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑路径",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 路径编辑输入框（修复「拖动光标手柄文字不自滚动」BUG 喵~）：
 * 旧实现 OutlinedTextField(String)：内部自滚动对「拖动选择手柄」不生效，
 * 且外部拿不到 selection 无法自行补偿。
 * 新实现：BasicTextField（能拿到 onTextLayout 光标排版信息与 selection）
 * + 官方 OutlinedTextFieldDefaults.DecorationBox/ContainerBox 复刻原版描边框外观；
 * 文本区自身不限宽、由外层 horizontalScroll 统一滚动，监听光标变化把光标滚进视口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PathEditField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onGo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val interactionSource = remember { MutableInteractionSource() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 文本视口宽度（描边框内、图标之外的可视区域）
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 光标贴近视口边缘时的留白
    val edgeMarginPx = with(density) { 16.dp.toPx() }
    // 进入编辑态自动聚焦并拉起键盘：配合外部传入的「光标在末尾」selection 直接可输入（喵~）
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // 光标/选区（含拖动选择手柄 / 键盘移动 / 输入）变化时，把选区两端滚进可视区
    LaunchedEffect(value.selection, textLayout, viewportWidthPx) {
        val layout = textLayout ?: return@LaunchedEffect
        if (viewportWidthPx <= 0 || value.text.isEmpty()) return@LaunchedEffect
        // 选区两端都要可见（拖动任一手柄都能跟随）；折叠选区时两端重合即光标位置
        val textLength = value.text.length
        val startX = layout.getHorizontalPosition(value.selection.min.coerceIn(0, textLength), true).coerceAtLeast(0f)
        val endX = layout.getHorizontalPosition(value.selection.max.coerceIn(0, textLength), true).coerceAtLeast(0f)
        val viewStart = scrollState.value.toFloat()
        val viewEnd = viewStart + viewportWidthPx
        val target = when {
            startX < viewStart -> startX - edgeMarginPx // 左端出视口左侧：向左滚
            endX > viewEnd - edgeMarginPx -> endX - viewportWidthPx + edgeMarginPx // 右端出视口右侧：向右滚
            else -> return@LaunchedEffect // 已可见：不打扰
        }
        scrollState.animateScrollTo(target.roundToInt().coerceIn(0, scrollState.maxValue))
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        onTextLayout = { textLayout = it },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .focusRequester(focusRequester),
    ) { innerTextField ->
        // 官方 DecorationBox + ContainerBox：与 OutlinedTextField 同款描边框
        OutlinedTextFieldDefaults.DecorationBox(
            value = value.text,
            innerTextField = {
                Box(
                    Modifier
                        .onSizeChanged { viewportWidthPx = it.width }
                        .horizontalScroll(scrollState),
                ) { innerTextField() }
            },
            label = { Text("输入路径") },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = "取消编辑")
                }
            },
            enabled = true,
            isError = false,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            colors = OutlinedTextFieldDefaults.colors(),
            contentPadding = OutlinedTextFieldDefaults.contentPadding(),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = interactionSource,
                )
            },
        )
    }
}