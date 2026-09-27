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

    /** 翻翻：一叠照片，每层之间留出缝隙，顶部边缘实心、视觉高度与理理一致。 */
    val FlipStack: ImageVector by lazy {
        glyph(
            name = "FlipStack",
            "M5.70,3.50L18.30,3.50A2.20,2.20 0.0 0,1 20.50,5.70L20.50,6.10L3.50,6.10L3.50,5.70A2.20,2.20 0.0 0,1 5.70,3.50Z" to 1f,
            "M5.70,7.10L18.30,7.10A2.20,2.20 0.0 0,1 20.50,9.30L20.50,9.70L3.50,9.70L3.50,9.30A2.20,2.20 0.0 0,1 5.70,7.10Z" to 1f,
            "M6.00,10.70L18.00,10.70A2.50,2.50 0.0 0,1 20.50,13.20L20.50,18.00A2.50,2.50 0.0 0,1 18.00,20.50L6.00,20.50A2.50,2.50 0.0 0,1 3.50,18.00L3.50,13.20A2.50,2.50 0.0 0,1 6.00,10.70Z" to 1f,
        )
    }

    /** 理理：2×2 圆角方格，圆角与照片卡同为 2.5。 */
    val Grid: ImageVector by lazy {
        glyph(
            name = "Grid",
            "M6.00,3.50L9.00,3.50A2.50,2.50 0.0 0,1 11.50,6.00L11.50,9.00A2.50,2.50 0.0 0,1 9.00,11.50L6.00,11.50A2.50,2.50 0.0 0,1 3.50,9.00L3.50,6.00A2.50,2.50 0.0 0,1 6.00,3.50Z" +
                "M15.00,3.50L18.00,3.50A2.50,2.50 0.0 0,1 20.50,6.00L20.50,9.00A2.50,2.50 0.0 0,1 18.00,11.50L15.00,11.50A2.50,2.50 0.0 0,1 12.50,9.00L12.50,6.00A2.50,2.50 0.0 0,1 15.00,3.50Z" +
                "M6.00,12.50L9.00,12.50A2.50,2.50 0.0 0,1 11.50,15.00L11.50,18.00A2.50,2.50 0.0 0,1 9.00,20.50L6.00,20.50A2.50,2.50 0.0 0,1 3.50,18.00L3.50,15.00A2.50,2.50 0.0 0,1 6.00,12.50Z" +
                "M15.00,12.50L18.00,12.50A2.50,2.50 0.0 0,1 20.50,15.00L20.50,18.00A2.50,2.50 0.0 0,1 18.00,20.50L15.00,20.50A2.50,2.50 0.0 0,1 12.50,18.00L12.50,15.00A2.50,2.50 0.0 0,1 15.00,12.50Z" to 1f,
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
