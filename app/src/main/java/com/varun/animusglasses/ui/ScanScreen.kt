package com.varun.animusglasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varun.animusglasses.AnimusUiState

val NeonCyan = Color(0xFF00F5FF)
val NeonMagenta = Color(0xFFFF2D78)
val DarkBg = Color(0xFF080B14)

@Composable
fun ScanScreen(
    uiState: AnimusUiState,
    onScan: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "ANIMUS", color = NeonCyan, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
            Text(text = "for Meta Glasses", color = NeonCyan.copy(alpha = 0.5f), fontSize = 12.sp, letterSpacing = 2.sp)

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = uiState.statusText, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center, letterSpacing = 1.sp)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(8.dp).background(if (uiState.isStreaming) NeonCyan else Color.Gray, shape = RoundedCornerShape(50)))
                Text(
                    text = if (uiState.isStreaming) "GLASSES CONNECTED" else "CONNECTING...",
                    color = if (uiState.isStreaming) NeonCyan else Color.Gray,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onScan,
                enabled = uiState.isStreaming && !uiState.isScanning && uiState.lastFrameJpeg != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = NeonCyan,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color.Gray
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (uiState.isStreaming && !uiState.isScanning) NeonCyan else Color.Gray),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = when {
                        uiState.isScanning -> "[ ANALYZING... ]"
                        !uiState.isStreaming -> "[ WAITING FOR GLASSES ]"
                        uiState.lastFrameJpeg == null -> "[ WAITING FOR FRAME ]"
                        else -> "[ SCAN OBJECT ]"
                    },
                    fontSize = 14.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            uiState.error?.let {
                Text(text = it, color = NeonMagenta, fontSize = 11.sp, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Point your glasses at any object, then tap Scan",
                color = Color.White.copy(alpha = 0.25f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 0.5.sp
            )
        }
    }
}
