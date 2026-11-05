package io.embodit.seedpits.ui.control

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

/**
 * Text that reports clipping (profile D13): any visual overflow logs `OVERFLOW <id>` so the longest-language
 * sweep can be checked from logcat. Every Text in the window goes through this.
 */
@Composable
internal fun T(
    text: String,
    id: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = if (align != null) style.copy(textAlign = align) else style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        onTextLayout = { r -> if (r.hasVisualOverflow) Log.w("SeedPits", "OVERFLOW $id text=\"$text\"") },
    )
}

/** The page's main action: 56 dp, ochre. */
@Composable
internal fun PrimaryButton(text: String, id: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true, tall: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = if (tall) 64.dp else 56.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(if (tall) 32.dp else 28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
            T(text, "btn/$id", PicoTheme.typography.titleMedium, PicoTheme.colorScheme.labelPrimaryLight)
        }
    }
}

/** A secondary action on glass: 48 dp, icon + words (profile B19: no bare icons). */
@Composable
internal fun SecondaryButton(
    text: String, id: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null,
    enabled: Boolean = true, active: Boolean = false, minWidth: Dp = 96.dp,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = minWidth),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (active) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        val fg = if (active) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
            T(text, "btn/$id", PicoTheme.typography.labelLarge, fg)
        }
    }
}

@Composable
internal fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

@Composable
internal fun HGap(w: Dp = 12.dp) = Spacer(Modifier.width(w))
