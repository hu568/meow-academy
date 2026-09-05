package com.meow.academy.ui.chat

/**
 * 工作区路径短名（纯函数小工具，喵~）：
 * 工作设置面板①工作区栏（默认工作区行 / 切换 toast / 候选列表）与 SessionDrawerList
 * 元信息行（同包调用）同规则复用：
 * `<filesDir>/workspace` → 「workspace」、`<filesDir>` 本身 → 「files」、其余取最后一段文件夹名。
 * path 为 null（旧数据未写工作区）时按历史唯一工作区回退「workspace」。
 *
 * 注：曾经有过 `workspaceShortName(path, Context)` 便捷重载（声称供 ChatScreen 顶栏小字共用），
 * 该引用已随后续重构消失，重载本身全项目零调用 → 已随本文件拆出删除（死代码不搬家，喵~）。
 */
fun workspaceShortName(path: String?, filesDirPath: String): String {
    if (path == null) return "workspace"
    val normalized = path.trimEnd('/')
    val root = filesDirPath.trimEnd('/')
    return when {
        normalized == root -> "files"
        normalized == "$root/workspace" -> "workspace"
        else -> normalized.substringAfterLast('/')
    }
}
