package de.mm20.launcher2.capture

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Decodes the committed recording, never opens the microphone or sends audio to a service. */
object OfflineTranscriber {
    @Synchronized
    private fun modelDirectory(context: Context): File {
        val root = File(context.noBackupFilesDir, "speech")
        val target = File(root, "en-us-0.15")
        if (File(target, "complete").isFile) return target
        root.mkdirs()
        val staging = File(root, "extracting"); staging.deleteRecursively(); staging.mkdirs()
        try {
            var total = 0L
            ZipInputStream(context.assets.open("speech/en-us.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val prefix = "vosk-model-small-en-us-0.15/"
                    require(entry.name.startsWith(prefix))
                    val relative = entry.name.removePrefix(prefix)
                    if (relative.isEmpty()) continue
                    val file = File(staging, relative).canonicalFile
                    require(file.path.startsWith(staging.canonicalPath + File.separator))
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val size = zip.read(buffer); if (size < 0) break
                                total += size; require(total <= 200L * 1024 * 1024)
                                out.write(buffer, 0, size)
                            }
                        }
                    }
                }
            }
            check(File(staging, "am/final.mdl").isFile)
            File(staging, "complete").writeText("0.15")
            target.deleteRecursively(); check(staging.renameTo(target))
            return target
        } finally { staging.deleteRecursively() }
    }

    fun transcribe(context: Context, file: File, stopped: () -> Boolean = { false }): String {
        // Avoid logging recognized text or model internals.
        LibVosk.vosk_set_log_level(-1)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var recognizer: Recognizer? = null
        val text = StringBuilder()
        fun append(json: String) {
            val part = JSONObject(json).optString("text").trim()
            if (part.isNotEmpty()) { if (text.isNotEmpty()) text.append(' '); text.append(part) }
            check(text.length <= 131072) { "Transcript too long" }
        }
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0); decoder.start()
            Model(modelDirectory(context).path).use { model ->
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                val deadline = SystemClock.elapsedRealtime() + 8 * 60 * 1000
                var frames = 0L
                while (true) {
                    if (stopped()) throw java.util.concurrent.CancellationException()
                    check(SystemClock.elapsedRealtime() < deadline) { "Transcription timed out" }
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index))
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(channels in 1..8 && rate in 16000..192000)
                        require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    } else if (index >= 0) {
                        if (info.size > 0) {
                            val active = recognizer ?: Recognizer(model, rate.toFloat()).also { recognizer = it }
                            val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val mono = ShortArray(info.size / (2 * channels))
                            for (i in mono.indices) {
                                var sum = 0
                                repeat(channels) { sum += buffer.short.toInt() }
                                mono[i] = (sum / channels).toShort()
                            }
                            frames += mono.size; check(frames <= rate.toLong() * 601) { "Audio exceeds ten minutes" }
                            if (active.acceptWaveForm(mono, mono.size)) append(active.result)
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                recognizer?.let { append(it.finalResult) }
            }
            return text.toString()
        } finally {
            recognizer?.close()
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
