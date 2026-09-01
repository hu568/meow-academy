package com.meow.academy.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 右侧看板锚定拖拽位置：Open = 面板完全展开（translationX 0），Closed = 面板滑出屏幕右侧 */
internal enum class DrawerPos { Closed, Open }

// ─────────────────── 几何常量（真机目检可微调） ───────────────────

/** 左侧页签列宽（凸起/融合区域右缘 x） */
internal val RAIL_WIDTH = 48.dp
/** 未选中页签方块边长 */
internal val TAB_SQUARE = 44.dp
/** 未选中页签方块圆角（对齐面板内 SelectableRow 的 12dp，备选 8dp） */
internal val TAB_CORNER = 12.dp
/** 页签列顶部 padding：让第一个页签低于抽屉标题（方便点按） */
internal val RAIL_TOP_PADDING = 72.dp
/** 页签（选中/未选中统一高度，无文字） */
internal val TAB_SELECTED_HEIGHT = TAB_SQUARE
/** 页签列内竖向间距 */
internal val TAB_SPACING = 10.dp
/** 面板主体圆角 */
internal val PANEL_CORNER = 16.dp
/** 凸起外侧圆角 / 反圆弧半径 */
internal val BUMP_CORNER = 12.dp
/** 凸起与面板交界处相切圆角的半径 R（圆心偏移 = 半径，同时相切于内容区左缘与页签边） */
internal val BUMP_RADIUS = 12.dp
/** 凸起水平居中于页签列时的左右偏移 */
internal val BUMP_OFFSET = (RAIL_WIDTH - TAB_SQUARE) / 2
/** 内容区（头部/面板）起始 x：凸起右缘 + 少量呼吸间距 */
internal val CONTENT_START_PADDING = BUMP_OFFSET + TAB_SQUARE + 4.dp

/**
 * 「毛玻璃融合」面板形状（参考 `毛玻璃融合页签` 的 clip-path 思路，纯几何剪裁、零额外模糊）：
 *
 * 面板主体（矩形，右缘贴屏幕边为直角）+ 左侧选中页签处向右伸出一个「凸起」。
 * 凸起与面板主体交界处用**相切圆角**平滑过渡：交接圆弧的圆心在交角外侧偏移一个半径 R，
 * 圆同时相切于内容区左缘与页签上/下边，形成干净的 90° 圆角——选中页签透明无背景，
 * 由这个凸起供底，视觉上页签与面板融为一体。
 *
 * 路径按抽屉局部坐标绘制：x=0 为抽屉左缘（页签列），x=W 为抽屉右缘（屏幕边）。
 *
 * @param t1 / t2 凸起的顶/底 y（抽屉局部坐标，由选中页签 bounds 换算）
 * @param bumpLeft / bumpRight 凸起的左/右 x（= 选中页签水平 bounds）
 */
internal class FusedPanelShape(
    private val t1: Float,
    private val t2: Float,
    private val bumpLeft: Float,
    private val bumpRight: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val W = size.width
        val H = size.height
        val r = with(density) { PANEL_CORNER.toPx() }   // 面板圆角
        val rc = with(density) { BUMP_CORNER.toPx() }   // 凸起外侧圆角
        val R = with(density) { BUMP_RADIUS.toPx() }    // 反圆弧半径
        val L = bumpLeft
        val B = bumpRight
        // 防退化：凸起高度至少盖住两个反圆弧，避免动画中间帧出现自交路径
        val y1 = t1.coerceAtLeast(r + R)
        val y2 = t2.coerceAtLeast(y1 + 2 * R + 4)

        val path = Path().apply {
            moveTo(W, 0f)
            lineTo(W, H)                                                  // 右缘（贴屏幕边，直角）
            lineTo(B + r, H)
            arcTo(Rect(B, H - 2 * r, B + 2 * r, H), 90f, 90f, false)      // 面板左下圆角
            lineTo(B, y2 + R)
            // ★交接圆角（下）：圆心在页签与内容区交角外侧 (B-R, y2+R)，偏移一个半径 R，
            //   圆同时相切于内容区左缘（切点 (B, y2+R)）与页签下边（切点 (B-R, y2)），90° 平滑过渡
            arcTo(Rect(B - 2 * R, y2, B, y2 + 2 * R), 0f, -90f, false)
            lineTo(L + rc, y2)
            arcTo(Rect(L, y2 - 2 * rc, L + 2 * rc, y2), 90f, 90f, false)  // 凸起左下圆角
            lineTo(L, y1 + rc)
            arcTo(Rect(L, y1, L + 2 * rc, y1 + 2 * rc), 180f, 90f, false) // 凸起左上圆角
            lineTo(B - R, y1)
            // ★交接圆角（上）：圆心在 (B-R, y1-R)，相切于页签上边（切点 (B-R, y1)）
            //   与内容区左缘（切点 (B, y1-R)），90° 平滑过渡
            arcTo(Rect(B - 2 * R, y1 - 2 * R, B, y1), 90f, -90f, false)
            lineTo(B, r)
            arcTo(Rect(B, 0f, B + 2 * r, 2 * r), 180f, 90f, false)        // 面板左上圆角
            close()
        }
        return Outline.Generic(path)
    }
}

/** 凸起默认位置计算（选中页签按序号估算，用于首帧/测量前，避免首帧凸起从顶部滑落） */
internal fun defaultBumpBounds(selectedIndex: Int, density: Density): Pair<Float, Float> {
    val t1 = with(density) { (RAIL_TOP_PADDING + (TAB_SQUARE + TAB_SPACING) * selectedIndex).toPx() }
    val t2 = t1 + with(density) { TAB_SELECTED_HEIGHT.toPx() }
    return t1 to t2
}

/** 凸起局部坐标计算（从 window 坐标映射到裁剪表面局部坐标：以 surfaceBounds 左上角为原点） */
internal fun localBumpBounds(selectedBounds: Rect, surfaceBounds: Rect): Pair<Float, Float> {
    val t1 = selectedBounds.top - surfaceBounds.top
    val t2 = selectedBounds.bottom - surfaceBounds.top
    return t1 to t2
}

/** 凸起水平 bounds（页签列 48dp 内居中 44dp 方块） */
internal fun bumpHorizontalBounds(density: Density): Pair<Float, Float> {
    val left = with(density) { BUMP_OFFSET.toPx() }
    val right = with(density) { (BUMP_OFFSET + TAB_SQUARE).toPx() }
    return left to right
}
