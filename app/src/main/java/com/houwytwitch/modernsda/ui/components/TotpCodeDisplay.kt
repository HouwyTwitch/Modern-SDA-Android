package com.houwytwitch.modernsda.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Displays the current Steam TOTP code with a countdown progress bar.
 * Color transitions: green (>10s), orange (5-10s), red (<5s) — matching the web app.
 */
@Composable
fun TotpCodeDisplay(
    code: String,
    timeRemaining: Int,
    progressFraction: Float,
    onCopyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressColor = when {
        timeRemaining > 10 -> Color(0xFF4CAF50) // green
        timeRemaining > 5 -> Color(0xFFF5A623)  // orange
        else -> Color(0xFFF44336)               // red
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Authentication Code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onCopyClick)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                AnimatedContent(
                    targetState = code,
                    transitionSpec = {
                        (slideInVertically { -it } + fadeIn()) togetherWith
                            (slideOutVertically { it } + fadeOut())
                    },
                    label = "totpCode",
                ) { targetCode ->
                    Text(
                        text = targetCode,
                        fontSize = 42.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        letterSpacing = 8.sp,
                    )
                }

                Box(modifier = Modifier.size(4.dp))

                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${timeRemaining}s remaining",
                style = MaterialTheme.typography.labelSmall,
                color = progressColor,
            )
        }
    }
}
