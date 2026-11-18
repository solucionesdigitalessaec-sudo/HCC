package cl.carrerarentable.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Lo que se alcanza a leer del detalle de un viaje ya hecho (historial de Uber / Cabify).
 * En Cabify los datos están repartidos en la pantalla (hay que bajar), así que se juntan
 * varias capturas del mismo viaje: la fecha y hora de arriba identifican el viaje.
 */
data class HistoryRead(
    val app: String,
    /** Fecha y hora del viaje (ms). Identifica el viaje para no duplicarlo. */
    val time: Long,
    val price: Double?,
    val km: Double?,
    val minutes: Double?,
)

object HistoryParser {

    private val MONTHS = mapOf(
        "ene" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "ago" to 8, "sep" to 9, "set" to 9, "oct" to 10, "nov" to 11, "dic" to 12,
    )

    private val o = setOf(RegexOption.IGNORE_CASE)
    // "16 nov 2026 • 10:59" / "16 nov., 18:07" / "lun 16 nov, 18:07"
    private val DATE_FULL = Regex("""(\d{1,2})\s+(ene|feb|mar|abr|may|jun|jul|ago|sep|set|oct|nov|dic)[a-z]*\.?(?:\s+(?:de\s+)?(\d{4}))?\s*[•·,\-]?\s*(\d{1,2}):(\d{2})""", o)
    // "Hoy, 18:07" / "Ayer, 9:15"
    private val DATE_REL = Regex("""\b(hoy|ayer)\s*,?\s*(\d{1,2}):(\d{2})""", o)

    private const val AMOUNT = """(\d{1,3}(?:[.,]\d{3})+|\d+)(?:[.,]\d{2}(?!\d))?"""
    private val PARA_TI = Regex("""\$\s?$AMOUNT\s*para ti""", o)
    private val TOTAL_PARA_TI = Regex("""total viaje para ti\s*\$\s?$AMOUNT""", o)
    private val UBER_EARNINGS = Regex("""tus ganancias\s*$AMOUNT\s*CLP""", o)
    private val UBER_BIG = Regex("""^$AMOUNT\s*CLP$""", o)
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s*km\b""", o)
    private val MINSEC = Regex("""(\d+)\s*min(?:utos)?(?:\s+(\d+)\s*seg)?""", o)

    /** ¿Es la pantalla de detalle de un viaje hecho? Si no, null. */
    fun parse(lines: List<String>, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): HistoryRead? {
        // Sin quitar líneas repetidas dentro de otras: "2.102 CLP" también aparece en "Tarifa por adelantado: 2.102 CLP".
        val clean = lines.map { OfferParser.fixOcrDigits(it.replace('\u00A0', ' ').trim()) }.filter { it.isNotEmpty() }
        val text = clean.joinToString("\n")
        val uber = Regex("""detalles del viaje""", o).containsMatchIn(text)
        val cabify = !uber && Regex("""cabify""", o).containsMatchIn(text) &&
            Regex("""total a cobrar|trayecto|para ti|rentabilidad|origen|destino|duraci[oó]n|distancia""", o).containsMatchIn(text)
        if (!uber && !cabify) return null
        // Una solicitud nueva (con botón para aceptar) no es historial.
        if (ScreenFilter.hasAcceptButton(clean)) return null

        val time = dateOf(text, zone, today) ?: return null

        val price = if (uber) {
            UBER_EARNINGS.find(text)?.let { amount(it.groupValues[1]) }
                ?: clean.firstNotNullOfOrNull { l -> UBER_BIG.find(l.trim())?.let { amount(it.groupValues[1]) } }
        } else {
            PARA_TI.find(text)?.let { amount(it.groupValues[1]) }
                ?: TOTAL_PARA_TI.find(text)?.let { amount(it.groupValues[1]) }
        }
        val km = DIST.find(text)?.let { it.groupValues[1].replace(',', '.').toDouble() }
        val minutes = durationOf(clean)
        return HistoryRead(if (uber) "Uber" else "Cabify", time, price, km, minutes)
    }

    private fun durationOf(lines: List<String>): Double? {
        // Preferir el valor que acompaña a "Duración" (misma línea o la siguiente).
        val i = lines.indexOfFirst { Regex("""(?i)duraci[oó]n""").containsMatchIn(it) }
        val candidates = if (i >= 0) lines.drop(i).take(4) else lines
        for (l in candidates) {
            MINSEC.find(l)?.let { m ->
                val min = m.groupValues[1].toDouble()
                val sec = m.groupValues[2].toDoubleOrNull() ?: 0.0
                return min + sec / 60.0
            }
        }
        return null
    }

    private fun dateOf(text: String, zone: ZoneId, today: LocalDate): Long? {
        DATE_REL.find(text)?.let { m ->
            val day = if (m.groupValues[1].lowercase() == "ayer") today.minusDays(1) else today
            return at(day, m.groupValues[2].toInt(), m.groupValues[3].toInt(), zone)
        }
        DATE_FULL.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[2].lowercase().take(3)] ?: return null
            var year = m.groupValues[3].toIntOrNull() ?: today.year
            var date = runCatching { LocalDate.of(year, month, m.groupValues[1].toInt()) }.getOrNull() ?: return null
            // Sin año y en el futuro: es del año pasado.
            if (m.groupValues[3].isEmpty() && date.isAfter(today)) { year -= 1; date = date.withYear(year) }
            return at(date, m.groupValues[4].toInt(), m.groupValues[5].toInt(), zone)
        }
        return null
    }

    private fun at(d: LocalDate, h: Int, min: Int, zone: ZoneId): Long? {
        if (h > 23 || min > 59) return null
        return LocalDateTime.of(d.year, d.month, d.dayOfMonth, h, min).atZone(zone).toInstant().toEpochMilli()
    }

    private fun amount(s: String) = s.replace(".", "").replace(",", "").toDouble()
}
