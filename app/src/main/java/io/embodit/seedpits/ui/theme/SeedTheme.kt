package io.embodit.seedpits.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Typography
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.seedpits.R
import io.embodit.seedpits.domain.game.Language
import java.util.Locale

/**
 * PicoTheme with all 16 roles assigned (labels in ink, the ochre interaction colour) and Noto Sans.
 * [language] localises resources below this point through a ContextWrapper that only swaps getResources()
 * (a bare configuration context crashes a SpatialView: Cloud Air lesson).
 */
@Composable
fun SeedTheme(language: Language = Language.SYSTEM, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, language) { base.localized(language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = SeedColors.FillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = SeedColors.OnPrimary,
        labelPrimary = SeedColors.Ink,
        labelSecondary = SeedColors.InkSecondary,
        labelTertiary = SeedColors.InkTertiary,
        labelQuaternary = SeedColors.InkTertiary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = SeedColors.Error,
        alert = SeedColors.Alert,
        passable = SeedColors.Passable,
        interaction = SeedColors.Primary,
        dividerLine = SeedColors.Divider,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}

val NotoSans: FontFamily = FontFamily(
    Font(R.font.noto_sans_regular, FontWeight.Normal),
    Font(R.font.noto_sans_medium, FontWeight.Medium),
    Font(R.font.noto_sans_semibold, FontWeight.SemiBold),
    Font(R.font.noto_sans_bold, FontWeight.Bold),
)

private fun TextStyle.noto(): TextStyle = copy(fontFamily = NotoSans)

fun Typography.withNotoSans(): Typography = copy(
    bodyLarge = bodyLarge.noto(), bodyLargeMultiline = bodyLargeMultiline.noto(), bodyMedium = bodyMedium.noto(),
    bodyMediumMultiline = bodyMediumMultiline.noto(), bodySmall = bodySmall.noto(), bodyTiny = bodyTiny.noto(),
    displayLarge = displayLarge.noto(), displayMedium = displayMedium.noto(), displaySmall = displaySmall.noto(),
    headlineLarge = headlineLarge.noto(), headlineMedium = headlineMedium.noto(), headlineSmall = headlineSmall.noto(),
    labelLarge = labelLarge.noto(), labelMedium = labelMedium.noto(), labelSmall = labelSmall.noto(),
    titleLarge = titleLarge.noto(), titleMedium = titleMedium.noto(), titleSmall = titleSmall.noto(),
)
