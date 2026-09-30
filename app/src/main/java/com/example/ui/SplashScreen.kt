package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Lightweight, self-contained launch animation.
 *
 * A previous animation caused issues; this one is intentionally simple and safe:
 * it uses only Compose animation primitives (no external libraries, no blocking
 * work) and drives its own entrance with an [Animatable], then reports back via
 * [onFinished] so the caller can fade it away and show the app.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // Entrance animation for the logo (scale + fade in).
    val enter = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
        )
        // Hold briefly so the animation is perceivable, then hand control back.
        kotlinx.coroutines.delay(700)
        onFinished()
    }

    val infinite = rememberInfiniteTransition(label = "splash")
    val ringRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation"
    )
    val glowPulse by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    val logoScale = 0.6f + (enter.value * 0.4f)
    val contentAlpha = enter.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Soft pulsing glow behind the logo.
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(glowPulse)
                        .alpha(0.25f * contentAlpha)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Rotating accent ring.
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(118.dp)
                        .rotate(ringRotation)
                        .alpha(contentAlpha),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )

                // App logo.
                Image(
                    painter = painterResource(id = R.drawable.ic_app_logo_1790641504573),
                    contentDescription = "App logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(92.dp)
                        .scale(logoScale)
                        .alpha(contentAlpha)
                        .clip(RoundedCornerShape(22.dp))
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "VidDownloader",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(contentAlpha)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Fast video downloads",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(contentAlpha)
            )
        }
    }
}
