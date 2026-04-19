package com.houwytwitch.modernsda.ui.screens.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.houwytwitch.modernsda.data.security.BiometricAuthenticator

@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
    biometricAuthenticator: BiometricAuthenticator,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state.unlocked) {
        if (state.unlocked) onUnlocked()
    }

    val activity = context as? FragmentActivity
    fun triggerBiometric() {
        if (activity == null || !state.biometricAvailable) return
        biometricAuthenticator.authenticate(
            activity = activity,
            title = "Unlock Modern SDA",
            subtitle = "Use biometric to unlock",
            negativeButtonText = "Use PIN",
            onResult = { result ->
                if (result is BiometricAuthenticator.Result.Success) {
                    viewModel.onBiometricSuccess()
                }
            },
        )
    }

    LaunchedEffect(state.biometricOffered) {
        if (state.biometricOffered) triggerBiometric()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.8f))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Enter PIN",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (state.error) "Incorrect PIN, try again"
                else "Unlock Modern SDA to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            PinDots(
                length = state.pinLength,
                entered = state.entered,
                error = state.error,
            )

            Spacer(modifier = Modifier.weight(1f))

            PinKeypad(
                biometricVisible = state.biometricOffered,
                onDigit = { digit ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.appendDigit(digit)
                },
                onBackspace = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.backspace()
                },
                onBiometric = { triggerBiometric() },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PinDots(length: Int, entered: Int, error: Boolean) {
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (error) {
            val dx = 18f
            offsetX.snapTo(0f)
            repeat(3) {
                offsetX.animateTo(-dx, tween(40, easing = LinearEasing))
                offsetX.animateTo(dx, tween(80, easing = LinearEasing))
            }
            offsetX.animateTo(0f, tween(40, easing = LinearEasing))
        }
    }
    Row(
        modifier = Modifier
            .offset(x = offsetX.value.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(length) { index ->
            val filled = index < entered
            val color = when {
                error -> MaterialTheme.colorScheme.error
                filled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(if (filled) 16.dp else 14.dp)
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
private fun PinKeypad(
    biometricVisible: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeypadRow {
            KeypadKey(label = "1", onClick = { onDigit('1') })
            KeypadKey(label = "2", onClick = { onDigit('2') })
            KeypadKey(label = "3", onClick = { onDigit('3') })
        }
        KeypadRow {
            KeypadKey(label = "4", onClick = { onDigit('4') })
            KeypadKey(label = "5", onClick = { onDigit('5') })
            KeypadKey(label = "6", onClick = { onDigit('6') })
        }
        KeypadRow {
            KeypadKey(label = "7", onClick = { onDigit('7') })
            KeypadKey(label = "8", onClick = { onDigit('8') })
            KeypadKey(label = "9", onClick = { onDigit('9') })
        }
        KeypadRow {
            if (biometricVisible) {
                KeypadKey(
                    icon = Icons.Outlined.Fingerprint,
                    onClick = onBiometric,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                KeypadSpacer()
            }
            KeypadKey(label = "0", onClick = { onDigit('0') })
            KeypadKey(
                icon = Icons.AutoMirrored.Outlined.Backspace,
                onClick = onBackspace,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeypadRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun KeypadKey(
    label: String? = null,
    icon: ImageVector? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier.size(72.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                label != null -> Text(
                    text = label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun KeypadSpacer() {
    Box(modifier = Modifier.size(72.dp))
}
