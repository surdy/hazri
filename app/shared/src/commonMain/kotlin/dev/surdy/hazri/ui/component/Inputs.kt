package dev.surdy.hazri.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.surdy.hazri.ui.theme.HazriColors
import dev.surdy.hazri.ui.theme.LocalHazriFonts

/**
 * The app's only text input.
 *
 * `BasicTextField` rather than Material's `TextField`: every field in this app sits inside
 * a card that already carries the border and the label, and Material's decoration box would
 * add a second, differently coloured one.
 */
@Composable
fun HazriTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val shape = RoundedCornerShape(10.dp)
    val fonts = LocalHazriFonts.current
    val style = TextStyle(
        fontFamily = if (mono) fonts.mono else fonts.ui,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = HazriColors.text,
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(HazriColors.surfaceSunken)
            .border(1.dp, HazriColors.border, shape)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, style = style.copy(color = HazriColors.muted))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(HazriColors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A labelled field in a settings-style list. */
@Composable
fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.textSecondary)
        HazriTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            mono = mono,
            keyboardType = keyboardType,
            modifier = Modifier.fillMaxWidth(),
        )
        if (helper != null) {
            Text(helper, fontSize = 11.sp, color = HazriColors.muted)
        }
    }
}

/** An inline "type a name and confirm" panel. Used to add a room and to rename a node. */
@Composable
fun TextPrompt(
    label: String,
    placeholder: String,
    initial: String = "",
    confirmLabel: String = "Add",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    HazriCard(padding = 14) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
        HazriTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
            PrimaryButton(
                label = confirmLabel,
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = { onConfirm(text.trim()) },
            )
        }
    }
}

/**
 * A minus / value / plus row.
 *
 * The numeric settings here are all coarse — an EMA weight to one decimal, a threshold to
 * the dB — and a stepper is faster and less error-prone on a phone held one-handed while
 * walking than a keyboard is.
 */
@Composable
fun Stepper(
    label: String,
    value: String,
    helper: String? = null,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HazriColors.text)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton("−", onDecrement)
                MonoText(
                    text = value,
                    size = 14,
                    modifier = Modifier.defaultMinSize(minWidth = 52.dp),
                    align = TextAlign.Center,
                )
                StepperButton("+", onIncrement)
            }
        }
        if (helper != null) {
            Text(helper, fontSize = 11.sp, color = HazriColors.muted)
        }
    }
}

@Composable
private fun StepperButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(HazriColors.surfaceRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HazriColors.text)
    }
}
