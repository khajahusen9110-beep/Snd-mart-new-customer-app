package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.SndmartRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Manual fallback for applying a Sndmart Agent's code — most customers arrive via the
 * agent's shared link (handled by the website's auto-apply); this screen covers the case
 * where the link didn't carry through. Deliberately separate from Refer & Earn: different
 * program, different code, a Sndmart Agent rather than a friend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCodeScreen(
    repository: SndmartRepository,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Outlined.Work, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(32.dp))
                    Text("Were you referred by a Sndmart Agent?", fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
                    Text(
                        "If a Sndmart Agent gave you a code (not a friend's referral code), enter it here. This is a one-time, permanent link.",
                        color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (applied) {
                        Text("Agent code applied — thanks!", color = SuccessGreen, fontSize = 14.sp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.uppercase() },
                                placeholder = { Text("Enter agent code") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    applying = true
                                    error = null
                                    coroutineScope.launch {
                                        repository.applyAgentCode(code.trim())
                                            .onSuccess { applied = true; applying = false }
                                            .onFailure { error = it.message ?: "Could not apply code"; applying = false }
                                    }
                                },
                                enabled = !applying && code.isNotBlank()
                            ) {
                                if (applying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                                else Text("Apply")
                            }
                        }
                        error?.let { Text(it, color = NaturalBadgeRed, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
