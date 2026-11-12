package cl.carrerarentable.core

/** Un tramo de la carrera: recogida (ir a buscar al pasajero) o viaje. */
data class Leg(val minutes: Double, val km: Double)

/** Solicitud de viaje leída de la pantalla. Distancia y tiempo incluyen recogida + viaje. */
data class RideOffer(
    val price: Double,
    val distanceKm: Double,
    val durationMin: Double?,
    val legs: List<Leg> = emptyList(),
)

/**
 * Extrae tarifa, kilómetros y minutos del texto visible de las apps de conductor
 * (Uber Driver, DiDi Conductor, Cabify Driver...).
 *
 * Formatos reconocidos, por ejemplo:
 *  - Uber:  "CLP6,980" · "9 min (3.6 km)" · "22 min (14.0 km)"
 *  - DiDi:  "$6.980" · "3,6 km (9 min)" · "9 min · 3,6 km"
 *  - Genérico: cualquier "X km" y "Y min" sueltos (se suman).
 */
object OfferParser {

    private const val TIME = """(?:(\d+)\s*(?:h|hr|hrs|horas?)\.?\s*)?(\d+)\s*(?:min|mins|minutos?)\b\.?"""
    private const val DIST = """(\d+(?:[.,]\d+)?)\s*(km|kms|kil[oó]metros?|m|mts|metros?)\b"""
    // Separador entre tiempo y distancia en la MISMA línea: "·", "•", "-", "|" o nada
    // (el reconocimiento de imagen a veces no ve el punto: "4 min 1.6 km").
    private const val SEP = """[ \t]*[^\w\n(]{0,3}[ \t]*(?:a[ \t]+)?"""

    private val opts = setOf(RegexOption.IGNORE_CASE)

    // grupos: 1-2 tiempo, 3-4 distancia
    private val TIME_THEN_DIST = listOf(
        Regex("""$TIME\s*\(\s*$DIST\s*\)""", opts),
        Regex("""$TIME$SEP$DIST""", opts),
    )
    // grupos: 1-2 distancia, 3-4 tiempo
    private val DIST_THEN_TIME = listOf(
        Regex("""$DIST\s*\(\s*$TIME\s*\)""", opts),
        Regex("""$DIST$SEP$TIME""", opts),
    )
    private val TIME_RE = Regex(TIME, opts)
    private val DIST_RE = Regex(DIST, opts)

    private const val AMOUNT = """(\d{1,3}(?:[.,]\d{3})+|\d+)(?:[.,]\d{1,2}(?!\d))?"""
    private val MONEY_PREFIX = Regex(
        // "S" = signo $ mal leído por el reconocimiento de imagen (solo si sigue un monto con miles: "S1.847").
        """(\+\s*)?(?:CLP|\$|(?<![A-Za-z])S(?=\d{1,3}[.,]\d{3}))\s?$AMOUNT(\s*/\s*(?:km|kms|h|hr|hrs|hora|min))?""", opts
    )
    private val MONEY_SUFFIX = Regex(
        """(?<![\d.,$])(\+\s*)?$AMOUNT\s?(?:CLP|\$)(?!\s*/)""", opts
    )

    /** Líneas que contienen montos que NO son la tarifa (propinas incluidas, bonos, ganancias del día...). */
    private val IGNORE_LINE = Regex(
        """incluid|propina|peaje|bono|promo|ganancia(s)? (de )?hoy|saldo|billetera|semana|today|earnings|wallet|""" +
            """tarifa base|din[aá]mica|gasolina|recargo|descuento""",
        opts,
    )

    /**
     * @param requireLegs si es true, solo acepta tramos "X min (Y km)" / "X min · Y km" y no suma
     * kilómetros sueltos. Se usa con la lectura por imagen, donde el mapa de fondo trae textos
     * con "km" o "min" que no son de la solicitud.
     */
    fun parse(lines: List<String>, requireLegs: Boolean = false): RideOffer? =
        parse(normalize(lines).joinToString("\n"), requireLegs)

