package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.HelperConfig
import com.example.data.model.TourText
import com.example.data.repository.HelperRepository
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.TextSecondary
import com.example.util.rupees
import kotlinx.coroutines.launch

/**
 * First-visit tour: a few swipeable cards on what Sndmart offers, with Quick Helper (when switched on for the
 * city) first and in most detail. Shown once per device; Profile > "App tour" opens it again.
 */
object AppTourState {
    /** Bumped by Profile > App tour to show the tour again. */
    var replayTick by mutableIntStateOf(0)
        private set

    fun replay() { replayTick++ }

    private const val PREFS = "app_tour"
    private const val KEY = "done_v1"
    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markSeen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private fun tourLang(context: Context): String {
    val saved = context.getSharedPreferences("tiffin_prefs", Context.MODE_PRIVATE).getString("lang", null)
    if (saved == "en" || saved == "kn") return saved
    return if (java.util.Locale.getDefault().language == "kn") "kn" else "en"
}

private class TourSlide(val icon: ImageVector, val title: String, val body: String, val helper: Boolean = false)

/** Mounted once at the app root. [homeVisible]: the first-visit tour waits until the customer is on Home. */
@Composable
fun AppTourHost(cityId: String?, homeVisible: Boolean, onOpenHelper: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<HelperConfig?>(null) }
    var lastReplay by remember { mutableIntStateOf(AppTourState.replayTick) }

    LaunchedEffect(cityId, homeVisible, AppTourState.replayTick) {
        if (cityId == null || open) return@LaunchedEffect
        val replay = AppTourState.replayTick != lastReplay
        if (!replay && (!homeVisible || AppTourState.seen(context))) return@LaunchedEffect
        lastReplay = AppTourState.replayTick
        val cfg = try { HelperRepository.getConfig(cityId) } catch (e: Exception) { null }
        config = cfg?.takeIf { it.enabled }
        open = true
    }
    if (!open) return

    val close = { AppTourState.markSeen(context); open = false }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        TourContent(config = config, onClose = close, onOpenHelper = { close(); onOpenHelper() })
    }
}

@Composable
private fun TourContent(config: HelperConfig?, onClose: () -> Unit, onOpenHelper: () -> Unit) {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(tourLang(context)) }
    val t = { key: String, args: Array<out Any?> -> TourText.t(lang, key, *args) }
    val slides = buildList {
        add(TourSlide(Icons.Filled.Store, t("tourWelcomeT", emptyArray()), t("tourWelcomeB", emptyArray())))
        if (config != null) add(TourSlide(Icons.Filled.Store, t("tourHelperT", emptyArray()), t("tourHelperB", emptyArray()), helper = true))
        add(TourSlide(Icons.Filled.SupportAgent, t("tourWaT", emptyArray()), t("tourWaB", emptyArray())))
        add(TourSlide(Icons.Filled.CardGiftcard, t("tourRewardsT", emptyArray()), t("tourRewardsB", emptyArray())))
    }
    val pager = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage >= slides.size - 1

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Color.White)
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp).testTag("app_tour"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en" to "English", "kn" to "ಕನ್ನಡ").forEach { (code, label) ->
                        Text(
                            label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (lang == code) Color.White else TextSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (lang == code) NaturalPrimary else Color(0xFFF1EFFA))
                                .clickable {
                                    lang = code
                                    context.getSharedPreferences("tiffin_prefs", Context.MODE_PRIVATE).edit().putString("lang", code).apply()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                if (!last) Text(t("tourSkip", emptyArray()), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
                    modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
            }

            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                TourPage(slides[page], onOpenHelper, t)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(slides.size) { n ->
                    val on = n == pager.currentPage
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(width = if (on) 20.dp else 7.dp, height = 7.dp)
                            .clip(CircleShape).background(if (on) NaturalPrimary else Color(0xFFD9D6E6))
                    )
                }
            }
            Button(
                onClick = { if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("app_tour_next"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary)
            ) { Text(t(if (last) "tourStart" else "tourNext", emptyArray()), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun TourPage(
    slide: TourSlide,
    onOpenHelper: () -> Unit,
    t: (String, Array<out Any?>) -> String
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Color(0xFFECE9FF)),
            contentAlignment = Alignment.Center
        ) { Icon(slide.icon, null, tint = NaturalPrimary, modifier = Modifier.size(50.dp)) }
        Text(slide.title, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(slide.body, fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
        if (slide.helper) {
            OutlinedButton(onClick = onOpenHelper, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(t("tourTryHelper", emptyArray()), fontWeight = FontWeight.Bold)
            }
        }
    }
}
