package com.bitchat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val TealPrimary = Color(0xFF006A68)
val TealOnPrimary = Color(0xFFFFFFFF)
val TealPrimaryContainer = Color(0xFF9CF1ED)
val TealOnPrimaryContainer = Color(0xFF00201F)
val TealSecondary = Color(0xFF4A6363)
val TealOnSecondary = Color(0xFFFFFFFF)
val TealSecondaryContainer = Color(0xFFCCE8E7)
val TealOnSecondaryContainer = Color(0xFF051F1F)
val TealBackground = Color(0xFFFAFDFC)
val TealSurface = Color(0xFFFAFDFC)
val Error = Color(0xFFBA1A1A)

@Immutable
data class GhostwireSemanticColors(
    val online: Color,
    val offline: Color,
    val deliveryPending: Color,
    val deliverySent: Color,
    val deliveryDelivered: Color,
    val warning: Color,
    val verified: Color,
)

val LightSemanticColors = GhostwireSemanticColors(
    online = Color(0xFF15803D),
    offline = Color(0xFF64748B),
    deliveryPending = Color(0xFF8A5A00),
    deliverySent = Color(0xFF0F766E),
    deliveryDelivered = Color(0xFF15803D),
    warning = Color(0xFFB45309),
    verified = Color(0xFF0F766E),
)

val DarkSemanticColors = GhostwireSemanticColors(
    online = Color(0xFF4ADE80),
    offline = Color(0xFF94A3B8),
    deliveryPending = Color(0xFFFBBF24),
    deliverySent = Color(0xFF5EEAD4),
    deliveryDelivered = Color(0xFF86EFAC),
    warning = Color(0xFFFBBF24),
    verified = Color(0xFF5EEAD4),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
