package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.OutlineSoft

// Website look for the secondary screens (src/styles.css "Design system v2"):
// white cards, hairline border, 16dp radius, soft shadow; 14dp inputs with a primary focus ring.

internal val AppCardShape = RoundedCornerShape(16.dp)
internal val AppCardBorder = BorderStroke(1.dp, OutlineSoft)

@Composable
internal fun appCardColors() = CardDefaults.cardColors(containerColor = Color.White)

@Composable
internal fun appCardElevation() = CardDefaults.cardElevation(defaultElevation = 1.dp)

/** Text-field colors: 14dp-style outline, primary focus border, white fill. */
@Composable
internal fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NaturalPrimary,
    unfocusedBorderColor = OutlineBorder,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White
)

internal val AppFieldShape = RoundedCornerShape(14.dp)
