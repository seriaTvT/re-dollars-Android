package mk.ry.redollars.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Thin MediaRecorder wrapper capturing AAC in an .m4a container (the web's allowed
 * audio set includes m4a, and browsers play it). Files land in the cache dir and are
 * deleted on cancel or after a successful send.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** Start capturing; false when the microphone can't be opened. */
    fun start(): Boolean {
        cancel()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        return runCatching {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            true
        }.getOrElse {
            release()
            out.delete()
            false
        }
    }

    /** Stop and return the recording, or null when nothing valid was captured. */
    fun stop(): File? {
        val out = file
        val ok = recorder != null && runCatching { recorder?.stop() }.isSuccess
        release()
        file = null
        return if (ok && out != null && out.length() > 0) {
            out
        } else {
            out?.delete()
            null
        }
    }

    /** Abort and discard the current recording. */
    fun cancel() {
        runCatching { recorder?.stop() }
        release()
        file?.delete()
        file = null
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }
}
