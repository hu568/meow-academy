package com.meow.academy.ui.settings

/**
 * 模型管理页头像组件：
 * - 已知厂商使用品牌色圆底 + 官方 logo（本地 vector drawable / Material 图标）
 * - 未知厂商/模型使用首字母 + 主题色容器兜底
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.academy.R
import com.meow.academy.data.model.DEEPSEEK_PROVIDER
import com.meow.academy.data.model.slug

/** 已知厂商头像配色和图标 */
private data class ProviderAvatarStyle(
    val backgroundColor: Color,
    val iconRes: Int? = null,
    val iconVector: ImageVector? = null,
)

private val DEEPSEEK_BLUE = Color(0xFF4D6BFE)
private val OPENAI_BLACK = Color(0xFF111111)
private val MOONSHOT_BLACK = Color(0xFF1A1A1A)
private val GROQ_ORANGE = Color(0xFFF55036)
private val SILICONFLOW_BLUE = Color(0xFF00A6A6)
private val QWEN_PURPLE = Color(0xFF615CED)

/** 已配置/预设 provider 的 key → 头像样式 */
private fun providerAvatarStyle(provider: String): ProviderAvatarStyle? {
    val key = if (provider == DEEPSEEK_PROVIDER || provider == "deepseek") {
        DEEPSEEK_PROVIDER
    } else {
        slug(provider)
    }
    return when (key) {
        DEEPSEEK_PROVIDER -> ProviderAvatarStyle(DEEPSEEK_BLUE, iconRes = R.drawable.ic_provider_deepseek)
        "openai" -> ProviderAvatarStyle(OPENAI_BLACK, iconRes = R.drawable.ic_provider_openai)
        "moonshot_kimi" -> ProviderAvatarStyle(MOONSHOT_BLACK, iconRes = R.drawable.ic_provider_moonshot)
        "groq" -> ProviderAvatarStyle(GROQ_ORANGE, iconVector = Icons.Filled.Bolt)
        "siliconflow" -> ProviderAvatarStyle(SILICONFLOW_BLUE, iconVector = Icons.Filled.Memory)
        "qwen" -> ProviderAvatarStyle(QWEN_PURPLE, iconRes = R.drawable.ic_provider_qwen)
        else -> null
    }
}

/** 厂商头像：官方 logo 优先，未知厂商回退首字母 */
@Composable
fun ProviderAvatar(
    provider: String,
    displayName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val style = remember(provider) { providerAvatarStyle(provider) }
    val fallbackContainer = MaterialTheme.colorScheme.primaryContainer
    val fallbackContent = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(style?.backgroundColor ?: fallbackContainer),
        contentAlignment = Alignment.Center,
    ) {
        val iconRes = style?.iconRes
        val iconVector = style?.iconVector
        when {
            iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = displayName,
                modifier = Modifier.size(size * 0.62f),
                tint = Color.White,
            )
            iconVector != null -> Icon(
                imageVector = iconVector,
                contentDescription = displayName,
                modifier = Modifier.size(size * 0.62f),
                tint = Color.White,
            )
            else -> Text(
                text = displayName.take(1).uppercase(),
                color = fallbackContent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 模型头像：沿用所属厂商的品牌头像；未知厂商回退首字母（用次要色容器区分） */
@Composable
fun ModelAvatar(
    provider: String,
    modelName: String,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val style = remember(provider) { providerAvatarStyle(provider) }
    val fallbackContainer = MaterialTheme.colorScheme.secondaryContainer
    val fallbackContent = MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(style?.backgroundColor ?: fallbackContainer),
        contentAlignment = Alignment.Center,
    ) {
        val iconRes = style?.iconRes
        val iconVector = style?.iconVector
        when {
            iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = modelName,
                modifier = Modifier.size(size * 0.58f),
                tint = Color.White,
            )
            iconVector != null -> Icon(
                imageVector = iconVector,
                contentDescription = modelName,
                modifier = Modifier.size(size * 0.58f),
                tint = Color.White,
            )
            else -> Text(
                text = modelName.take(1).uppercase(),
                color = fallbackContent,
                fontSize = if (size >= 36.dp) 14.sp else 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
