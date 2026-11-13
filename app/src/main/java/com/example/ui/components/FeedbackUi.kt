package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.example.R
import com.example.ui.theme.NaturalPrimary
import com.example.util.AppFeedback
import com.example.util.OrderCelebration
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * App-wide tap feedback: any press that a button / clickable already consumed gets the light tap
 * feedback (vibration + tick), like the website's single pointerdown listener. Put it on the
 * root container. Scrolling and touches on plain areas are left alone because nothing consumes
 * their initial down event.
 */
fun Modifier.globalTapFeedback(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        // Final pass: children (the clickable) have already seen the event by now.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (down.isConsumed) {
            if (AppFeedback.suppressNextTap) AppFeedback.suppressNextTap = false else AppFeedback.tap()
        }
    }
}

/** For controls that play their own feedback (the cart stepper): the global tick skips their touches. */
fun Modifier.noTapFeedback(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        AppFeedback.suppressNextTap = true
    }
}

private val ConfettiColors = listOf(
    Color(0xFF7C4DFF), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF06B6D4), Color(0xFFEC4899)
)

/**
 * Full-screen "Order placed" moment: green screen, ring + tick drawn in, confetti burst, then it
 * fades by itself. Mount once anywhere in the app; trigger with OrderCelebration.show(). It is a
 * full-screen dialog window so it also covers the status and navigation bars.
 */
@Composable
fun OrderSuccessHost() {
    val shownAt = OrderCelebration.shownAt ?: return
    val alpha = remember(shownAt) { Animatable(0f) }
    LaunchedEffect(shownAt) {
        alpha.animateTo(1f, tween(250))
        delay(2000)
        alpha.animateTo(0f, tween(350))
        OrderCelebration.hide()
    }
    Dialog(
        onDismissRequest = { OrderCelebration.hide() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
            OrderSuccessScreen(onClick = { OrderCelebration.hide() })
        }
    }
}

@Composable
fun OrderSuccessScreen(onClick: () -> Unit = {}, progressOverride: Float? = null) {
    val ring = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val pop = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) {
        if (progressOverride == null) {
            pop.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(Unit) { if (progressOverride == null) ring.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) {
        if (progressOverride == null) {
            delay(400)
            tick.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(Unit) {
        if (progressOverride == null) {
            delay(350)
            burst.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
    }
    val ringP = progressOverride ?: ring.value
    val tickP = progressOverride ?: tick.value
    val burstP = progressOverride ?: burst.value
    val popS = if (progressOverride != null) 1f else pop.value

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF22A455), Color(0xFF128A40), Color(0xFF0B6B30))))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // confetti burst from just above the centre
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f - 60.dp.toPx()
            val count = 26
            for (i in 0 until count) {
                val a = (2 * PI * i / count).toFloat()
                val dist = (90 + ((i * 37) % 80)).dp.toPx() * 2.1f * burstP
                val alpha = if (burstP < 0.7f) 1f else ((1f - burstP) / 0.3f).coerceIn(0f, 1f)
                if (burstP <= 0f) continue
                val x = cx + cos(a) * dist
                val y = cy + sin(a) * dist
                val col = ConfettiColors[i % ConfettiColors.size].copy(alpha = alpha)
                translate(x, y) {
                    rotate(260f * burstP + i * 20f) {
                        if (i % 3 == 0) drawCircle(col, radius = 4.dp.toPx())
                        else drawRect(col, topLeft = Offset(-4.5.dp.toPx(), -7.dp.toPx()), size = Size(9.dp.toPx(), 14.dp.toPx()))
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(112.dp).scale(popS)) {
                val stroke = 4.dp.toPx()
                val r = size.minDimension / 2f - stroke
                drawCircle(Color.White.copy(alpha = 0.14f * ringP), radius = r)
                // ring: drawn clockwise from the top
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * ringP,
                    useCenter = false,
                    topLeft = Offset(stroke, stroke),
                    size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // tick: the same two segments as the website SVG (28,49.5 -> 42,63 -> 69,34 in a 96 box)
                val k = size.width / 96f
                val path = Path().apply {
                    moveTo(28f * k, 49.5f * k)
                    lineTo(42f * k, 63f * k)
                    lineTo(69f * k, 34f * k)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val seg = Path()
                measure.getSegment(0f, measure.length * tickP, seg, true)
                drawPath(seg, Color.White, style = Stroke(width = 7f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(
                "Order Placed!",
                color = Color.White.copy(alpha = tickP.coerceIn(0f, 1f)),
                fontSize = 26.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "ನಿಮ್ಮ ಆರ್ಡರ್ ಯಶಸ್ವಿಯಾಗಿದೆ",
                color = Color.White.copy(alpha = 0.92f * tickP.coerceIn(0f, 1f)),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** First-load screen: the brand mark breathing, instead of a bare spinner. */
@Composable
fun BrandSplash(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "splash")
    val breathe by t.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe"
    )
    val dots = List(3) { i ->
        t.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Reverse), label = "dot$i"
        )
    }
    Box(modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painter = painterResource(R.drawable.sndmart_icon),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(92.dp).scale(breathe).clip(RoundedCornerShape(26.dp))
            )
            Text("Sndmart", color = NaturalPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dots.forEach { d ->
                        Box(Modifier.size(7.dp).clip(CircleShape).background(NaturalPrimary.copy(alpha = d.value)))
                    }
                }
            }
        }
    }
}

/** Ready-made instance for call sites that prefer a val over the extension. */
val globalTapFeedbackModifier: Modifier get() = Modifier.globalTapFeedback()
