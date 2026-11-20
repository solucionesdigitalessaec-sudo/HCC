package cl.carrerarentable

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Sonido de aviso cuando llega una carrera rentable (no suena para las no rentables). */
object Alerts {
    private val handler = Handler(Looper.getMainLooper())

    fun playProfitable() {
        // Notificación: respeta el volumen de notificaciones y el modo silencio del teléfono.
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }.getOrNull() ?: return
        // Tres tonos ascendentes, distintos del sonido de Uber/DiDi/Cabify.
        tone.startTone(ToneGenerator.TONE_DTMF_1, 120)
        handler.postDelayed({ tone.startTone(ToneGenerator.TONE_DTMF_5, 120) }, 160)
        handler.postDelayed({ tone.startTone(ToneGenerator.TONE_DTMF_D, 260) }, 320)
        handler.postDelayed({ tone.release() }, 900)
    }
}
