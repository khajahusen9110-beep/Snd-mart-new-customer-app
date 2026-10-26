package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextSecondary

/**
 * The post-order reward: a "rub to reveal" scratch card. The gray foil is drawn on its own
 * offscreen layer so erasing it with BlendMode.Clear reveals the reward content underneath,
 * rather than punching through to black. Auto-reveals once enough of the card is scratched.
 */
@Composable
fun ScratchCardDialog(amount: Double, onRevealed: () -> Unit, onDismiss: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    var revealedOnce by remember { mutableStateOf(false) }
    var strokeCount by remember { mutableStateOf(0) }
    val path = remember { Path() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🎉 Order Placed!", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "You earned a scratch card — rub it off to reveal your reward.",
                    color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(NaturalPrimary, Color(0xFF8B5CF6))))
                ) {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Text("₹${amount.toInt()}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                    }

                    if (!revealed) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset -> path.moveTo(offset.x, offset.y) }
                                    ) { change, _ ->
                                        path.lineTo(change.position.x, change.position.y)
                                        strokeCount++
                                        if (strokeCount > 35 && !revealedOnce) {
                                            revealedOnce = true
                                            revealed = true
                                            onRevealed()
                                        }
                                    }
                                }
                        ) {
                            drawRect(color = Color(0xFFC0C6D6))
                            drawIntoCanvas { canvas ->
                                val clearPaint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 70f
                                    strokeCap = android.graphics.Paint.Cap.ROUND
                                    strokeJoin = android.graphics.Paint.Join.ROUND
                                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                                }
                                canvas.nativeCanvas.drawPath(path.asAndroidPath(), clearPaint)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (revealed) {
                    Text(
                        "You won ₹${amount.toInt()}! It'll be added to your wallet once this order is delivered.",
                        color = SuccessGreen, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Nice!")
                    }
                } else {
                    Text("Swipe your finger over the card", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}
