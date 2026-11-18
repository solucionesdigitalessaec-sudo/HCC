package cl.carrerarentable

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import cl.carrerarentable.core.HistoryParser
import cl.carrerarentable.core.HistoryRead
import cl.carrerarentable.core.Money
import cl.carrerarentable.core.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Importar historial": mientras está activo, cada detalle de viaje que se abre en Uber o Cabify
 * se anota en la bitácora con su fecha, hora y monto final. Los datos de un mismo viaje se juntan
 * aunque estén en distintas partes de la pantalla (en Cabify hay que bajar para ver la distancia).
 */
object HistoryImporter {
    @Volatile var active = false
        private set
    /** El turno (captura de pantalla) se inició solo para importar: al terminar se detiene. */
    var startedShift = false
    var imported = 0
        private set

    private val partial = HashMap<String, HistoryRead>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT)

    fun start() {
        partial.clear(); imported = 0; active = true
    }

    fun stop(c: Context) {
        active = false
        partial.clear()
        if (startedShift) { startedShift = false; ShiftService.stop(c) }
    }

    /** El turno terminó (por ejemplo desde la notificación): la importación también. */
    fun onShiftEnded() {
        active = false; startedShift = false; partial.clear()
    }

    /** Texto de una captura (hilo principal). */
    fun onLines(c: Context, lines: List<String>) {
        val r = HistoryParser.parse(lines) ?: return
        val key = "${r.app}|${r.time}"
        val prev = partial[key]
        val merged = HistoryRead(
            app = r.app, time = r.time,
            price = r.price ?: prev?.price, km = r.km ?: prev?.km, minutes = r.minutes ?: prev?.minutes,
        )
        if (merged == prev) return
        partial[key] = merged
        val price = merged.price ?: return // sin monto todavía: esperar a que se vea
        val trip = Trip(
            time = merged.time, app = merged.app, price = price,
            pickupKm = SettingsStore.historyPickupKm(c), tripKm = merged.km ?: 0.0,
            minutes = merged.minutes, source = "historial",
        )
        Thread {
            val isNew = TripLog.get(c).upsertByKey(key, trip)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (isNew) {
                    imported++
                    buzz(c)
                    Toast.makeText(c, "Anotada: ${merged.app} ${fmt.format(Date(merged.time))} ${Money.clp(price)}", Toast.LENGTH_SHORT).show()
                }
                Diag.ocr(c, "historial: ${merged.app} ${fmt.format(Date(merged.time))} ${Money.clp(price)}" +
                    (merged.km?.let { " · ${Money.decimal(it)} km" } ?: " · falta la distancia (baja en el detalle)"))
            }
        }.start()
    }

    private fun buzz(c: Context) {
        val v = c.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}
