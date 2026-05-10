package com.varun.animusglasses.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varun.animusglasses.AnimusUiState
import com.varun.animusglasses.ChatMessage

@Composable
fun ChatScreen(
    uiState: AnimusUiState,
    onSendMessage: (String) -> Unit,
    onStartRecording: (Activity) -> Unit,
    onStopRecording: () -> Unit,
    onTerminate: () -> Unit
) {
    val activity = LocalContext.current as Activity
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeonCyan.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).background(NeonCyan, RoundedCornerShape(50)))
                Text(
                    text = "LINK: ${uiState.personality?.objectType?.uppercase() ?: ""}",
                    color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
            }
            TextButton(onClick = onTerminate) {
                Text("✕ END", color = NeonMagenta, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.messages) { message ->
                MessageBubble(message = message, objectType = uiState.personality?.objectType ?: "")
            }
            if (uiState.isTyping) {
                item {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text("_PROCESSING...", color = NeonCyan.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }

        // Input area
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Hold to speak — uses pointerInput for press/release detection
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        Color.Transparent,
                        RoundedCornerShape(0.dp)
                    )
                    .then(
                        if (uiState.isRecording)
                            Modifier.background(NeonMagenta.copy(alpha = 0.1f))
                        else Modifier
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onStartRecording(activity)
                                tryAwaitRelease()
                                onStopRecording()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.isRecording) "● RECORDING — release to send" else "🎙 HOLD TO SPEAK",
                    color = if (uiState.isRecording) NeonMagenta else NeonMagenta.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Text input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Or type a message...", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = NeonCyan
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    shape = RoundedCornerShape(4.dp)
                )
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !uiState.isTyping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = NeonCyan,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = Color.Gray
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (inputText.isNotBlank() && !uiState.isTyping) NeonCyan else Color.Gray
                    ),
                    shape = RoundedCornerShape(0.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("[Send]", fontSize = 12.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, objectType: String) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) "YOU" else objectType.uppercase(),
            color = if (isUser) Color(0xFF4488FF).copy(alpha = 0.6f) else NeonCyan.copy(alpha = 0.6f),
            fontSize = 9.sp, letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Box(
            modifier = Modifier
                .background(
                    if (isUser) Color(0xFF1A2A4A) else Color.Black.copy(alpha = 0.5f),
                    RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White.copy(alpha = if (isUser) 1f else 0.85f),
                fontSize = if (isUser) 14.sp else 15.sp,
                fontStyle = if (isUser) FontStyle.Normal else FontStyle.Italic,
                lineHeight = 20.sp
            )
        }
    }
}
