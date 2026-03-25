package uk.co.tfd.boatwatch.autopilot.presentation

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import uk.co.tfd.boatwatch.autopilot.protocol.AutopilotState
import uk.co.tfd.boatwatch.autopilot.protocol.ConnectionState
import uk.co.tfd.boatwatch.autopilot.protocol.PilotMode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val BTN = 46.dp
private val PILL_WIDTH = 92.dp

@Composable
fun MainScreen(
    state: AutopilotState,
    connectionState: ConnectionState,
    onAdjust: (Double) -> Unit,
    onStandby: () -> Unit,
    onCycleMode: () -> Unit,
    onSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    fun haptic() {
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    val activeModeLabel = state.pilotMode.displayName

    val targetText = when (state.pilotMode) {
        PilotMode.STANDBY -> "---"
        PilotMode.COMPASS -> String.format("%.0f\u00B0", state.targetHeading)
        PilotMode.WIND_AWA, PilotMode.WIND_TWA -> {
            val side = if (state.targetWindAngle >= 0) "S" else "P"
            val angle = Math.abs(state.targetWindAngle)
            String.format("%s %.0f\u00B0", side, angle)
        }
    }

    val isDisconnected = connectionState != ConnectionState.CONNECTED
    val bgColor = if (isDisconnected) Color(0xFF1A0A0A) else Color.Black

    val primaryColor = MaterialTheme.colors.primary
    val errorColor = MaterialTheme.colors.error
    val dark = Color(0xFF2A2A2A)

    var sizePx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged { sizePx = it.width }
            .onRotaryScrollEvent { event ->
                val clicks = (event.verticalScrollPixels / 30f).roundToInt()
                if (clicks != 0) {
                    haptic()
                    onAdjust(clicks.toDouble())
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        if (sizePx > 0) {
            val btnPx = with(density) { BTN.toPx() }
            val pillWPx = with(density) { PILL_WIDTH.toPx() }
            val centerPx = sizePx / 2f
            val radiusPx = centerPx - btnPx / 2f - with(density) { 4.dp.toPx() }

            fun ringPos(angleDeg: Float): Pair<Float, Float> {
                val rad = Math.toRadians(angleDeg.toDouble())
                return Pair(
                    centerPx + radiusPx * sin(rad).toFloat(),
                    centerPx - radiusPx * cos(rad).toFloat(),
                )
            }

            // Connection indicator at top
            val connDotColor = when (connectionState) {
                ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                ConnectionState.ERROR -> errorColor
                ConnectionState.CONNECTING -> MaterialTheme.colors.secondary
                ConnectionState.DISCONNECTED -> Color.Gray
            }
            @OptIn(ExperimentalFoundationApi::class)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { haptic(); onSettings() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(connDotColor)
                )
            }

            // Circle buttons
            data class CBtn(val label: String, val angle: Float, val bg: Color, val fg: Color, val onClick: () -> Unit)
            val circleButtons = listOf(
                CBtn("+1", 45f, dark, Color.White) { haptic(); onAdjust(1.0) },
                CBtn("+10", 90f, dark, Color.White) { haptic(); onAdjust(10.0) },
                CBtn("-10", 270f, dark, Color.White) { haptic(); onAdjust(-10.0) },
                CBtn("-1", 315f, dark, Color.White) { haptic(); onAdjust(-1.0) },
            )

            circleButtons.forEach { btn ->
                val (cx, cy) = ringPos(btn.angle)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cx - btnPx / 2f).roundToInt(), (cy - btnPx / 2f).roundToInt()) }
                        .size(BTN)
                        .clip(CircleShape)
                        .background(btn.bg)
                        .clickable { btn.onClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = btn.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = btn.fg,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }

            // STBY pill at 225 degrees
            val (stbyOcx, stbyOcy) = ringPos(225f)
            val stbyX = stbyOcx - btnPx / 2f
            val stbyY = stbyOcy - btnPx / 2f
            val stbyBg = if (state.pilotMode == PilotMode.STANDBY) errorColor else Color(0xFF4A1A1A)

            Box(
                modifier = Modifier
                    .offset { IntOffset(stbyX.roundToInt(), stbyY.roundToInt()) }
                    .width(PILL_WIDTH)
                    .height(BTN)
                    .clip(RoundedCornerShape(50))
                    .background(stbyBg)
                    .clickable { haptic(); onStandby() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "STBY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }

            // Auto pill at 135 degrees
            val (autoOcx, autoOcy) = ringPos(135f)
            val autoX = autoOcx + btnPx / 2f - pillWPx
            val autoY = autoOcy - btnPx / 2f
            val autoBg = if (state.pilotMode != PilotMode.STANDBY) primaryColor else Color(0xFF1A3A4A)
            val autoFg = if (state.pilotMode != PilotMode.STANDBY) Color.Black else Color.White

            Box(
                modifier = Modifier
                    .offset { IntOffset(autoX.roundToInt(), autoY.roundToInt()) }
                    .width(PILL_WIDTH)
                    .height(BTN)
                    .clip(RoundedCornerShape(50))
                    .background(autoBg)
                    .clickable { haptic(); onCycleMode() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Auto",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = autoFg,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Center: mode + target
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = activeModeLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = when (state.pilotMode) {
                    PilotMode.STANDBY -> MaterialTheme.colors.error
                    else -> MaterialTheme.colors.primary
                },
            )

            Text(
                text = targetText,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}
