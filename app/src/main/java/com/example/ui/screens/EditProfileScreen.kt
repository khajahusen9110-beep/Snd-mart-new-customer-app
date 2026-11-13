package com.example.ui.screens

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Profile
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.theme.*
import com.example.util.PhoneUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val userId = sessionManager.userId.collectAsState().value
    val currentName = sessionManager.userName.collectAsState().value
    val currentPhone = sessionManager.userPhone.collectAsState().value

    var name by remember { mutableStateOf(currentName ?: "") }
    var phone by remember { mutableStateOf(currentPhone ?: "") }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppCardShape,
                colors = appCardColors(),
                elevation = appCardElevation(),
                border = AppCardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Personal Details", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        shape = AppFieldShape,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("edit_name_field")
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        shape = AppFieldShape,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("edit_phone_field")
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (userId.isNullOrBlank()) return@Button
                    saving = true
                    coroutineScope.launch {
                        val formattedPhone = phone.trim().ifBlank { null }?.let { PhoneUtils.toE164(it) }
                        val res = repository.updateProfile(userId, name.trim().ifBlank { null }, formattedPhone)
                        saving = false
                        if (res.isSuccess) {
                            sessionManager.updateProfileInfo(
                                Profile(id = userId, fullName = name.trim().ifBlank { null }, phone = formattedPhone)
                            )
                            snackbarHostState.showSnackbar("Profile updated")
                            onBack()
                        } else {
                            snackbarHostState.showSnackbar("Failed: ${res.exceptionOrNull()?.message}")
                        }
                    }
                },
                enabled = !saving && (name.isNotBlank() || phone.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_profile_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text("Save Changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
