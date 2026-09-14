package dev.shikongbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 两页共用的小零件（卡片、灯、按钮、档位小按钮、滑块）。
 * 风格照抄 server/relay.html：深色紫粉渐变 + 半透明粉边卡片 + 一排小按钮。
 */

/** 页面大标题，粉紫渐变（relay.html 的 h2）。 */
@Composable
internal fun GradientTitle(text: String) {
    Text(
        text,
        fontSize = Ui.pageTitleSp,
        fontWeight = FontWeight.Bold,
        style = TextStyle(brush = Brush.linearGradient(Ui.titleGradient)),
    )
}

/** 顶部两个页签：连接 / 控制。 */
@Composable
internal fun TabSwitch(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val outer = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(Ui.chipBg)
            .border(1.dp, Ui.chipBorder, outer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(shape)
                    .background(if (on) Ui.chipOnBg else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = Ui.bodySp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Ui.chipOnText else Ui.textDim,
                )
            }
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    bg: Color,
    border: Color,
    titleColor: Color,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Ui.cardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .padding(Ui.cardPad)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // trailing 不带 weight，先量、拿够自己要的宽度；标题带 weight(fill=false)，
            // 挤不下时标题自己省略，右边那行状态字保证完整。
            Text(
                title,
                fontSize = Ui.titleSp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun Body(text: String, color: Color = Ui.text) {
    Text(text, fontSize = Ui.bodySp, color = color)
}

@Composable
internal fun Note(text: String) {
    Text(text, fontSize = Ui.noteSp, color = Ui.textDim, lineHeight = Ui.noteSp * 1.5f)
}

@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(Ui.buttonHeight)
    ) { Text(label, fontSize = Ui.bodySp, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun GhostButton(
    label: String,
    onClick: () -> Unit,
    color: Color = Ui.textDim,
    border: Color = Ui.divider,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = Modifier
            .fillMaxWidth()
            .height(Ui.buttonHeight)
    ) { Text(label, fontSize = Ui.bodySp, fontWeight = FontWeight.Bold) }
}

/** 档位小按钮 / 模式小按钮。on = 正在生效，粉色高亮。 */
@Composable
internal fun Chip(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Ui.chipRadius)
    Box(
        modifier = modifier
            .height(Ui.chipHeight)
            .clip(shape)
            .background(if (on) Ui.chipOnBg else Ui.chipBg)
            .border(1.dp, if (on) Ui.chipOnBorder else Ui.chipBorder, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = Ui.noteSp,
            fontWeight = FontWeight.Medium,
            color = if (on) Ui.chipOnText else Ui.chipText,
            maxLines = 1,
        )
    }
}

/** 一盏灯 + 一句人话。 */
@Composable
internal fun StatusRow(
    label: String,
    row: LinkStatus.Row,
    onClick: (() -> Unit)? = null,
) {
    val color = when (row.lamp) {
        LinkStatus.Lamp.GREEN -> Ui.lampGreen
        LinkStatus.Lamp.RED -> Ui.lampRed
        LinkStatus.Lamp.GREY -> Ui.lampGrey
    }
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = if (onClick != null) base.clickable { onClick() } else base,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Lamp(color)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = Ui.bodySp,
            fontWeight = FontWeight.Bold,
            color = Ui.textDim,
            modifier = Modifier.width(84.dp)
        )
        Text(row.text, fontSize = Ui.bodySp, color = Ui.text)
    }
}

@Composable
internal fun Lamp(color: Color) {
    Box(
        modifier = Modifier
            .size(Ui.lampSize)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 0-100 的档位滑块。当前值显示在卡片标题那一行（见 [SectionCard] 的 trailing），这里不重复。
 *
 * [dimmed] = 这一端正被模式接管：滑块画成灰的（看起来像禁用），但**照样能碰**——
 * 一碰就等于用户接管，调用方负责把模式清掉、恢复正常颜色。
 * 不能用 `enabled = false`，那样连触摸都收不到。
 */
@Composable
internal fun LevelSlider(
    value: Float,
    dimmed: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onDrag,
        onValueChangeFinished = onRelease,
        valueRange = 0f..100f,
        steps = 0,
        colors = if (dimmed) {
            SliderDefaults.colors(
                thumbColor = Ui.lampGrey,
                activeTrackColor = Ui.lampGrey,
                inactiveTrackColor = Ui.divider,
            )
        } else {
            SliderDefaults.colors()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
