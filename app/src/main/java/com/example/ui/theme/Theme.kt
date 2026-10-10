package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

enum class AppThemePreset(
    val id: String,
    val titleFa: String,
    val previewPrimary: Color,
    val previewSecondary: Color,
    val previewBg: Color,
    val isDark: Boolean = true
) {
    OBSIDIAN("obsidian", "آبسیدین نئون (پیش‌فرض)", Color(0xFF6366F1), Color(0xFF06B6D4), Color(0xFF090D16), true),
    MIDNIGHT_NAVY("midnight_navy", "سرمه‌ای کهکشانی", Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFF0A1128), true),
    CYBERPUNK_EMERALD("cyberpunk_emerald", "زمردی ماتریکس", Color(0xFF10B981), Color(0xFF14B8A6), Color(0xFF08140E), true),
    CRIMSON_ROSE("crimson_rose", "یاقوتی و زرشکی", Color(0xFFE11D48), Color(0xFFA855F7), Color(0xFF170A12), true),
    PEARL_LIGHT("pearl_light", "مرواریدی روز (روشن)", Color(0xFF4338CA), Color(0xFF0D9488), Color(0xFFF8FAFC), false);

    companion object {
        fun fromId(id: String): AppThemePreset = values().firstOrNull { it.id == id } ?: OBSIDIAN
    }
}

enum class AppFontFamilyPreset(val id: String, val titleFa: String, val fontFamily: FontFamily) {
    VAZIRMATN("vazirmatn", "وزیرمتن (مدرن)", FontFamily(
            Font(R.font.vazirmatn_regular, FontWeight.Normal),
            Font(R.font.vazirmatn_medium, FontWeight.Medium),
            Font(R.font.vazirmatn_bold, FontWeight.Bold)
        )
    ),
    SERIF("serif", "نسخ و رسمی (سریف)", FontFamily.Serif),
    MONOSPACE("monospace", "یکدست و فنی (مونو)", FontFamily.Monospace);

    companion object {
        fun fromId(id: String): AppFontFamilyPreset = values().firstOrNull { it.id == id } ?: VAZIRMATN
    }
}

private val ObsidianColorScheme = darkColorScheme(
    primary = Color(0xFF6366F1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E1B4B),
    onPrimaryContainer = Color(0xFFA5B4FC),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF083344),
    onSecondaryContainer = Color(0xFF67E8F9),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color.Black,
    background = Color(0xFF090D16),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B)
)

private val MidnightNavyColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFF93C5FD),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF451A03),
    onSecondaryContainer = Color(0xFFFDE68A),
    tertiary = Color(0xFF06B6D4),
    onTertiary = Color.Black,
    background = Color(0xFF0A1128),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF101F42),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E2D5A),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2E4374),
    outlineVariant = Color(0xFF1A294A)
)

private val CyberpunkEmeraldColorScheme = darkColorScheme(
    primary = Color(0xFF10B981),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFF6EE7B7),
    secondary = Color(0xFF14B8A6),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFF5EEAD4),
    tertiary = Color(0xFF34D399),
    onTertiary = Color.Black,
    background = Color(0xFF08140E),
    onBackground = Color(0xFFECFDF5),
    surface = Color(0xFF0F241C),
    onSurface = Color(0xFFECFDF5),
    surfaceVariant = Color(0xFF16382C),
    onSurfaceVariant = Color(0xFF86EFAC),
    outline = Color(0xFF1F4E3C),
    outlineVariant = Color(0xFF0F2E22)
)

private val CrimsonRoseColorScheme = darkColorScheme(
    primary = Color(0xFFE11D48),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4C0519),
    onPrimaryContainer = Color(0xFFFDA4AF),
    secondary = Color(0xFFA855F7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B0764),
    onSecondaryContainer = Color(0xFFD8B4FE),
    tertiary = Color(0xFFFB7185),
    onTertiary = Color.Black,
    background = Color(0xFF170A12),
    onBackground = Color(0xFFFFF1F2),
    surface = Color(0xFF26101E),
    onSurface = Color(0xFFFFF1F2),
    surfaceVariant = Color(0xFF3B1830),
    onSurfaceVariant = Color(0xFFFDA4AF),
    outline = Color(0xFF5C2346),
    outlineVariant = Color(0xFF2D1223)
)

private val PearlLightColorScheme = lightColorScheme(
    primary = Color(0xFF4338CA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

fun createScaledTypography(fontScale: Float, fontFamily: FontFamily): Typography {
    val scale = fontScale.coerceIn(0.75f, 1.4f)
    return Typography(
        displayLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (32 * scale).sp,
            lineHeight = (40 * scale).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (24 * scale).sp,
            lineHeight = (32 * scale).sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (20 * scale).sp,
            lineHeight = (28 * scale).sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16 * scale).sp,
            lineHeight = (24 * scale).sp
        ),
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (15 * scale).sp,
            lineHeight = (24 * scale).sp
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (14 * scale).sp,
            lineHeight = (20 * scale).sp
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (12 * scale).sp,
            lineHeight = (16 * scale).sp
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (14 * scale).sp,
            lineHeight = (20 * scale).sp
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (10 * scale).sp,
            lineHeight = (14 * scale).sp
        ),
        displayMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (28 * scale).sp,
            lineHeight = (36 * scale).sp
        ),
        displaySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (24 * scale).sp,
            lineHeight = (32 * scale).sp
        ),
        headlineLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (32 * scale).sp,
            lineHeight = (40 * scale).sp
        ),
        headlineSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (24 * scale).sp,
            lineHeight = (32 * scale).sp
        ),
        titleSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (14 * scale).sp,
            lineHeight = (20 * scale).sp
        ),
        labelMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (12 * scale).sp,
            lineHeight = (16 * scale).sp
        )
    )
}

@Composable
fun AIChatExporterTheme(
    themePreset: AppThemePreset = AppThemePreset.OBSIDIAN,
    fontScale: Float = 1.0f,
    fontFamilyPreset: AppFontFamilyPreset = AppFontFamilyPreset.VAZIRMATN,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themePreset) {
        AppThemePreset.OBSIDIAN -> ObsidianColorScheme
        AppThemePreset.MIDNIGHT_NAVY -> MidnightNavyColorScheme
        AppThemePreset.CYBERPUNK_EMERALD -> CyberpunkEmeraldColorScheme
        AppThemePreset.CRIMSON_ROSE -> CrimsonRoseColorScheme
        AppThemePreset.PEARL_LIGHT -> PearlLightColorScheme
    }

    val dynamicTypography = createScaledTypography(fontScale, fontFamilyPreset.fontFamily)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = dynamicTypography,
        content = content
    )
}
