package com.finexy.mobile

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Shared Finexy identity: coral action, neutral surfaces and green income.
internal val Coral = Color(0xFFF05537)
internal val CoralButton = Color(0xFFCB4027)
internal val IncomeGreen = Color(0xFF62D3A0)
internal val CanvasBlack: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val PanelRaised: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val WalletHighlight: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
internal val Hairline: Color @Composable get() = MaterialTheme.colorScheme.outline
internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val LightCanvas = Color(0xFFF7F5F2)
private val LightPanel = Color(0xFFFFFFFF)
private val LightPanelRaised = Color(0xFFEEEAE5)
private val LightWalletHighlight = Color(0xFFE4DED7)
private val LightHairline = Color(0xFFC8C0B8)
private val LightInk = Color(0xFF1A1917)
private val LightMuted = Color(0xFF625D57)
private val LightIncomeGreen = Color(0xFF16845B)

@Composable
internal fun FinexyTheme(isLightTheme: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLightTheme
                isAppearanceLightNavigationBars = isLightTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (isLightTheme) lightColorScheme(
            primary = CoralButton, onPrimary = Color.White,
            background = LightCanvas, onBackground = LightInk,
            surface = LightPanel, onSurface = LightInk,
            surfaceVariant = LightPanelRaised, onSurfaceVariant = LightMuted,
            secondary = LightIncomeGreen, onSecondary = Color.White,
            tertiaryContainer = LightWalletHighlight,
            outline = LightHairline, error = Color(0xFFB3261E)
        ) else darkColorScheme(
            primary = CoralButton, onPrimary = Color.White,
            background = Color(0xFF101113), onBackground = Color(0xFFF5F5F6),
            surface = Color(0xFF202226), onSurface = Color(0xFFF5F5F6),
            surfaceVariant = Color(0xFF292C31), onSurfaceVariant = Color(0xFFADB2BC),
            secondary = IncomeGreen, onSecondary = Color(0xFF101113),
            tertiaryContainer = Color(0xFF36393E),
            outline = Color(0xFF666B75), error = Color(0xFFFF9D91)
        ),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, letterSpacing = (-1.2).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp)
        ),
        content = content
    )
}
