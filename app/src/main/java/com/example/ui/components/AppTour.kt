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
import com.example.data.model.TiffinConfig
import com.example.data.model.TiffinText
import com.example.data.repository.TiffinRepository
import com.example.ui.theme.NaturalPrimary
import com.example.ui.theme.TextSecondary
import com.example.util.rupees
import kotlinx.coroutines.launch

/**
 * First-visit tour: a few swipeable cards on what Sndmart offers, with Tiffin Pickup (when switched on for the
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

private class TourSlide(val icon: ImageVector, val title: String, val body: String, val tiffin: String? = null)

/** Mounted once at the app root. [homeVisible]: the first-visit tour waits until the customer is on Home. */
@Composable
fun AppTourHost(cityId: String?, homeVisible: Boolean, onOpenTiffin: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<TiffinConfig?>(null) }
    var lastReplay by remember { mutableIntStateOf(AppTourState.replayTick) }

    LaunchedEffect(cityId, homeVisible, AppTourState.replayTick) {
        if (cityId == null || open) return@LaunchedEffect
        val replay = AppTourState.replayTick != lastReplay
        if (!replay && (!homeVisible || AppTourState.seen(context))) return@LaunchedEffect
        lastReplay = AppTourState.replayTick
        TiffinText.ensureLoaded(context)
        val cfg = try { TiffinRepository.getConfig(cityId) } catch (e: Exception) { null }
        config = cfg?.takeIf { it.enabled }
        open = true
    }
    if (!open) return

    val close = { AppTourState.markSeen(context); open = false }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        TourContent(config = config, onClose = close, onOpenTiffin = { close(); onOpenTiffin() })
    }
}

@Composable
private fun TourContent(config: TiffinConfig?, onClose: () -> Unit, onOpenTiffin: () -> Unit) {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(tourLang(context)) }
    val t = { key: String, args: Array<out Any?> -> TiffinText.t(lang, key, *args) }
    val slides = buildList {
        add(TourSlide(Icons.Filled.Store, t("tourWelcomeT", emptyArray()), t("tourWelcomeB", emptyArray())))
        if (config != null) {
            add(TourSlide(Icons.Filled.Store, t("tourTiffinT", emptyArray()), t("tourTiffinB", emptyArray()), "intro"))
            val trial = config.trialEnabled
            add(
                TourSlide(
                    Icons.Filled.Store,
                    if (trial) t("tourTrialT", arrayOf(config.trialDays, rupees(config.trialPrice))) else t("title", emptyArray()),
                    if (trial) t("tourTrialB", emptyArray()) else t("heroSub", emptyArray()),
                    "trial"
                )
            )
        }
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
                TourPage(slides[page], config, lang, onOpenTiffin, t)
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
    config: TiffinConfig?,
    lang: String,
    onOpenTiffin: () -> Unit,
    t: (String, Array<out Any?>) -> String
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (slide.tiffin) {
            "intro" -> {
                val fallback = painterResource(R.drawable.tiffin_hero)
                AsyncImage(
                    model = config?.heroImageUrl ?: R.drawable.tiffin_hero, contentDescription = null,
                    placeholder = fallback, error = fallback, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1210f / 748f).clip(RoundedCornerShape(18.dp))
                )
            }
            "trial" -> TourSteps(t)
            else -> Box(
                Modifier.size(104.dp).clip(CircleShape).background(Color(0xFFECE9FF)),
                contentAlignment = Alignment.Center
            ) { Icon(slide.icon, null, tint = NaturalPrimary, modifier = Modifier.size(50.dp)) }
        }
        Text(slide.title, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(slide.body, fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
        if (slide.tiffin == "trial") {
            OutlinedButton(onClick = onOpenTiffin, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(t("tourTryTiffin", emptyArray()), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TourSteps(t: (String, Array<out Any?>) -> String) {
    val keys = listOf("hw1", "hw2", "hw3", "hw4")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, k ->
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF5F3FF)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(NaturalPrimary), contentAlignment = Alignment.Center) {
                            Text("${row * 2 + col + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(t(k, emptyArray()), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
