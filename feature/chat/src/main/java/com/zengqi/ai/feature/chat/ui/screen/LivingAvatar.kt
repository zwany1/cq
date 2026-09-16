package com.zengqi.ai.feature.chat.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 动态头像：TA 说话时呼吸缩放 + 轻微摇曳，模拟正在开口的活人感。
 * 静默时保持静止。
 */
@Composable
fun LivingAvatar(
    avatarUrl: String?,
    fallbackText: String,
    isSpeaking: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "livingAvatar")

    // 呼吸缩放：说话时 0.96~1.06，静默时 1.0
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeaking) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 420 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    // 轻微摇曳：说话时左右 2 度摆动，静默时 0
    val sway by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (isSpeaking) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 600 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sway"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = breath
                scaleY = breath
                rotationZ = if (isSpeaking) (sway - 0.5f) * 3f else 0f
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = fallbackText,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = fallbackText,
                fontSize = 48.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
