package com.houwytwitch.modernsda.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val PIN_LENGTH = 4

@Composable
fun SetPinDialog(
    onDismiss: () -> Unit,
    onPinSet: (String) -> Unit,
) {
    var firstPin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val confirming = firstPin.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (confirming) "Confirm PIN" else "Set PIN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        mismatch -> "PINs didn't match. Try again."
                        confirming -> "Re-enter your PIN to confirm"
                        else -> "Choose a $PIN_LENGTH-digit PIN"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mismatch) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                PinDotsInline(length = PIN_LENGTH, entered = currentPin.length, error = mismatch)
                Spacer(modifier = Modifier.height(20.dp))
                MiniKeypad(
                    onDigit = { d ->
                        if (currentPin.length < PIN_LENGTH) {
                            mismatch = false
                            val next = currentPin + d
                            currentPin = next
                            if (next.length == PIN_LENGTH) {
                                if (!confirming) {
                                    firstPin = next
                                    currentPin = ""
                                } else {
                                    if (next == firstPin) {
                                        onPinSet(next)
                                    } else {
                                        mismatch = true
                                        firstPin = ""
                                        currentPin = ""
                                    }
                                }
                            }
                        }
                    },
                    onBackspace = {
                        if (currentPin.isNotEmpty()) {
                            currentPin = currentPin.dropLast(1)
                        }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PinDotsInline(length: Int, entered: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(length) { i ->
            val filled = i < entered
            val color = when {
                error -> MaterialTheme.colorScheme.error
                filled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(if (filled) 14.dp else 12.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = 1.dp,
                        color = if (filled) color else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun MiniKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MiniRow {
            MiniKey(label = "1") { onDigit('1') }
            MiniKey(label = "2") { onDigit('2') }
            MiniKey(label = "3") { onDigit('3') }
        }
        MiniRow {
            MiniKey(label = "4") { onDigit('4') }
            MiniKey(label = "5") { onDigit('5') }
            MiniKey(label = "6") { onDigit('6') }
        }
        MiniRow {
            MiniKey(label = "7") { onDigit('7') }
            MiniKey(label = "8") { onDigit('8') }
            MiniKey(label = "9") { onDigit('9') }
        }
        MiniRow {
            Box(modifier = Modifier.size(60.dp))
            MiniKey(label = "0") { onDigit('0') }
            MiniKey(icon = Icons.AutoMirrored.Outlined.Backspace, onClick = onBackspace)
        }
    }
}

@Composable
private fun MiniRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        content()
    }
}

@Composable
private fun MiniKey(
    label: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(60.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                label != null -> Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
