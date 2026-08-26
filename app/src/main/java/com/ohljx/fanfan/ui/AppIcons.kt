package com.ohljx.fanfan.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 手绘产品图标：圆角、略厚的形体，与整体“暗房 + 相纸”的气质对齐。 */
object AppIcons {

    /** 留言：圆角气泡 + 小尾巴。 */
    val Comment: ImageVector by lazy {
        glyph(
            name = "Comment",
            "M6.2,4.5h11.6a3.7,3.7 0 0 1 3.7,3.7v4.6a3.7,3.7 0 0 1 -3.7,3.7h-5.1l-4,3.9a0.72,0.72 0 0 1 -1.23,-0.51v-3.39H6.2a3.7,3.7 0 0 1 -3.7,-3.7V8.2A3.7,3.7 0 0 1 6.2,4.5z" to 1f,
        )
    }

    /** 翻翻：一叠照片，后一张向左上错开。 */
    val FlipStack: ImageVector by lazy {
        glyph(
            name = "FlipStack",
            "M6.5,4h6a2.5,2.5 0 0 1 2.5,2.5v8a2.5,2.5 0 0 1 -2.5,2.5h-6a2.5,2.5 0 0 1 -2.5,-2.5v-8A2.5,2.5 0 0 1 6.5,4z" to 0.45f,
            "M10.5,7h6a2.5,2.5 0 0 1 2.5,2.5v8a2.5,2.5 0 0 1 -2.5,2.5h-6a2.5,2.5 0 0 1 -2.5,-2.5v-8A2.5,2.5 0 0 1 10.5,7z" to 1f,
        )
    }

    /** 理理：2×2 圆角方格。 */
    val Grid: ImageVector by lazy {
        glyph(
            name = "Grid",
            "M5.5,3.5h4a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2h-4a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z" +
                "M14.5,3.5h4a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2h-4a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z" +
                "M5.5,12.5h4a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2h-4a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z" +
                "M14.5,12.5h4a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2h-4a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z" to 1f,
        )
    }

    private fun glyph(name: String, vararg paths: Pair<String, Float>): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { (data, alpha) ->
                addPath(
                    pathData = addPathNodes(data),
                    fill = SolidColor(Color.White),
                    fillAlpha = alpha,
                )
            }
        }.build()
}
