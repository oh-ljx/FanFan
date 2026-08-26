package com.ohljx.fanfan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ohljx.fanfan.R

/**
 * Live Photo 徽章，图形直接使用产品提供的 SVG path。
 * 白色本体直接叠在照片上，背后垫一圈径向渐变的柔光晕保证浅色画面上可读——
 * 不用高斯模糊（会把点阵糊成一团），点按水波纹裁成圆形。
 */
@Composable
fun LivePhotoBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val touchSize = if (compact) 24.dp else 48.dp
    val markSize = if (compact) 14.dp else 27.dp
    var containerModifier = modifier
        .size(touchSize)
        .clip(CircleShape)
        .semantics {
            contentDescription = if (onClick == null) "实况照片" else "实况照片，点按重播"
        }
    if (onClick != null) {
        containerModifier = containerModifier.clickable(
            onClickLabel = "从头播放实况照片",
            onClick = onClick,
        )
    }

    Box(modifier = containerModifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(markSize * 1.4f)
                .background(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.4f),
                        0.6f to Color.Black.copy(alpha = 0.16f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Image(
            painter = painterResource(R.drawable.ic_live_photo),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(markSize),
        )
    }
}
