package com.meow.academy.rpc

/**
 * 连接状态模型（DSH JSON-RPC 客户端）。
 * 从 DshRpcClient.kt 原子拆出：Connecting → Running → Closed 的生命周期，
 * 上层（聊天流式收集等）用它做连接断开兜底。
 */
sealed interface DshConnectionState {
    data object Connecting : DshConnectionState
    data object Running : DshConnectionState
    data class Closed(val error: String? = null) : DshConnectionState
}
