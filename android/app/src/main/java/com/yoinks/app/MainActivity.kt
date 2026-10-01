package com.yoinks.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        YoinksAndroidApp(context = applicationContext)
      }
    }
  }
}

private data class ProbeChoice(
  val label: String,
  val args: List<String>,
)

private data class ProbeResult(
  val title: String,
  val uploader: String?,
  val durationSeconds: Double?,
  val choices: List<ProbeChoice>,
  val ffmpegAvailable: Boolean,
)

private data class ProgressState(
  val downloadedBytes: Long,
  val totalBytes: Long?,
  val speedBytesPerSecond: Double?,
  val etaSeconds: Long?,
)

private sealed interface ScreenState {
  data object Idle : ScreenState
  data object Probing : ScreenState
  data class Picking(val result: ProbeResult) : ScreenState
  data class Downloading(val choice: ProbeChoice, val progress: ProgressState?) : ScreenState
  data class Done(val path: String) : ScreenState
  data class Error(val message: String) : ScreenState
}

@Composable
private fun YoinksAndroidApp(context: Context) {
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current

  var url by rememberSaveable { mutableStateOf("") }
  var state by remember { mutableStateOf<ScreenState>(ScreenState.Idle) }
  var status by remember { mutableStateOf("Ready") }
  val logs = remember { mutableStateListOf<String>() }
  var runningJob by remember { mutableStateOf<Job?>(null) }

  fun cancelCurrentRun(reason: String = "Cancelled") {
    runningJob?.cancel()
    AndroidYtdlp.cancelActive()
    runningJob = null
    status = reason
    if (state is ScreenState.Probing || state is ScreenState.Downloading) {
      state = ScreenState.Idle
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) {
        cancelCurrentRun("Paused in background")
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      cancelCurrentRun()
    }
  }

  Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("yoinks for Android", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text("yoink any video. paste. yoink. done.")

      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Video URL") },
        singleLine = true,
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          enabled = runningJob == null,
          onClick = {
            val target = url.trim()
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
              state = ScreenState.Error("Paste a full URL that starts with http:// or https://")
              return@Button
            }
            if (!context.hasUsableNetwork()) {
              state = ScreenState.Error("No internet connection available.")
              return@Button
            }
            if (!context.hasDownloadSpace()) {
              state = ScreenState.Error("Low free storage. Free some space and try again.")
              return@Button
            }
            logs.clear()
            status = "Fetching formats..."
            state = ScreenState.Probing
            runningJob = scope.launch {
              try {
                val result = withContext(Dispatchers.IO) {
                  AndroidYtdlp.probe(context, target) { logs.add(it) }
                }
                state = ScreenState.Picking(result)
                status = if (result.ffmpegAvailable) {
                  "Pick a format"
                } else {
                  "Pick a format (ffmpeg not found: using phone-safe formats)"
                }
              } catch (ce: CancellationException) {
                throw ce
              } catch (e: Exception) {
                state = ScreenState.Error(e.message ?: "Failed to fetch formats")
              } finally {
                runningJob = null
              }
            }
          },
        ) {
          Text("Fetch formats")
        }

        OutlinedButton(
          enabled = runningJob != null,
          onClick = { cancelCurrentRun() },
        ) {
          Text("Cancel")
        }
      }

      Text(status, style = MaterialTheme.typography.bodySmall)
      HorizontalDivider()

      when (val current = state) {
        is ScreenState.Idle -> Text("Paste a supported link, then tap Fetch formats.")
        is ScreenState.Probing -> {
          CircularProgressIndicator()
          Text("Probing video metadata...")
        }
        is ScreenState.Picking -> {
          Text(current.result.title, fontWeight = FontWeight.Medium)
          current.result.uploader?.let { Text("By $it") }
          current.result.durationSeconds?.let { Text("Duration: ${formatDuration(it)}") }
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(current.result.choices) { _, choice ->
              Card(onClick = {
                val target = url.trim()
                status = "Starting download..."
                state = ScreenState.Downloading(choice, null)
                runningJob = scope.launch {
                  try {
                    val outputPath = withContext(Dispatchers.IO) {
                      AndroidYtdlp.download(
                        context = context,
                        url = target,
                        choice = choice,
                        onProgress = { progress ->
                          state = ScreenState.Downloading(choice, progress)
                          status = progress.renderLabel()
                        },
                        onLog = { logs.add(it) },
                      )
                    }
                    state = ScreenState.Done(outputPath)
                    status = "Download complete"
                  } catch (ce: CancellationException) {
                    throw ce
                  } catch (e: Exception) {
                    state = ScreenState.Error(e.message ?: "Download failed")
                  } finally {
                    runningJob = null
                  }
                }
              }) {
                Text(choice.label, modifier = Modifier.padding(12.dp))
              }
            }
          }
        }
        is ScreenState.Downloading -> {
          Text("Downloading: ${current.choice.label}")
          current.progress?.let {
            Text(it.renderLabel())
          }
          CircularProgressIndicator()
        }
        is ScreenState.Done -> {
          Text("Done ✅")
          Text("Saved to: ${current.path}")
        }
        is ScreenState.Error -> Text("Error: ${current.message}")
      }

      if (logs.isNotEmpty()) {
        HorizontalDivider()
        Text("Recent logs", fontWeight = FontWeight.Medium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          itemsIndexed(logs.takeLast(8)) { _, line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
      }
    }
  }
}

