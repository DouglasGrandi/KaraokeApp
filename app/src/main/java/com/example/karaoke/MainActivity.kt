package com.example.karaoke

import android.Manifest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var recorder: MediaRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KaraokeAppUI() }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        stopRecording()
    }

    @Composable
    fun KaraokeAppUI() {
        val context = this
        var audioUri by remember { mutableStateOf<Uri?>(null) }
        var lrcUri by remember { mutableStateOf<Uri?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var positionMs by remember { mutableStateOf(0L) }
        var durationMs by remember { mutableStateOf(0L) }
        var pollingJob by remember { mutableStateOf<Job?>(null) }

        var lines by remember { mutableStateOf(listOf<LrcLine>()) }

        val pickAudio = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                audioUri = it
                initPlayerIfNeeded()
                player?.setMediaItem(MediaItem.fromUri(it))
                player?.prepare()
                durationMs = player?.duration ?: 0L
            }
        }

        val pickLrc = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                lrcUri = it
                lines = readLrc(uri)
            }
        }

        val requestMic = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startRecording() else Unit
        }

        fun togglePlay() {
            initPlayerIfNeeded()
            val p = player ?: return
            if (p.isPlaying) {
                p.pause()
                isPlaying = false
                pollingJob?.cancel()
            } else {
                p.play()
                isPlaying = true
                pollingJob = lifecycleScope.launch {
                    while (true) {
                        positionMs = p.currentPosition
                        durationMs = p.duration.coerceAtLeast(0)
                        delay(50)
                    }
                }
            }
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text("Karaoke") })
        }) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickAudio.launch(arrayOf("audio/*"))
                    }) { Text("Escolher música") }

                    Button(onClick = {
                        pickLrc.launch(arrayOf("text/*"))
                    }) { Text("Escolher letra (.lrc)") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { togglePlay() },
                        enabled = audioUri != null
                    ) { Text(if (isPlaying) "Pausar" else "Tocar") }

                    Button(onClick = {
                        if (recorder == null) {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            stopRecording()
                        }
                    }, enabled = audioUri != null) {
                        Text(if (recorder == null) "Gravar voz" else "Parar gravação")
                    }
                }

                LinearProgressIndicator(
                    progress = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(timeLabel(positionMs) + " / " + timeLabel(durationMs))

                LyricsList(lines = lines, positionMs = positionMs)
            }
        }
    }

    private fun initPlayerIfNeeded() {
        if (player == null) {
            player = ExoPlayer.Builder(this).build().apply {
                addListener(object : Player.Listener {})
            }
        }
    }

    private fun readLrc(uri: Uri): List<LrcLine> {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val all = reader.readText()
                parseLrc(all)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun startRecording() {
        val outFile = getExternalFilesDir(null)?.resolve("vocal_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        recorder = rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outFile?.absolutePath)
            prepare()
            start()
        }
    }

    private fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (_: Exception) {}
        recorder = null
    }
}

// --- Helpers & UI ---

data class LrcLine(val timeMs: Long, val text: String)

fun parseLrc(text: String): List<LrcLine> {
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]\\s*(.*)")
    return text.lineSequence().mapNotNull { line ->
        val m = regex.find(line) ?: return@mapNotNull null
        val (min, sec, ms, lyric) = m.destructured
        val totalMs = min.toLong() * 60_000 + sec.toLong() * 1_000 + (ms.ifEmpty { "0" }.padEnd(3, '0').take(3).toLong())
        LrcLine(totalMs, lyric.trim())
    }.sortedBy { it.timeMs }.toList()
}

@Composable
fun LyricsList(lines: List<LrcLine>, positionMs: Long) {
    val currentIndex = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(lines) { idx, item ->
            val active = idx == currentIndex
            Text(
                text = item.text,
                style = if (active) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                else MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

private fun timeLabel(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val df = DecimalFormat("00")
    return "$m:${df.format(s)}"
}
