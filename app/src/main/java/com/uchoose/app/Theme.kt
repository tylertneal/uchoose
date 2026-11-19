package com.uchoose.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF161616)
    val Surface = Color(0xFF202020)
    val SurfaceAlt = Color(0xFF2A2A2A)
    val Line = Color(0x33FFFFFF)
    val Gold = Color(0xFFC8A96A)
    val Text = Color(0xFFF2EDE4)
    val Muted = Color(0xFF9A948A)
}

object Type {
    val Display = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        letterSpacing = 2.sp,
        color = Palette.Text,
    )
    val Title = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 24.sp,
        letterSpacing = 1.sp,
        color = Palette.Text,
    )
    val Body = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 15.sp,
        color = Palette.Muted,
    )
    val Label = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 13.sp,
        letterSpacing = 3.sp,
        color = Palette.Gold,
    )
}

@Composable
fun UChooseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Gold,
            background = Palette.Background,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}
