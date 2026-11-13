package com.example.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.LocalAd
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.NaturalPrimaryDark
import com.example.ui.theme.SurfaceVariantLight

private class CtaMeta(val label: String, val icon: ImageVector, val container: Brush, val content: Color)

private fun ctaFor(linkType: String?): CtaMeta? = when (linkType) {
    "whatsapp" -> CtaMeta("WhatsApp", Icons.Default.Chat, Brush.linearGradient(listOf(Color(0xFF25D366), Color(0xFF1FB757))), Color.White)
    "call" -> CtaMeta("Call Now", Icons.Default.Call, Brush.linearGradient(listOf(NaturalPrimary, NaturalPrimaryDark)), Color.White)
    "map" -> CtaMeta("Visit Now", Icons.Default.Place, Brush.linearGradient(listOf(Color.White, Color.White)), NaturalPrimaryDark)
    "website" -> CtaMeta("Visit Now", Icons.Default.OpenInNew, Brush.linearGradient(listOf(Color.White, Color.White)), NaturalPrimaryDark)
    else -> null
}

/**
 * Paid banners from local businesses (table local_ads, managed by the city's own City Admin).
 * Styled like a native sponsored feed ad (same as the website): 16:9 photo or looping muted video,
 * a "SPONSORED" pill top-left, and a bottom gradient with the advertiser name and — for a
 * clickable ad — a Visit Now / WhatsApp / Call Now pill, all inside one card.
 */
@Composable
fun LocalAdStrip(
    ads: List<LocalAd>,
    onOpen: (LocalAd) -> Unit,
    modifier: Modifier = Modifier
) {
    if (ads.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ads.forEach { ad -> LocalAdCard(ad, onOpen) }
    }
}

@Composable
fun LocalAdCard(ad: LocalAd, onOpen: (LocalAd) -> Unit, modifier: Modifier = Modifier) {
    val cta = if (ad.isClickable) ctaFor(ad.linkType) else null
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceVariantLight)
            .aspectRatio(16f / 9f)
    ) {
        if (ad.isVideo) {
            AdVideo(ad.videoUrl.orEmpty(), Modifier.matchParentSize())
        } else {
            ProductImage(url = ad.imageUrl.orEmpty(), contentDescription = ad.advertiserName, modifier = Modifier.matchParentSize())
        }
        Text(
            text = "SPONSORED",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 9.dp, vertical = 3.dp)
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f), Color.Black.copy(alpha = 0.72f))))
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = ad.advertiserName,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (cta != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .background(cta.container)
                        .clickable { onOpen(ad) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(cta.icon, contentDescription = null, tint = cta.content, modifier = Modifier.size(14.dp))
                    Text(cta.label, color = cta.content, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            } else if (ad.isClickable) {
                // clickable with an unknown link type: the whole card opens it
                Spacer(Modifier.width(0.dp))
            }
        }
        if (ad.isClickable && cta == null) {
            Box(Modifier.matchParentSize().clickable { onOpen(ad) })
        }
    }
}

/** Looping, muted, autoplaying video (platform VideoView, no extra dependency). */
@Composable
private fun AdVideo(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    start()
                }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        update = { v -> if (!v.isPlaying) v.start() }
    )
}
