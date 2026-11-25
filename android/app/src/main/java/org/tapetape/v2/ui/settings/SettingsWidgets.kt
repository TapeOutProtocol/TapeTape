package org.tapetape.v2.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.R
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.theme.Accent
import org.tapetape.v2.ui.theme.LocalTapeTapeColors

@Composable
fun PrefRow(title: String, summary: String? = null, enabled: Boolean = true, inset: Dp = 16.dp, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = inset, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = c.text)
            if (summary != null) Text(summary, fontSize = 14.sp, color = c.preview, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(28.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true, inset: Dp = 16.dp, onChange: (Boolean) -> Unit) {
    PrefRow(title, summary, enabled, inset, onClick = { onChange(!checked) }) {
        TapeTapeSwitch(checked, enabled) { onChange(it) }
    }
}

@Composable
fun TapeTapeSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val offset by animateDpAsState(if (checked) 16.dp else 0.dp, label = "switch")
    Box(
        Modifier
            .size(40.dp, 24.dp)
            .clickable(enabled = enabled, interactionSource = null, indication = null) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(34.dp, 14.dp)
                .background(if (checked) Accent.copy(alpha = 0.5f) else Color(0xFFB9B9B9), RoundedCornerShape(7.dp)),
        )
        Box(
            Modifier
                .offset(x = offset)
                .padding(2.dp)
                .shadow(2.dp, CircleShape)
                .size(20.dp)
                .background(if (checked) Accent else Color(0xFFECECEC), CircleShape),
        )
    }
}

@Composable
fun PrefSection(title: String) {
    val c = LocalTapeTapeColors.current
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Text(title, fontSize = 14.sp, color = Accent, modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 6.dp))
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = LocalTapeTapeColors.current
    TapeTapeDialog(onDismiss, title = title, dismiss = stringResource(R.string.cancel) to onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            options.forEachIndexed { i, label ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(i) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = i == selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.dialogAccent, unselectedColor = c.hint),
                        modifier = Modifier.padding(10.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(label, fontSize = 18.sp, color = c.text)
                }
            }
        }
    }
}
