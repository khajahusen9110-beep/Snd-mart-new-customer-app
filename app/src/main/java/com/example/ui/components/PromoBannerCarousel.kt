package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PromoBanner
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.OutlineBorder
import kotlinx.coroutines.delay

private const val AUTO_SCROLL_MS = 4000L

/**
 * Super-Admin promo banners: swipeable, auto-advancing every 4 s, with dots. The admin's text is
 * written over the image (dark shade on the left) unless they turned that off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PromoBannerCarousel(
    banners: List<PromoBanner>,
    onOpen: (PromoBanner) -> Unit,
    modifier: Modifier = Modifier
) {
    if (banners.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { banners.size })

    if (banners.size > 1) {
        LaunchedEffect(banners.size) {
            while (true) {
                delay(AUTO_SCROLL_MS)
                if (!pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage((pagerState.currentPage + 1) % banners.size)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().testTag("promo_banner_carousel")) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
        ) { page ->
            val b = banners[page]
            val hasText = b.showText && (!b.title.isNullOrBlank() || !b.subtitle.isNullOrBlank() || (b.isClickable && !b.ctaText.isNullOrBlank()))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (b.isClickable) Modifier.clickable { onOpen(b) } else Modifier)
            ) {
                ProductImage(
                    url = b.imageUrl,
                    contentDescription = if (hasText) null else (b.title ?: "Offer"),
                    modifier = Modifier.fillMaxSize()
                )
                if (hasText) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = 0.72f),
                                    0.55f to Color.Black.copy(alpha = 0.4f),
                                    1f to Color.Transparent
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (!b.title.isNullOrBlank()) {
                            Text(
                                b.title.uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp,
                                lineHeight = 23.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.7f)
                            )
                        }
                        if (!b.subtitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                b.subtitle,
                                color = Color.White.copy(alpha = 0.95f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.7f)
                            )
                        }
                        if (b.isClickable && !b.ctaText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(color = Color(0xFFD32F2F), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    b.ctaText,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (banners.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(banners.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (active) 18.dp else 7.dp, height = 7.dp)
                            .clip(CircleShape)
                            .background(if (active) NaturalPrimary else OutlineBorder)
                    )
                }
            }
        }
    }
}
