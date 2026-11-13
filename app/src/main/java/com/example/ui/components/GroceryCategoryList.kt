package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * "Shop by Category" as a row of pill tabs (round photo + name; the selected one is filled with the
 * brand color). Deliberately not card-shaped, so categories never read as products. Scrolls
 * sideways when there are more than fit.
 */
@Composable
fun GroceryCategoryList(
    categories: List<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "Shop by Category",
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("grocery_category_list"),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categories, key = { it.id }) { category ->
                GroceryCategoryPill(
                    name = category.name,
                    imageUrl = category.imageUrl,
                    isSelected = selectedCategoryId == category.id,
                    fallbackIcon = Icons.Outlined.Storefront,
                    onClick = { onCategorySelected(category.id) },
                    testTag = "category_item_${category.id}"
                )
            }
        }
    }
}

@Composable
private fun GroceryCategoryPill(
    name: String,
    imageUrl: String?,
    isSelected: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (isSelected) NaturalPrimary else Color.White,
        border = BorderStroke(1.dp, if (isSelected) NaturalPrimary else OutlineSoft),
        shadowElevation = if (isSelected) 6.dp else 1.5.dp,
        modifier = modifier.widthIn(min = 132.dp).testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 16.dp)
        ) {
            Surface(
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(2.dp, Color.White)
            ) {
                val fallbackPainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(fallbackIcon)
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(imageUrl).crossfade(300).scale(Scale.FILL).build(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        placeholder = fallbackPainter,
                        error = fallbackPainter,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(fallbackIcon, contentDescription = name, tint = TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
