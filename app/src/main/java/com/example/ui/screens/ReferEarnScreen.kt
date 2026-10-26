package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.SndmartRepository
import com.example.ui.theme.*
import com.example.util.rupees
import kotlinx.coroutines.launch

private const val SHARE_URL = "https://sndmart.in"
private const val REFERRER_REWARD = 50
private const val FRIEND_REWARD = 30

private fun shareMessage(code: String) =
    "Sndmart se order karo aur ₹$FRIEND_REWARD paao! Mera referral code use karo: $code\n$SHARE_URL"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferEarnScreen(
    repository: SndmartRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var code by remember { mutableStateOf<String?>(null) }
    var totalReferred by remember { mutableStateOf(0) }
    var totalEarned by remember { mutableStateOf(0.0) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    var applyCode by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val res = repository.getMyReferralStats()
        res.onSuccess { s ->
            code = s.referralCode
            totalReferred = s.totalReferred
            totalEarned = s.totalEarned
            isLoading = false
        }.onFailure {
            error = it.message ?: "Failed to load referral info"
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Refer & Earn") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NaturalPrimary)
                }
            } else if (error != null) {
                Text(error!!, color = NaturalBadgeRed)
            } else {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(36.dp))
                        Text("Refer friends, earn wallet cash", fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
                        Text(
                            "Share your code — your friend gets ₹$FRIEND_REWARD on their first order, and you get ₹$REFERRER_REWARD in your Sndmart wallet.",
                            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                    }
                }

                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Your referral code", color = TextSecondary, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(code ?: "••••••", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 3.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    code?.let {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Referral code", it))
                                        copied = true
                                    }
                                },
                                enabled = code != null,
                                modifier = Modifier.testTag("refer_copy_button")
                            ) {
                                Icon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (copied) "Copied" else "Copy")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    code?.let {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareMessage(it))
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share your referral code"))
                                    }
                                },
                                enabled = code != null,
                                modifier = Modifier.weight(1f).testTag("refer_share_button")
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                            OutlinedButton(
                                onClick = {
                                    code?.let {
                                        val encoded = Uri.encode(shareMessage(it))
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded")))
                                    }
                                },
                                enabled = code != null,
                                modifier = Modifier.weight(1f).testTag("refer_whatsapp_button")
                            ) {
                                Icon(Icons.Outlined.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("WhatsApp")
                            }
                        }
                    }
                }

                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.People, contentDescription = null, tint = NaturalPrimary)
                            Text("$totalReferred", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Friends referred", color = TextSecondary, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = NaturalPrimary)
                            Text(rupees(totalEarned), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Total earned", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                if (totalReferred == 0) {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Got a friend's code?", color = TextSecondary, fontSize = 12.sp)
                            if (applied) {
                                Text("Code applied — place your first order to unlock ₹$FRIEND_REWARD bonus.", color = SuccessGreen, fontSize = 13.sp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = applyCode,
                                        onValueChange = { applyCode = it.uppercase() },
                                        placeholder = { Text("Enter referral code") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            applying = true
                                            applyError = null
                                            coroutineScope.launch {
                                                repository.applyReferralCode(applyCode.trim())
                                                    .onSuccess {
                                                        applied = true
                                                        applying = false
                                                    }
                                                    .onFailure {
                                                        applyError = it.message ?: "Could not apply code"
                                                        applying = false
                                                    }
                                            }
                                        },
                                        enabled = !applying && applyCode.isNotBlank(),
                                        modifier = Modifier.testTag("refer_apply_button")
                                    ) {
                                        if (applying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                        else Text("Apply")
                                    }
                                }
                                applyError?.let { Text(it, color = NaturalBadgeRed, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How it works", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("1. Share your code with a friend who hasn't ordered on Sndmart yet.", color = TextSecondary, fontSize = 13.sp)
                        Text("2. They enter it on this page before placing their first order.", color = TextSecondary, fontSize = 13.sp)
                        Text("3. When their first order (₹99+) is delivered, you both get wallet cash.", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
