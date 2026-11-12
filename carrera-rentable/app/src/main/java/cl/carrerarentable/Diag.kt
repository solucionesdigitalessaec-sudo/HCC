package cl.carrerarentable

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico (solo en el teléfono) para saber en qué paso falla la lectura:
 * ¿el servicio está conectado? ¿llegan eventos de Uber? ¿se lee texto? ¿se detecta la oferta?
 */
object Diag {
    private const val FILE = "diagnostico"
    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun now() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())

    fun serviceConnected(c: Context) = prefs(c).edit().putString("conectado", now()).apply()

    /** Paquetes de las últimas apps que generaron eventos (para ver el nombre real de Uber/DiDi). */
    fun eventFrom(c: Context, pkg: String) {
        val p = prefs(c)
        val recent = p.getString("paquetes", "")!!.split(',').filter { it.isNotBlank() && it != pkg }
        val list = (listOf(pkg) + recent).take(8).joinToString(",")
        if (list != p.getString("paquetes", "")) p.edit().putString("paquetes", list).apply()
    }

    fun rideEvent(c: Context, pkg: String) =
        prefs(c).edit().putString("evento", "$pkg · ${now()}").apply()

    fun ocr(c: Context, status: String, lines: List<String> = emptyList()) {
        val e = prefs(c).edit().putString("imagen", "${now()} · $status")
        if (lines.isNotEmpty()) e.putString("texto_imagen", lines.joinToString("\n").take(4000))
        e.apply()
    }

    fun windows(c: Context, info: String) = prefs(c).edit().putString("ventanas", info).apply()

    fun scan(c: Context, windows: Int, texts: List<String>, offerFound: Boolean) =
        prefs(c).edit()
            .putString("lectura", "${now()} · ventanas: $windows · textos: ${texts.size} · oferta: ${if (offerFound) "SÍ" else "no"}")
            .putString("texto", texts.joinToString("\n").take(4000))
            .apply()

    fun report(c: Context): String {
        val p = prefs(c)
        fun v(k: String) = p.getString(k, null) ?: "nunca"
        return buildString {
            appendLine("Servicio conectado: ${v("conectado")}")
            appendLine("Último evento de app de conductor: ${v("evento")}")
            appendLine("Última lectura: ${v("lectura")}")
            appendLine("Pantalla: ${v("ventanas")}")
            appendLine("Lectura por imagen: ${v("imagen")}")
            appendLine("Apps vistas: ${p.getString("paquetes", "").orEmpty().ifBlank { "ninguna" }.replace(",", ", ")}")
        }.trim()
    }

    fun lastText(c: Context): String {
        val p = prefs(c)
        val img = p.getString("texto_imagen", "").orEmpty()
        val txt = p.getString("texto", "").orEmpty()
        return listOf(
            if (img.isNotBlank()) "[imagen]\n$img" else "",
            if (txt.isNotBlank()) "[accesibilidad]\n$txt" else "",
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