private object AndroidYtdlp {
  private const val releaseBase = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
  private const val progressPrefix = "YOINK|"
  @Volatile
  private var activeProcess: Process? = null

  fun cancelActive() {
    activeProcess?.destroy()
    activeProcess = null
  }

  fun probe(context: Context, url: String, onLog: (String) -> Unit): ProbeResult {
    val ytdlpPath = ensureYtdlp(context, onLog)
    val ffmpegAvailable = hasCommand("ffmpeg", listOf("-version"))

    val process = ProcessBuilder(ytdlpPath, "-J", "--no-playlist", "--no-warnings", url)
      .redirectErrorStream(true)
      .start()
    activeProcess = process

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val code = process.waitFor()
    activeProcess = null
    if (code != 0) {
      throw IllegalStateException("yt-dlp probe failed with exit code $code")
    }

    val json = JSONObject(output)
    val title = json.optString("title", "Untitled")
    val uploader = json.optString("uploader").takeIf { it.isNotBlank() }
    val duration = json.optDouble("duration").takeIf { it > 0 }

    val formats = json.optJSONArray("formats")
    val choices = buildChoices(formats, ffmpegAvailable)
    if (choices.isEmpty()) {
      throw IllegalStateException("No downloadable formats were found for this URL.")
    }

    return ProbeResult(
      title = title,
      uploader = uploader,
      durationSeconds = duration,
      choices = choices,
      ffmpegAvailable = ffmpegAvailable,
    )
  }

  fun download(
    context: Context,
    url: String,
    choice: ProbeChoice,
    onProgress: (ProgressState) -> Unit,
    onLog: (String) -> Unit,
  ): String {
    val ytdlpPath = ensureYtdlp(context, onLog)
    val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "yoinks").apply { mkdirs() }

    val args = mutableListOf(
      ytdlpPath,
      url,
      "--no-playlist",
      "--no-warnings",
      "--newline",
      "--progress",
      "--progress-template",
      "download:${progressPrefix}%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s",
      "--print",
      "after_move:filepath",
      "--no-simulate",
      "-o",
      "${outputDir.absolutePath}/%(title).60s.%(ext)s",
    )
    args.addAll(choice.args)

    val process = ProcessBuilder(args).redirectErrorStream(true).start()
    activeProcess = process
    var finalPath: String? = null

    process.inputStream.bufferedReader().useLines { lines ->
      lines.forEach { line ->
        parseProgress(line)?.let(onProgress)
        if (line.startsWith("/")) {
          finalPath = line
        }
        onLog(line.take(180))
      }
    }

