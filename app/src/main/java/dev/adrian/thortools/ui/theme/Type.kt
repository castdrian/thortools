package dev.adrian.thortools.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)

private fun TextStyle.lowerDisplay(fontSize: TextUnit, lineHeight: TextUnit): TextStyle = copy(
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
)

val LowerDisplayTypography = Typography.copy(
    bodyLarge = Typography.bodyLarge.lowerDisplay(18.sp, 26.sp),
    bodyMedium = Typography.bodyMedium.lowerDisplay(18.sp, 26.sp),
    bodySmall = Typography.bodySmall.lowerDisplay(18.sp, 24.sp),
    displayLarge = Typography.displayLarge.lowerDisplay(56.sp, 64.sp),
    displayMedium = Typography.displayMedium.lowerDisplay(48.sp, 56.sp),
    displaySmall = Typography.displaySmall.lowerDisplay(42.sp, 50.sp),
    headlineLarge = Typography.headlineLarge.lowerDisplay(38.sp, 46.sp),
    headlineMedium = Typography.headlineMedium.lowerDisplay(34.sp, 42.sp),
    headlineSmall = Typography.headlineSmall.lowerDisplay(30.sp, 38.sp),
    labelLarge = Typography.labelLarge.lowerDisplay(18.sp, 24.sp),
    labelMedium = Typography.labelMedium.lowerDisplay(18.sp, 24.sp),
    labelSmall = Typography.labelSmall.lowerDisplay(18.sp, 24.sp),
    titleLarge = Typography.titleLarge.lowerDisplay(26.sp, 34.sp),
    titleMedium = Typography.titleMedium.lowerDisplay(23.sp, 30.sp),
    titleSmall = Typography.titleSmall.lowerDisplay(20.sp, 26.sp),
)
