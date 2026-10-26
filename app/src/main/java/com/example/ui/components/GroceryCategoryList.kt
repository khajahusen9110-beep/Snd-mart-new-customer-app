package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.example.data.model.Category
import com.example.ui.theme.*

private const val COLUMNS = 4

/**
 * All grocery categories, shown as a 4-per-row grid (wraps to as many rows as needed — no
 * vertical limit). Tapping a card selects that category. A non-lazy grid (chunked rows) since
 * this sits as a single item inside the outer product LazyVerticalGrid.
 */
@Composable
fun GroceryCategoryList(
    categories: List<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Shop by Category",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("grocery_category_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            categories.chunked(COLUMNS).forEach { rowCategories ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowCategories.forEach { category ->
                        GroceryCategoryCard(
                            name = category.name,
                            imageUrl = category.imageUrl,
                            isSelected = selectedCategoryId == category.id,
                            fallbackIcon = Icons.Outlined.Storefront,
                            onClick = { onCategorySelected(category.id) },
                            testTag = "category_item_${category.id}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Pad a partial last row so earlier rows' cards keep the same width.
                    repeat(COLUMNS - rowCategories.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroceryCategoryCard(
    name: String,
    imageUrl: String?,
    isSelected: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag(testTag)
            .padding(vertical = 2.dp)
    ) {
        Surface(
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
            color = if (isSelected) PastelSage else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isSelected) BorderStroke(2.5.dp, NaturalPrimary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            val fallbackPainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(fallbackIcon)
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(300)
                        .scale(Scale.FILL)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    placeholder = fallbackPainter,
                    error = fallbackPainter,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = name,
                        tint = if (isSelected) NaturalPrimary else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) NaturalPrimary else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
