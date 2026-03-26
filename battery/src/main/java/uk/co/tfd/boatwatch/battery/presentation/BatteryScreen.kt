package uk.co.tfd.boatwatch.battery.presentation

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import uk.co.tfd.boatwatch.battery.data.BatteryState
import uk.co.tfd.boatwatch.battery.data.ConnectionStatus

private val CYAN = Color(0xFF4FC3F7)
private val CYAN_DIM = Color(0xFF2A6A8A)
private val SOC_GREEN = Color(0xFF4CAF50)
private val SOC_YELLOW = Color(0xFFFFB74D)
private val SOC_RED = Color(0xFFEF5350)
private val CELL_WARN = Color(0xFFFFB74D)
private val TEMP_WARN = Color(0xFFEF5350)
private val LABEL_COLOR = Color(0xFF6A8A9A)

@Composable
fun BatteryScreen(
    state: BatteryState,
    connectionStatus: ConnectionStatus,
    onSettings: () -> Unit = {},
) {
    val bgColor = if (state.hasError) Color(0xFF1A0505) else Color(0xFF0A0F14)
    val density = LocalDensity.current

    val socColor = when {
        state.stateOfCharge < 0 -> LABEL_COLOR
        state.stateOfCharge <= 20 -> SOC_RED
        state.stateOfCharge <= 50 -> SOC_YELLOW
        else -> CYAN
    }

    val statusText = when {
        state.hasError -> "ERROR"
        state.isCharging -> "CHARGE"
        state.isDischarging -> "DISCHARGE"
        else -> ""
    }

    val chargeFetOn = (state.fetStatus and 0x01) != 0
    val dischargeFetOn = (state.fetStatus and 0x02) != 0

    val healthText = when {
        state.hasError -> "ERROR"
        state.healthPercent >= 80 -> "HEALTHY"
        state.healthPercent >= 50 -> "FAIR"
        state.healthPercent >= 0 -> "WORN"
        else -> ""
    }

    val healthColor = when {
        state.hasError -> SOC_RED
        state.healthPercent >= 80 -> SOC_GREEN
        state.healthPercent >= 50 -> SOC_YELLOW
        else -> SOC_RED
    }

    val avgCell = if (state.cellVoltages.isNotEmpty()) state.cellVoltages.average() else 0.0

    var sizePx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged { sizePx = it.width },
    ) {
        if (sizePx > 0) {
            val centerPx = sizePx / 2f

            // Draw cell voltages along arc using Canvas
            if (state.cellVoltages.isNotEmpty()) {
                val labelTextSize = with(density) { 7.sp.toPx() }
                val valueTextSize = with(density) { 9.sp.toPx() }
                val labelRadius = centerPx - with(density) { 6.dp.toPx() } - labelTextSize * 0.5f
                val valueRadius = centerPx - with(density) { 16.dp.toPx() } - valueTextSize * 0.5f

                // Cells spread across top arc with a gap at 12 o'clock for the delta
                val totalSweep = 160f
                val gapSweep = 40f  // gap at center for delta display
                val nCells = state.cellVoltages.size
                val halfCells = nCells / 2
                val sideArc = (totalSweep - gapSweep) / 2f
                val sweepPerCell = sideArc / halfCells.coerceAtLeast(1)
                // Start angle so the arc is centered at top (270° in Android canvas coords)
                val arcStartBase = 270f - totalSweep / 2f

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvas = drawContext.canvas.nativeCanvas

                    val labelPaint = Paint().apply {
                        color = LABEL_COLOR.toArgb()
                        textSize = labelTextSize
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                        letterSpacing = 0.2f
                        typeface = Typeface.DEFAULT
                    }

                    val valuePaint = Paint().apply {
                        textSize = valueTextSize
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                        letterSpacing = 0.15f
                        typeface = Typeface.DEFAULT_BOLD
                    }

                    // Cell imbalance at 12 o'clock
                    if (state.cellVoltages.size >= 2) {
                        val imbalanceMv = (state.cellImbalance * 1000).toInt()
                        val imbalanceColor = if (imbalanceMv > 50) CELL_WARN else LABEL_COLOR
                        val imbalanceText = "${imbalanceMv}mV"
                        val imbalancePaint = Paint().apply {
                            color = imbalanceColor.toArgb()
                            textSize = valueTextSize
                            isAntiAlias = true
                            textAlign = Paint.Align.CENTER
                            letterSpacing = 0.15f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        val imbalanceWidth = imbalancePaint.measureText(imbalanceText)
                        val imbalanceSweep = Math.toDegrees((imbalanceWidth / valueRadius).toDouble()).toFloat()
                        val imbalancePath = Path().apply {
                            addArc(
                                centerPx - valueRadius,
                                centerPx - valueRadius,
                                centerPx + valueRadius,
                                centerPx + valueRadius,
                                270f - imbalanceSweep / 2f,
                                imbalanceSweep
                            )
                        }
                        canvas.drawTextOnPath(imbalanceText, imbalancePath, 0f, 0f, imbalancePaint)

                        // Tiny delta label above
                        val deltaLabelPaint = Paint().apply {
                            color = LABEL_COLOR.toArgb()
                            textSize = labelTextSize
                            isAntiAlias = true
                            textAlign = Paint.Align.CENTER
                            letterSpacing = 0.2f
                            typeface = Typeface.DEFAULT
                        }
                        val deltaLabel = "\u0394 CELL"
                        val deltaWidth = deltaLabelPaint.measureText(deltaLabel)
                        val deltaSweep = Math.toDegrees((deltaWidth / labelRadius).toDouble()).toFloat()
                        val deltaPath = Path().apply {
                            addArc(
                                centerPx - labelRadius,
                                centerPx - labelRadius,
                                centerPx + labelRadius,
                                centerPx + labelRadius,
                                270f - deltaSweep / 2f,
                                deltaSweep
                            )
                        }
                        canvas.drawTextOnPath(deltaLabel, deltaPath, 0f, 0f, deltaLabelPaint)
                    }

                    state.cellVoltages.forEachIndexed { i, v ->
                        val cellColor = if (kotlin.math.abs(v - avgCell) > 0.05) CELL_WARN else LABEL_COLOR
                        valuePaint.color = cellColor.toArgb()

                        // Place cells on left side (indices 0..halfCells-1) and right side
                        val cellCenterAngle = if (i < halfCells) {
                            // Left side of gap
                            arcStartBase + sweepPerCell * (i + 0.5f)
                        } else {
                            // Right side of gap
                            arcStartBase + sideArc + gapSweep + sweepPerCell * (i - halfCells + 0.5f)
                        }

                        // Label arc (outer) — "CELL N"
                        val labelText = "CELL ${i + 1}"
                        val labelArcWidth = labelPaint.measureText(labelText)
                        val labelSweep = Math.toDegrees((labelArcWidth / labelRadius).toDouble()).toFloat()
                        val labelPath = Path().apply {
                            addArc(
                                centerPx - labelRadius,
                                centerPx - labelRadius,
                                centerPx + labelRadius,
                                centerPx + labelRadius,
                                cellCenterAngle - labelSweep / 2f,
                                labelSweep
                            )
                        }
                        canvas.drawTextOnPath(labelText, labelPath, 0f, 0f, labelPaint)

                        // Value arc (inner) — "3.300V"
                        val valueText = String.format("%.3fV", v)
                        val valueArcWidth = valuePaint.measureText(valueText)
                        val valueSweep = Math.toDegrees((valueArcWidth / valueRadius).toDouble()).toFloat()
                        val valuePath = Path().apply {
                            addArc(
                                centerPx - valueRadius,
                                centerPx - valueRadius,
                                centerPx + valueRadius,
                                centerPx + valueRadius,
                                cellCenterAngle - valueSweep / 2f,
                                valueSweep
                            )
                        }
                        canvas.drawTextOnPath(valueText, valuePath, 0f, 0f, valuePaint)
                    }
                }
            }

            // Arc bar meters: current (outer), SOC (inner)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvas = drawContext.canvas.nativeCanvas
                val barWidth = with(density) { 8.dp.toPx() }
                val barGap = with(density) { 4.dp.toPx() }
                val outerInset = with(density) { 28.dp.toPx() }
                val startAngle = 135f
                val maxSweep = 270f
                val halfSweep = maxSweep / 2f  // 135° each side from 12 o'clock

                // Track (background) paint
                val trackPaint = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = barWidth
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    isAntiAlias = true
                    color = android.graphics.Color.argb(40, 255, 255, 255)
                }

                // Foreground paint
                val barPaint = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = barWidth
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    isAntiAlias = true
                }

                // Current (outer) — ±60A, 12 o'clock = 0, left = -60, right = +60
                // 12 o'clock = 270°. Negative draws CCW from 270°, positive draws CW from 270°.
                val currentRadius = centerPx - outerInset
                val currentRect = android.graphics.RectF(
                    centerPx - currentRadius, centerPx - currentRadius,
                    centerPx + currentRadius, centerPx + currentRadius
                )
                canvas.drawArc(currentRect, startAngle, maxSweep, false, trackPaint)
                if (!state.current.isNaN() && state.current != 0.0) {
                    val clampedCurrent = state.current.coerceIn(-60.0, 60.0).toFloat()
                    val currentColor = if (clampedCurrent < 0) SOC_RED else SOC_GREEN
                    // Flat cap at zero (start), round cap at value end
                    val currentPaint = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = barWidth
                        isAntiAlias = true
                        color = currentColor.toArgb()
                    }
                    val frac = kotlin.math.abs(clampedCurrent) / 60f
                    val sweep = halfSweep * frac
                    // Draw flat portion (all but last ~1° for the round tip)
                    val tipSweep = Math.toDegrees((barWidth * 0.5 / currentRadius).toDouble()).toFloat()
                    if (sweep > tipSweep) {
                        currentPaint.strokeCap = android.graphics.Paint.Cap.BUTT
                        if (clampedCurrent >= 0) {
                            canvas.drawArc(currentRect, 270f, sweep - tipSweep, false, currentPaint)
                        } else {
                            canvas.drawArc(currentRect, 270f - sweep + tipSweep, sweep - tipSweep, false, currentPaint)
                        }
                        // Round tip at the value end
                        currentPaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        if (clampedCurrent >= 0) {
                            canvas.drawArc(currentRect, 270f + sweep - tipSweep, tipSweep, false, currentPaint)
                        } else {
                            canvas.drawArc(currentRect, 270f - sweep, tipSweep, false, currentPaint)
                        }
                    } else {
                        currentPaint.strokeCap = android.graphics.Paint.Cap.BUTT
                        if (clampedCurrent >= 0) {
                            canvas.drawArc(currentRect, 270f, sweep, false, currentPaint)
                        } else {
                            canvas.drawArc(currentRect, 270f - sweep, sweep, false, currentPaint)
                        }
                    }
                }

                // SOC (inner) — 0-100%
                val socRadius = currentRadius - barWidth - barGap
                val socFrac = if (state.stateOfCharge >= 0)
                    (state.stateOfCharge.coerceIn(0, 100) / 100f) else 0f
                val socRect = android.graphics.RectF(
                    centerPx - socRadius, centerPx - socRadius,
                    centerPx + socRadius, centerPx + socRadius
                )
                canvas.drawArc(socRect, startAngle, maxSweep, false, trackPaint)
                if (socFrac > 0f) {
                    barPaint.color = socColor.toArgb()
                    canvas.drawArc(socRect, startAngle, maxSweep * socFrac, false, barPaint)
                }
            }

            // Connection indicator — bottom center
            val dotColor = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> SOC_GREEN
                ConnectionStatus.ERROR -> SOC_RED
                ConnectionStatus.CONNECTING -> SOC_YELLOW
                ConnectionStatus.DISCONNECTED -> Color.Gray
            }
            @OptIn(ExperimentalFoundationApi::class)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onSettings() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }

        // Center content
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status line
            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = LABEL_COLOR,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // SOC — large
            Text(
                text = if (state.stateOfCharge >= 0) "${state.stateOfCharge}%" else "--%",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = socColor,
                textAlign = TextAlign.Center,
            )

            // Voltage and current
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VOLTAGE",
                        fontSize = 8.sp,
                        color = LABEL_COLOR,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = if (!state.packVoltage.isNaN()) String.format("%.2fV", state.packVoltage) else "--",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Spacer(modifier = Modifier.width(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "CURRENT",
                        fontSize = 8.sp,
                        color = LABEL_COLOR,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = if (!state.current.isNaN()) String.format("%.1fA", state.current) else "--",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Temperatures
            if (state.temperatures.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.temperatures.forEach { t ->
                        val tempColor = if (t > 45.0) TEMP_WARN else LABEL_COLOR
                        Text(
                            text = String.format("%.0f\u00B0", t),
                            fontSize = 12.sp,
                            color = tempColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Health badge
            if (healthText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(healthColor.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\u2022 $healthText",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = healthColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // FET status indicator pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val chargeColor = if (chargeFetOn) SOC_GREEN else SOC_RED
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(chargeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "c",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = chargeColor,
                    )
                }
                val dischargeColor = if (dischargeFetOn) SOC_GREEN else SOC_RED
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(dischargeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "d",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = dischargeColor,
                    )
                }
            }
        }
    }
}
