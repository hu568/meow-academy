package com.meow.academy.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠卡自动状态机的纯逻辑单测（plan-chat-streaming-render §四.B1/B2）。
 *
 * [AutoCollapseController] 只依赖 Compose 的 snapshot state（`mutableStateOf`），
 * 不碰 Android API，可在 JVM 上直接测；`rememberAutoCollapse` 里的 `delay`/`LaunchedEffect`
 * 时序由真机冒烟覆盖（§7.3），这里只钉死「谁能改变 expanded」的语义。
 */
class ChatCollapseMotionTest {

    @Test
    fun `运行中默认展开`() {
        assertTrue(AutoCollapseController(running = true, pinned = false).expanded)
    }

    @Test
    fun `非运行中默认收起`() {
        assertFalse(AutoCollapseController(running = false, pinned = false).expanded)
    }

    @Test
    fun `首次思考钉住：完成后仍展开`() {
        val pinned = AutoCollapseController(running = false, pinned = true)
        assertTrue(pinned.expanded)
        // 自动逻辑即使把 autoCollapsed 置 true（模拟误触发的延迟收起），pinned 的初始态也不是收起
        assertFalse(pinned.autoCollapsed)
    }

    @Test
    fun `用户点过之后自动逻辑不再改观感`() {
        val controller = AutoCollapseController(running = true, pinned = false)
        assertTrue(controller.expanded)

        controller.toggle() // 用户手动收起
        assertFalse(controller.expanded)
        assertTrue(controller.userToggled)

        controller.autoCollapsed = false // 自动逻辑又想展开（例如运行态再次翻转）
        assertFalse(controller.expanded) // 用户接管优先

        controller.toggle() // 用户再点开
        assertTrue(controller.expanded)
        controller.autoCollapsed = true // 自动逻辑又想收起
        assertTrue(controller.expanded)
    }

    @Test
    fun `pinned 卡用户收起后不被自动展开`() {
        val controller = AutoCollapseController(running = false, pinned = true)
        assertTrue(controller.expanded)

        controller.toggle()
        assertFalse(controller.expanded)

        controller.autoCollapsed = false // 自动逻辑不应影响（它本身也不会动 pinned 卡，这里作双保险）
        assertFalse(controller.expanded)
    }
}