    val code = process.waitFor()
    activeProcess = null
    if (code != 0 || finalPath == null) {
      throw IllegalStateException("Download failed (yt-dlp exit code $code)")
    }
    return finalPath as String
  }

  private fun parseProgress(line: String): ProgressState? {
    if (!line.contains(progressPrefix)) return null
    val payload = line.substringAfter(progressPrefix)
    val parts = payload.split("|")
    if (parts.size < 5) return null

    val downloaded = parts[0].toLongOrNull() ?: return null
    val total = parts[1].toLongOrNull() ?: parts[2].toLongOrNull()
    val speed = parts[3].toDoubleOrNull()
    val eta = parts[4].toDoubleOrNull()?.toLong()

    return ProgressState(downloaded, total, speed, eta)
  }

  private fun buildChoices(formats: org.json.JSONArray?, ffmpegAvailable: Boolean): List<ProbeChoice> {
    if (formats == null) return emptyList()

    data class Candidate(
      val height: Int,
      val tbr: Double,
      val formatId: String,
      val ext: String,
      val hasAudio: Boolean,
    )

    val progressive = mutableListOf<Candidate>()
    var bestAudioM4a: String? = null

    for (i in 0 until formats.length()) {
      val item = formats.optJSONObject(i) ?: continue
      val formatId = item.optString("format_id")
      if (formatId.isBlank()) continue
      val vcodec = item.optString("vcodec")
      val acodec = item.optString("acodec")
      val height = item.optInt("height", 0)
      val ext = item.optString("ext", "")
      val tbr = item.optDouble("tbr", 0.0)

      if (vcodec != "none" && acodec != "none" && height > 0) {
        progressive += Candidate(height, tbr, formatId, ext, hasAudio = true)
      }

      if (vcodec == "none" && acodec != "none" && ext == "m4a" && bestAudioM4a == null) {
        bestAudioM4a = formatId
      }
    }

    val deduped = progressive
      .groupBy { it.height }
      .mapNotNull { (_, values) -> values.maxByOrNull { it.tbr } }
      .sortedByDescending { it.height }
      .take(8)

    val choices = deduped.map {
      ProbeChoice(
        label = "${it.height}p · ${it.ext.ifBlank { "video" }}",
        args = listOf("-f", "${it.formatId}/best[height<=${it.height}]"),
      )
    }.toMutableList()

    if (ffmpegAvailable) {
      choices += ProbeChoice(
        label = "audio only · mp3",
        args = listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"),
      )
    } else if (bestAudioM4a != null) {
      choices += ProbeChoice(
        label = "audio only · m4a",
        args = listOf("-f", bestAudioM4a),
      )
    }

    return choices
  }

  private fun ensureYtdlp(context: Context, onLog: (String) -> Unit): String {
    val binDir = File(context.filesDir, "bin").apply { mkdirs() }
    val target = File(binDir, "yt-dlp")

    if (target.exists() && target.canExecute() && hasCommand(target.absolutePath, listOf("--version"))) {
      return target.absolutePath
    }

    val asset = when {
      Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> "yt-dlp_linux_aarch64"
      Build.SUPPORTED_ABIS.any { it.contains("armeabi") || it.contains("arm") } -> "yt-dlp_linux_armv7l"
      else -> "yt-dlp_linux"
    }

    val url = "$releaseBase/$asset"
    onLog("Downloading yt-dlp binary for $asset")

    URL(url).openStream().use { input ->
      target.outputStream().use { output ->
        input.copyTo(output)
      }
    }

    target.setExecutable(true)
    if (!hasCommand(target.absolutePath, listOf("--version"))) {
      throw IllegalStateException("Failed to install yt-dlp binary for this device architecture.")
    }

    return target.absolutePath
  }

  private fun hasCommand(command: String, args: List<String>): Boolean {
    return try {
      val process = ProcessBuilder(listOf(command) + args)
        .redirectErrorStream(true)
        .start()
      process.inputStream.close()
      process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
      false
    }
  }
}

private fun ProgressState.renderLabel(): String {
  val bytes = formatBytes(downloadedBytes)
  val total = totalBytes?.let { formatBytes(it) } ?: "?"
  val speed = speedBytesPerSecond?.let { "${formatBytes(it.toLong())}/s" } ?: ""
  val eta = etaSeconds?.let { "ETA ${it}s" } ?: ""
  return "$bytes / $total  $speed $eta".trim()
}

private fun formatBytes(value: Long): String {
  if (value <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  val digitGroups = (Math.log10(value.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
  val normalized = value / Math.pow(1024.0, digitGroups.toDouble())
  return String.format(Locale.US, "%.1f %s", normalized, units[digitGroups])
}

private fun formatDuration(seconds: Double): String {
  val whole = seconds.toLong()
  val h = whole / 3600
  val m = (whole % 3600) / 60
  val s = whole % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Context.hasUsableNetwork(): Boolean {
  val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val active = manager.activeNetwork ?: return false
  val caps = manager.getNetworkCapabilities(active) ?: return false
  return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun Context.hasDownloadSpace(minBytes: Long = 100L * 1024 * 1024): Boolean {
  val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
  val stat = StatFs(directory.absolutePath)
  return stat.availableBytes >= minBytes
}
