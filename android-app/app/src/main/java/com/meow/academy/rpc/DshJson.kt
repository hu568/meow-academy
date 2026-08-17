package com.meow.academy.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 便捷扩展：读 JsonObject 里的字符串字段（非 primitive 时序列化为字符串，绝不抛异常） */
fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null -> null
    is JsonPrimitive -> v.contentOrNull
    else -> v.toString()
}

/** 便捷扩展：读 JsonObject 里的布尔字段（非 primitive 返回 null，绝不抛异常） */
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBoolean()

/** 便捷扩展：读 JsonObject 里的整数字段（非 primitive 返回 null，绝不抛异常） */
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
