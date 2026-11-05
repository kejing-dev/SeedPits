package io.embodit.seedpits.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens (design-package theme.colorScheme): ochre primary, wood secondary, slate-blue tertiary,
 * ink labels (not the Vibrant greys, Xiangqi device lesson), board highlight colours.
 */
object SeedColors {
    val Primary = Color(0xFFA0522D) // design-style: fixed-figma-color theme.colorScheme.primary 赭石
    val OnPrimary = Color(0xFFFFFFFF) // design-style: fixed-figma-color theme.colorScheme.onPrimary
    val PrimaryContainer = Color(0xFFF5D9C8) // design-style: fixed-figma-color theme.colorScheme.primaryContainer
    val OnPrimaryContainer = Color(0xFF3E1A0A) // design-style: fixed-figma-color theme.colorScheme.onPrimaryContainer
    val Secondary = Color(0xFF8C6A3F) // design-style: fixed-figma-color theme.colorScheme.secondary 木色
    val SecondaryContainer = Color(0xFFEFDCC0) // design-style: fixed-figma-color theme.colorScheme.secondaryContainer
    val OnSecondaryContainer = Color(0xFF3B2A12) // design-style: fixed-figma-color theme.colorScheme.onSecondaryContainer
    val Tertiary = Color(0xFF5B7A86) // design-style: fixed-figma-color theme.colorScheme.tertiary 青灰
    val Error = Color(0xFF7A1F1C) // design-style: fixed-figma-color error darkened for text on the emulator glass (#AFACA8 measured, 4.5:1)
    val Ink = Color(0xFF1B1F23) // design-style: fixed-figma-color theme.colorScheme.onSurface 实墨色
    val InkSecondary = Color(0xFF33383D) // design-style: fixed-figma-color derived ink, 4.5:1 on light glass
    val InkTertiary = Color(0xFF33383D) // design-style: fixed-figma-color derived ink, 4.5:1 on the measured glass
    val Alert = Color(0xFF8A5A10) // design-style: fixed-figma-color darkened amber for text on glass
    val Passable = Color(0xFF3E7A4E) // design-style: fixed-figma-color passable
    val FillTertiary = Color(0xFFEFDCC0) // design-style: fixed-figma-color secondaryContainer as tertiary fill
    val Divider = Color(0xFFD7C3A6) // design-style: fixed-figma-color divider

    // ---- board highlight tokens (also used on the panel's mirror digits) ----
    val PathBlue = Color(0xFF3F7FD9) // design-style: fixed-figma-color highlightColors.path
    val EndAmber = Color(0xFFE0A040) // design-style: fixed-figma-color highlightColors.end
    val CaptureOrange = Color(0xFFD8842A) // design-style: fixed-figma-color highlightColors.capture
    val Wood = Color(0xFFE8C9A0) // design-style: fixed-figma-color digit tile wood
    val StoreYou = Color(0xFF8A4424) // design-style: fixed-figma-color ochre darkened, 3:1 large text on glass
    val StoreThem = Color(0xFF3F5A66) // design-style: fixed-figma-color slate darkened, 3:1 large text on glass
    val Opponent = Color(0xFF5B7A86) // design-style: fixed-figma-color seat colour of the AI (tertiary)
}
