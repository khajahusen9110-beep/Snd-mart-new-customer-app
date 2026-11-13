package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer effect modifier that creates a gentle pulsating light sweep over placeholder shapes.
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(x = translateAnim.value - 200f, y = translateAnim.value - 200f),
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    background(brush)
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .shimmerEffect()
    )
}

@Composable
fun ProductCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.example.ui.theme.OutlineSoft
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Product image skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            // Product title skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(16.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Unit / description skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Price & Add button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .width(60.dp)
                        .height(18.dp)
                )
                SkeletonBox(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
fun GroceryProductGridSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 6
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (i in 0 until (count / 2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProductCardSkeleton(modifier = Modifier.weight(1f))
                ProductCardSkeleton(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun HotelCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.example.ui.theme.OutlineSoft
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // Banner skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBox(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(18.dp)
                    )
                    SkeletonBox(
                        modifier = Modifier
                            .width(44.dp)
                            .height(22.dp),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(14.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(
                        modifier = Modifier
                            .width(70.dp)
                            .height(20.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                    SkeletonBox(
                        modifier = Modifier
                            .width(90.dp)
                            .height(20.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HotelsListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 4
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(count) {
            HotelCardSkeleton()
        }
    }
}

@Composable
fun OrderCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.example.ui.theme.OutlineSoft
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .width(130.dp)
                        .height(18.dp)
                )
                SkeletonBox(
                    modifier = Modifier
                        .width(80.dp)
                        .height(24.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(14.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .width(70.dp)
                        .height(20.dp)
                )
                SkeletonBox(
                    modifier = Modifier
                        .width(90.dp)
                        .height(30.dp),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }
}

@Composable
fun OrderListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 4
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(count) {
            OrderCardSkeleton()
        }
    }
}

@Composable
fun NotificationCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            com.example.ui.theme.OutlineSoft
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            SkeletonBox(
                modifier = Modifier.size(40.dp),
                shape = CircleShape
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(10.dp)
                )
            }
        }
    }
}

@Composable
fun NotificationListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 5
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(count) {
            NotificationCardSkeleton()
        }
    }
}
