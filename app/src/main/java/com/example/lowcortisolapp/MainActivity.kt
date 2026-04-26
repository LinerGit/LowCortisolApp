package com.example.lowcortisolapp

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lowcortisolapp.ui.theme.LowCortisolAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DefaultMinutes = 30
private const val DefaultSeconds = 0
private const val DefaultAlarmMinutes = 5
private const val DefaultAlarmSeconds = 0
private const val PrefsName = "low_cortisol_settings"

private enum class TimerPhase {
    Idle,
    Running,
    Paused,
    Alarm
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

        setContent {
            var darkTheme by rememberSaveable {
                mutableStateOf(prefs.getBoolean("darkTheme", false))
            }

            LowCortisolAppTheme(
                darkTheme = darkTheme,
                dynamicColor = false
            ) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TimerScreen(
                        darkTheme = darkTheme,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            prefs.edit().putBoolean("darkTheme", darkTheme).apply()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun TimerScreen(
    darkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) }

    var timerMinutes by rememberSaveable { mutableIntStateOf(prefs.getInt("timerMinutes", DefaultMinutes)) }
    var timerSeconds by rememberSaveable { mutableIntStateOf(prefs.getInt("timerSeconds", DefaultSeconds)) }
    var rollbackMinutes by rememberSaveable {
        mutableIntStateOf(prefs.getInt("rollbackMinutes", DefaultAlarmMinutes))
    }
    var rollbackSeconds by rememberSaveable {
        mutableIntStateOf(prefs.getInt("rollbackSeconds", DefaultAlarmSeconds))
    }
    var audioUriText by rememberSaveable { mutableStateOf(prefs.getString("audioUri", null)) }
    var durationMillis by rememberSaveable { mutableLongStateOf(toMillis(timerMinutes, timerSeconds)) }
    var rollbackDurationMillis by rememberSaveable {
        mutableLongStateOf(toMillis(rollbackMinutes, rollbackSeconds))
    }
    var elapsedMillis by rememberSaveable { mutableLongStateOf(0L) }
    var rollbackElapsedMillis by rememberSaveable { mutableLongStateOf(0L) }
    var phase by rememberSaveable { mutableStateOf(TimerPhase.Idle) }
    var runStartedAt by remember { mutableLongStateOf(0L) }
    var elapsedBeforeRun by rememberSaveable { mutableLongStateOf(0L) }
    var rollbackStartedAt by remember { mutableLongStateOf(0L) }
    var rollbackElapsedBeforeRun by rememberSaveable { mutableLongStateOf(0L) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        audioUriText = uri.toString()
        prefs.edit().putString("audioUri", uri.toString()).apply()
    }

    LaunchedEffect(timerMinutes, timerSeconds) {
        prefs.edit()
            .putInt("timerMinutes", timerMinutes)
            .putInt("timerSeconds", timerSeconds)
            .apply()

        if (phase == TimerPhase.Idle || phase == TimerPhase.Paused) {
            durationMillis = toMillis(timerMinutes, timerSeconds)
            elapsedMillis = elapsedMillis.coerceAtMost(durationMillis)
        }
    }

    LaunchedEffect(rollbackMinutes, rollbackSeconds) {
        prefs.edit()
            .putInt("rollbackMinutes", rollbackMinutes)
            .putInt("rollbackSeconds", rollbackSeconds)
            .apply()

        if (phase != TimerPhase.Alarm) {
            rollbackDurationMillis = toMillis(rollbackMinutes, rollbackSeconds)
            rollbackElapsedMillis = rollbackElapsedMillis.coerceAtMost(rollbackDurationMillis)
        }
    }

    LaunchedEffect(phase, durationMillis) {
        if (phase != TimerPhase.Running) return@LaunchedEffect

        runStartedAt = SystemClock.elapsedRealtime()
        elapsedBeforeRun = elapsedMillis

        while (phase == TimerPhase.Running && elapsedMillis < durationMillis) {
            val now = SystemClock.elapsedRealtime()
            elapsedMillis = (elapsedBeforeRun + now - runStartedAt).coerceAtMost(durationMillis)
            delay(100L)
        }

        if (elapsedMillis >= durationMillis) {
            rollbackElapsedMillis = 0L
            rollbackElapsedBeforeRun = 0L
            phase = TimerPhase.Alarm
        }
    }

    LaunchedEffect(phase, rollbackDurationMillis) {
        if (phase != TimerPhase.Alarm) return@LaunchedEffect

        rollbackStartedAt = SystemClock.elapsedRealtime()
        rollbackElapsedBeforeRun = rollbackElapsedMillis

        while (phase == TimerPhase.Alarm && rollbackElapsedMillis < rollbackDurationMillis) {
            val now = SystemClock.elapsedRealtime()
            rollbackElapsedMillis = (rollbackElapsedBeforeRun + now - rollbackStartedAt)
                .coerceAtMost(rollbackDurationMillis)
            delay(100L)
        }

        if (rollbackElapsedMillis >= rollbackDurationMillis) {
            phase = TimerPhase.Idle
            elapsedMillis = 0L
            rollbackElapsedMillis = 0L
        }
    }

    AlarmSound(
        audioUriText = audioUriText,
        playing = phase == TimerPhase.Alarm
    )

    val timerProgress = if (durationMillis > 0L) {
        elapsedMillis.toFloat() / durationMillis.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)

    val rollbackProgress = if (rollbackDurationMillis > 0L) {
        rollbackElapsedMillis.toFloat() / rollbackDurationMillis.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)

    val gaugeProgress = when (phase) {
        TimerPhase.Alarm -> 1f - rollbackProgress
        else -> timerProgress
    }.coerceIn(0f, 1f)

    val displayMillis = when (phase) {
        TimerPhase.Alarm -> (rollbackDurationMillis - rollbackElapsedMillis).coerceAtLeast(0L)
        else -> (durationMillis - elapsedMillis).coerceAtLeast(0L)
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            ThemeButton(
                darkTheme = darkTheme,
                onClick = onToggleTheme,
                modifier = Modifier.align(Alignment.TopEnd)
            )

            if (phase == TimerPhase.Idle) {
                SetupScreen(
                    timerMinutes = timerMinutes,
                    timerSeconds = timerSeconds,
                    rollbackMinutes = rollbackMinutes,
                    rollbackSeconds = rollbackSeconds,
                    hasAudio = audioUriText != null,
                    onTimerMinutesChange = { timerMinutes = it },
                    onTimerSecondsChange = { timerSeconds = it },
                    onRollbackMinutesChange = { rollbackMinutes = it },
                    onRollbackSecondsChange = { rollbackSeconds = it },
                    onPickAudio = { audioPicker.launch(arrayOf("audio/mpeg", "audio/*")) },
                    onStart = {
                        durationMillis = toMillis(timerMinutes, timerSeconds)
                        rollbackDurationMillis = toMillis(rollbackMinutes, rollbackSeconds)
                        elapsedMillis = 0L
                        rollbackElapsedMillis = 0L
                        phase = TimerPhase.Running
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                RunningScreen(
                    progress = gaugeProgress,
                    displayMillis = displayMillis,
                    isRollback = phase == TimerPhase.Alarm,
                    phase = phase,
                    onPause = { phase = TimerPhase.Paused },
                    onResume = { phase = TimerPhase.Running },
                    onStop = {
                        phase = TimerPhase.Idle
                        elapsedMillis = 0L
                        rollbackElapsedMillis = 0L
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    timerMinutes: Int,
    timerSeconds: Int,
    rollbackMinutes: Int,
    rollbackSeconds: Int,
    hasAudio: Boolean,
    onTimerMinutesChange: (Int) -> Unit,
    onTimerSecondsChange: (Int) -> Unit,
    onRollbackMinutesChange: (Int) -> Unit,
    onRollbackSecondsChange: (Int) -> Unit,
    onPickAudio: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(top = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Таймер",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(22.dp))
        TimeWheelRow(
            title = "Время",
            minutes = timerMinutes,
            seconds = timerSeconds,
            onMinutesChange = onTimerMinutesChange,
            onSecondsChange = onTimerSecondsChange
        )
        Spacer(modifier = Modifier.height(26.dp))
        TimeWheelRow(
            title = "Откат",
            minutes = rollbackMinutes,
            seconds = rollbackSeconds,
            onMinutesChange = onRollbackMinutesChange,
            onSecondsChange = onRollbackSecondsChange
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedButton(
            onClick = onPickAudio,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(if (hasAudio) "Аудио выбрано" else "Загрузить аудио")
        }
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7DFF))
        ) {
            Text("Старт")
        }
    }
}

@Composable
private fun RunningScreen(
    progress: Float,
    displayMillis: Long,
    isRollback: Boolean,
    phase: TimerPhase,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(top = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CortisolGauge(
            progress = progress,
            displayMillis = displayMillis,
            isRollback = isRollback,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = if (phase == TimerPhase.Paused) onResume else onPause,
                enabled = phase != TimerPhase.Alarm,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B7280))
            ) {
                Text(if (phase == TimerPhase.Paused) "Старт" else "Пауза")
            }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onStop,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
            ) {
                Text("Стоп")
            }
        }
    }
}

@Composable
private fun TimeWheelRow(
    title: String,
    minutes: Int,
    seconds: Int,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$title: ${"%02d:%02d".format(minutes, seconds)}",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WheelPicker(
                value = minutes,
                range = 0..180,
                suffix = "мин",
                onValueChange = onMinutesChange
            )
            WheelPicker(
                value = seconds,
                range = 0..59,
                suffix = "сек",
                onValueChange = onSecondsChange
            )
        }
    }
}

