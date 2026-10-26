package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LocalAd

/**
 * Paid banners from local businesses (table local_ads, managed by the city's own City Admin).
 * Separate from PromoBannerCarousel (Sndmart's own offers): shows a small "Ad" tag for
 * transparency, and only opens a link when the admin marked it Clickable.
 */
@Composable
fun LocalAdStrip(
    ads: List<LocalAd>,
    onOpen: (LocalAd) -> Unit,
    modifier: Modifier = Modifier
) {
    if (ads.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ads.forEach { ad ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (ad.isClickable) Modifier.clickable { onOpen(ad) } else Modifier)
            ) {
                ProductImage(
                    url = ad.imageUrl,
                    contentDescription = ad.advertiserName,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("Ad", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
