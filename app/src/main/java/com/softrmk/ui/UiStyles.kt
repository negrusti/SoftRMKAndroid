package com.softrmk.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object UiPalette {
    val AppBackground = Color(0xFF050505)
    val PanelBackground = Color(0xFF111111)
    val OverlayBackground = Color(0xEE050505)
    val OverlayContentBackground = Color(0xFF0C0C0C)
    val PrimaryText = Color(0xFFF0F0F0)
    val SecondaryText = Color(0xFFD0D0D0)
    val MutedText = Color(0xFFADADAD)
    val Divider = Color(0xFF222222)
    val ButtonDisabledBackground = Color(0xFF555555)
    val ButtonDisabledContent = Color(0xFFB0B0B0)
    val RadioUnselected = Color(0xFF9A9A9A)
    val RadioDisabledSelected = Color(0xFF777777)
    val RadioDisabledUnselected = Color(0xFF555555)
    val DangerAccent = Color(0xFFF13B3B)
    val RotaryAccent = Color(0xFFD23942)
}

object UiBrushes {
    val Tile = Brush.verticalGradient(
        listOf(Color(0xFF2B2C2E), Color(0xFF1A1A1B), Color(0xFF232426))
    )
    val DpadSegment = Brush.verticalGradient(
        listOf(Color(0xFF3A3B3F), Color(0xFF26272A), Color(0xFF17181A))
    )
    val OkButton = Brush.verticalGradient(
        listOf(Color(0xFF3B3B3B), Color(0xFF181818), Color(0xFF2B2B2B))
    )
}

object UiShapes {
    val TileCorner = 24.dp
    val PanelCorner = 26.dp
}

@Composable
fun whiteButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.White,
    contentColor = Color.Black,
    disabledContainerColor = UiPalette.ButtonDisabledBackground,
    disabledContentColor = UiPalette.ButtonDisabledContent
)

@Composable
fun whiteTextButtonColors() = ButtonDefaults.textButtonColors(
    containerColor = Color.White,
    contentColor = Color.Black,
    disabledContainerColor = UiPalette.ButtonDisabledBackground,
    disabledContentColor = UiPalette.ButtonDisabledContent
)
