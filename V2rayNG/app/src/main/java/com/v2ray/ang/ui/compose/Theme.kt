package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF087A70),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7F4EF),
    onPrimaryContainer = Color(0xFF073D38),
    secondary = Color(0xFFC94B64),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE1E7),
    onSecondaryContainer = Color(0xFF5B1728),
    tertiary = Color(0xFF52647A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE5EF),
    onTertiaryContainer = Color(0xFF243241),
    error = Color(0xFFB4232E),
    errorContainer = Color(0xFFFFDADD),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF4B0710),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF171A1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = Color(0xFF59616C),
    outline = Color(0xFF7A838E),
    outlineVariant = Color(0xFFD7DCE2),
    inverseSurface = Color(0xFF272B31),
    inverseOnSurface = Color(0xFFF2F4F6),
    inversePrimary = Color(0xFF67D8C9),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF087A70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F7),
    surfaceContainer = Color(0xFFEEF1F4),
    surfaceContainerHigh = Color(0xFFE7EBEF),
    surfaceContainerHighest = Color(0xFFDDE2E8),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF67D8C9),
    onPrimary = Color(0xFF003D37),
    primaryContainer = Color(0xFF0B514B),
    onPrimaryContainer = Color(0xFFB8F0E8),
    secondary = Color(0xFFFF8DA2),
    onSecondary = Color(0xFF5A1022),
    secondaryContainer = Color(0xFF762A3D),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFB8C8DA),
    onTertiary = Color(0xFF233343),
    tertiaryContainer = Color(0xFF394B5E),
    onTertiaryContainer = Color(0xFFD8E4F1),
    error = Color(0xFFFFB3B8),
    errorContainer = Color(0xFF8D1723),
    onError = Color(0xFF65000D),
    onErrorContainer = Color(0xFFFFDADD),
    background = Color(0xFF111417),
    onBackground = Color(0xFFE8EBEF),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE8EBEF),
    surfaceVariant = Color(0xFF252A31),
    onSurfaceVariant = Color(0xFFB8C0CA),
    outline = Color(0xFF89929D),
    outlineVariant = Color(0xFF343A42),
    inverseSurface = Color(0xFFE8EBEF),
    inverseOnSurface = Color(0xFF24282E),
    inversePrimary = Color(0xFF087A70),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF67D8C9),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF14171B),
    surfaceContainer = Color(0xFF1A1E23),
    surfaceContainerHigh = Color(0xFF20252B),
    surfaceContainerHighest = Color(0xFF292F36),
)

// Semantic Colors
val colorPing = Color(0xFF169B6B)
val colorPingRed = Color(0xFFD64555)
val colorConfigType = Color(0xFF52647A)
val colorFabActive = Color(0xFF087A70)
val colorFabInactiveLight = Color(0xFF65707C)
val colorFabInactiveDark = Color(0xFFAEB7C1)
val dividerColorLight = Color(0xFFDDE2E8)
val dividerColorDark = Color(0xFF343A42)

private val MiaomiaoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

// Toast Colors 70%
val toastNormalBgLight = Color(0xB3353A3E) // Dark Gray
val toastNormalBgDark = Color(0xB34A4F54) // Darker Gray
val toastSuccessBg = Color(0xB3388E3C) // Green
val toastErrorBg = Color(0xB3D50000) // Red
val toastInfoBg = Color(0xB33F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColor else LightColor
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = MiaomiaoShapes,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
