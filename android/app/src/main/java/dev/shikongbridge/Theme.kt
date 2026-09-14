package dev.shikongbridge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 配色和尺寸集中在这里，别在界面里散着写死。
 * 风格照抄 server/relay.html（手动控制网页）的深色紫粉渐变 + 卡片 + 小按钮。
 */
object Ui {
    // 页面
    val bgTop = Color(0xFF2D1B4E)
    val bgMid = Color(0xFF1A1035)
    val bgBottom = Color(0xFF23103F)

    val text = Color(0xFFFCE7F3)
    val textDim = Color(0xB3F0E6FF)
    val textFaint = Color(0x80F0E6FF)

    val accent = Color(0xFFF472B6)

    // 灯
    val lampGreen = Color(0xFF34D399)
    val lampRed = Color(0xFFFF6B6B)
    val lampGrey = Color(0xFF6E6A86)

    // 四个卡片：链路状态=蓝、控制=粉、危险操作=红描边、设置=灰
    val linkBg = Color(0x1A6BA8FF)
    val linkBorder = Color(0x595B8DEF)
    val linkTitle = Color(0xFF93C5FD)

    val ctrlBg = Color(0x14FFC8E6)
    val ctrlBorder = Color(0x4DFF96C8)
    val ctrlTitle = Color(0xFFF9A8D4)

    val dangerBg = Color(0x0DEF4444)
    val dangerBorder = Color(0x80EF4444)
    val dangerTitle = Color(0xFFFCA5A5)

    val setBg = Color(0x0DFFFFFF)
    val setBorder = Color(0x26FFFFFF)
    val setTitle = Color(0xFFC7C3DC)

    val divider = Color(0x1FFFFFFF)

    // 档位小按钮 / 模式小按钮（relay.html 里的 .g button 和 .pr button）
    val chipBg = Color(0x0FFFFFFF)
    val chipBorder = Color(0x14FFFFFF)
    val chipText = Color(0xB3F0E6FF)
    val chipOnBg = Color(0x33F0ABFC)
    val chipOnBorder = Color(0x80F0ABFC)
    val chipOnText = Color(0xFFF0ABFC)

    /** 大标题的粉紫渐变（relay.html 的 h2）。 */
    val titleGradient = listOf(
        Color(0xFFF9A8D4),
        Color(0xFFD8B4FE),
        Color(0xFFA78BFA),
    )

    // 字号只有四档：20 页标题 / 16 卡片标题 / 14 正文 / 12 小字，别再大大小小
    val titleSp = 16.sp
    val bodySp = 14.sp
    val noteSp = 12.sp
    val pageTitleSp = 20.sp

    // 尺寸
    val pagePad = 16.dp
    val cardGap = 12.dp
    val cardPad = 16.dp
    val cardRadius = 16.dp
    val lampSize = 10.dp
    val buttonHeight = 48.dp
    val chipHeight = 34.dp
    val chipRadius = 10.dp
}