@Composable
private fun WheelPicker(
    value: Int,
    range: IntRange,
    suffix: String,
    onValueChange: (Int) -> Unit
) {
    val values = remember(range.first, range.last) { range.toList() }
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val textColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(value) {
        val index = values.indexOf(value)
        if (index >= 0 && index != listState.firstVisibleItemIndex) {
            listState.animateScrollToItem(index)
        }
    }

    LaunchedEffect(listState, values) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { index ->
                values.getOrNull(index)?.let(onValueChange)
            }
    }

    Box(
        modifier = Modifier
            .width(112.dp)
            .height(172.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(48.dp)
                .background(highlightColor)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 62.dp)
        ) {
            items(values.size) { index ->
                val item = values[index]
                val selected = item == value
                Text(
                    text = "%02d $suffix".format(item),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { onValueChange(item) },
                    color = if (selected) MaterialTheme.colorScheme.primary else textColor.copy(alpha = 0.62f),
                    fontSize = if (selected) 24.sp else 18.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ThemeButton(
    darkTheme: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(if (darkTheme) "Светлая" else "Темная")
    }
}

@Composable
private fun CortisolGauge(
    progress: Float,
    displayMillis: Long,
    isRollback: Boolean,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier.height(310.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 34.dp.toPx()
            val arcWidth = size.width * 0.82f
            val arcHeight = arcWidth
            val left = (size.width - arcWidth) / 2f
            val top = 52.dp.toPx()
            val arcSize = Size(arcWidth, arcHeight)
            val gap = 3f
            val segmentSweep = (180f - gap * 5f) / 6f
            val segmentColors = listOf(
                Color(0xFF56C76D),
                Color(0xFFA9C936),
                Color(0xFFF2E644),
                Color(0xFFFF9E32),
                Color(0xFFFF584F),
                Color(0xFFF72F52)
            )

            segmentColors.forEachIndexed { index, color ->
                drawArc(
                    color = color,
                    startAngle = 180f + index * (segmentSweep + gap),
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    topLeft = Offset(left, top),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
            }

            val center = Offset(size.width / 2f, top + arcHeight / 2f)
            val radius = arcWidth / 2f
            val angle = Math.toRadians((180f + 180f * progress).toDouble())
            val needleEnd = Offset(
                x = center.x + cos(angle).toFloat() * radius * 0.86f,
                y = center.y + sin(angle).toFloat() * radius * 0.86f
            )

            drawLine(
                color = Color(0xFF24345C),
                start = center,
                end = needleEnd,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(color = Color(0xFF2B4A82), radius = 11.dp.toPx(), center = center)
            drawCircle(color = Color(0xFFE9EEF7), radius = 4.dp.toPx(), center = center)

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                )
                textSize = 13.sp.toPx()
            }
            val labelRadius = radius * 1.08f
            drawContext.canvas.nativeCanvas.apply {
                drawText(
                    "LOW",
                    center.x + cos(PI).toFloat() * labelRadius,
                    center.y + sin(PI).toFloat() * labelRadius + 4.dp.toPx(),
                    paint
                )
                drawText(
                    "MEDIUM",
                    center.x,
                    center.y - labelRadius + 2.dp.toPx(),
                    paint
                )
                drawText(
                    "HIGH",
                    center.x + cos(0.0).toFloat() * labelRadius,
                    center.y + sin(0.0).toFloat() * labelRadius + 4.dp.toPx(),
                    paint
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 166.dp)
        ) {
            Text(
                text = formatTime(displayMillis),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isRollback) "ОТКАТ" else "CORTISOL",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "${(progress * 100).roundToInt()}%",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AlarmSound(
    audioUriText: String?,
    playing: Boolean
) {
    val context = LocalContext.current

    DisposableEffect(audioUriText, playing) {
        var player: MediaPlayer? = null

        if (playing && audioUriText != null) {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, Uri.parse(audioUriText))
                isLooping = true
                prepare()
                start()
            }
        }

        onDispose {
            player?.runCatching { stop() }
            player?.release()
        }
    }
}

private fun toMillis(minutes: Int, seconds: Int): Long {
    val totalSeconds = (minutes.coerceAtLeast(0) * 60L + seconds.coerceIn(0, 59))
        .coerceAtLeast(1L)
    return totalSeconds * 1000L
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis + 999L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt()
    )
}

@Preview(showBackground = true)
@Composable
fun TimerScreenPreview() {
    LowCortisolAppTheme(dynamicColor = false) {
        TimerScreen()
    }
}