    fun parse(text: String, requireLegs: Boolean = false): RideOffer? {
        val legs = mutableListOf<Pair<IntRange, Leg>>()
        fun overlaps(r: IntRange) = legs.any { it.first.first <= r.last && r.first <= it.first.last }

        for (re in TIME_THEN_DIST) for (m in re.findAll(text)) {
            if (overlaps(m.range)) continue
            legs += m.range to Leg(minutes(m.groupValues[1], m.groupValues[2]), km(m.groupValues[3], m.groupValues[4]))
        }
        for (re in DIST_THEN_TIME) for (m in re.findAll(text)) {
            if (overlaps(m.range)) continue
            legs += m.range to Leg(minutes(m.groupValues[3], m.groupValues[4]), km(m.groupValues[1], m.groupValues[2]))
        }

        val distanceKm: Double
        val durationMin: Double?
        val firstDistancePos: Int
        if (legs.isNotEmpty()) {
            legs.sortBy { it.first.first }
            distanceKm = legs.sumOf { it.second.km }
            durationMin = legs.sumOf { it.second.minutes }
            firstDistancePos = legs.first().first.first
        } else {
            if (requireLegs) return null
            val dists = DIST_RE.findAll(text).toList()
            if (dists.isEmpty()) return null
            distanceKm = dists.sumOf { km(it.groupValues[1], it.groupValues[2]) }
            val times = TIME_RE.findAll(text).toList()
            durationMin = if (times.isEmpty()) null else times.sumOf { minutes(it.groupValues[1], it.groupValues[2]) }
            firstDistancePos = dists.first().range.first
        }
        if (distanceKm <= 0.0) return null

        val price = findPrice(text, firstDistancePos) ?: return null
        if (price <= 0.0) return null
        return RideOffer(price, distanceKm, durationMin?.takeIf { it > 0 }, legs.map { it.second })
    }

    /**
     * Tarifa = monto válido más cercano ANTES del primer kilometraje (así se evita tomar,
     * por ejemplo, las ganancias del día que se muestran arriba). Si no hay, el más cercano después.
     * Se prefieren los montos que están solos en su línea ("$1.847") por sobre los desgloses
     * ("$625 de tarifa base", "$96 de tarifa de gasolina").
     */
    private fun findPrice(text: String, anchor: Int): Double? {
        data class Cand(val pos: Int, val value: Double, val alone: Boolean)

        fun candidates(re: Regex): List<Cand> = re.findAll(text).mapNotNull { m ->
            if (m.groupValues[1].isNotBlank()) return@mapNotNull null          // "+$400" -> extra, no tarifa
            if (re === MONEY_PREFIX && m.groupValues[3].isNotBlank()) return@mapNotNull null // "$397/km"
            val line = lineAt(text, m.range.first)
            if (IGNORE_LINE.containsMatchIn(line)) return@mapNotNull null
            val rest = line.replace(m.value, "").replace(Regex("""(?i)CLP|est\.?|[\s$.,:]"""), "")
            Cand(m.range.first, amount(m.groupValues[2]), rest.isEmpty())
        }.toList()

        val all = candidates(MONEY_PREFIX).ifEmpty { candidates(MONEY_SUFFIX) }
        val cands = all.filter { it.alone }.ifEmpty { all }
        if (cands.isEmpty()) return null
        val before = cands.filter { it.pos <= anchor }.maxByOrNull { it.pos }
        return (before ?: cands.minByOrNull { it.pos })?.value
    }

    private fun lineAt(text: String, pos: Int): String {
        val start = text.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    /** Montos en CLP: el punto y la coma seguidos de 3 dígitos son separadores de miles. */
    private fun amount(s: String): Double = s.replace(".", "").replace(",", "").toDouble()

    private fun km(value: String, unit: String): Double {
        val v = value.replace(',', '.').toDouble()
        return if (unit.lowercase().startsWith("k")) v else v / 1000.0
    }

    private fun minutes(h: String, m: String): Double =
        (h.toIntOrNull() ?: 0) * 60.0 + (m.toIntOrNull() ?: 0)

    /**
     * Limpia el texto leído: quita espacios, duplicados y líneas contenidas dentro de otras
     * (Android suele repetir el texto de los hijos en la descripción del contenedor).
     */
    fun normalize(lines: List<String>): List<String> {
        val clean = lines.map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }.distinct()
        return clean.filter { line -> clean.none { other -> other !== line && other.length > line.length && other.contains(line) } }
    }
}
