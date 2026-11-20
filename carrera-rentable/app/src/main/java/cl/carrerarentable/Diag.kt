package cl.carrerarentable

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de diagnóstico (solo en el teléfono): estado del turno y último texto leído de la pantalla. */
object Diag {
    private const val FILE = "diagnostico"
    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun now() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())

    fun ocr(c: Context, status: String, lines: List<String> = emptyList()) {
        val e = prefs(c).edit().putString("imagen", "${now()} · $status")
        if (lines.isNotEmpty()) e.putString("texto_imagen", lines.joinToString("\n").take(4000))
        e.apply()
    }

    /** Por qué terminó el turno (se guardan los últimos 5). commit = se escribe al tiro (por si la app se cierra). */
    fun shiftEnded(c: Context, reason: String) {
        val p = prefs(c)
        val list = (listOf("${now()} · $reason") + p.getString("cortes", "").orEmpty().split("\n").filter { it.isNotBlank() }).take(5)
        p.edit().putString("cortes", list.joinToString("\n")).commit()
    }

    /** Marca de turno activo, para detectar si el sistema cerró la app sin avisar. */
    fun setShiftFlag(c: Context, on: Boolean) = prefs(c).edit().putBoolean("turno_marcado", on).commit()
    fun shiftFlag(c: Context) = prefs(c).getBoolean("turno_marcado", false)

    fun report(c: Context): String {
        val p = prefs(c)
        return buildString {
            appendLine("Mostrar sobre otras apps: ${if (OfferController.canDraw(c)) "sí" else "NO"}")
            appendLine("Turno: ${if (ShiftService.running) "activo" else "detenido"}")
            appendLine("Lectura por imagen: ${p.getString("imagen", null) ?: "nunca"}")
            val cortes = p.getString("cortes", "").orEmpty()
            if (cortes.isNotBlank()) appendLine("Últimos fines de turno:\n$cortes")
        }.trim()
    }

    fun lastText(c: Context) = prefs(c).getString("texto_imagen", "").orEmpty()
}
