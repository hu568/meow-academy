package com.meow.academy.rpc

/** 连接状态 */
sealed interface DshConnectionState {
    data object Connecting : DshConnectionState
    data object Running : DshConnectionState
    data class Closed(val error: String? = null) : DshConnectionState
}
