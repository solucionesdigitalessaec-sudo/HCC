package cl.carrerarentable.core

/** Un tramo de la carrera: recogida (ir a buscar al pasajero) o viaje. */
data class Leg(val minutes: Double, val km: Double)

/** Solicitud de viaje leída de la pantalla. Distancia y tiempo incluyen recogida + viaje. */
/** Forma de pago que aparece en la solicitud. */
enum class Payment { CARD, CASH, UNKNOWN }

data class RideOffer(
    val price: Double,
    val distanceKm: Double,
    val durationMin: Double?,
    val legs: List<Leg> = emptyList(),
    val payment: Payment = Payment.UNKNOWN,
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
            """tarifa base|din[aá]mica|gasolina|recargo|descuento|\bextra\b""",
        opts,
    )

    /**
     * @param requireLegs si es true, solo acepta tramos "X min (Y km)" / "X min · Y km" y no suma
     * kilómetros sueltos. Se usa con la lectura por imagen, donde el mapa de fondo trae textos
     * con "km" o "min" que no son de la solicitud.
     */
    private val CASH = Regex("""efectivo|\bcash\b""", opts)
    private val CARD = Regex("""tarjeta|\bcard\b|cr[eé]dito|d[eé]bito""", opts)

    /**
     * Lectura ESTRICTA de una solicitud de viaje, como la tarjeta de Uber o DiDi:
     *  1. botón para aceptar en pantalla ("Aceptar", "Toca para aceptar"),
     *  2. exactamente dos tramos: recogida y destino ("4 min • 1.6 km", "7 min (3,7 km)"),
     *  3. la tarifa ("CLP9,518" / "$1.847") ARRIBA de los tramos.
     * Cualquier otra pantalla (ganancias, ir a buscar al pasajero, mapa...) devuelve null.
     */
    fun parseRequest(lines: List<String>): RideOffer? {
        val clean = normalize(lines)

        // Cabify (lista de solicitudes): cada tarjeta empieza con "$ 3.855 para ti" y no tiene
        // botón para aceptar. Se lee cada tarjeta por separado y se devuelve la primera válida.
        val cards = clean.indices.filter { REQUEST_PRICE.containsMatchIn(clean[it]) }
        for ((k, start) in cards.withIndex()) {
            val end = cards.getOrNull(k + 1) ?: clean.size
            val offer = parse(clean.subList(start, end).joinToString("\n"), requireLegs = true, strict = true)
            if (offer != null && offer.legs.size == 2) return offer
        }

        if (!ScreenFilter.hasAcceptButton(clean)) return null
        val offer = parse(clean.joinToString("\n"), requireLegs = true, strict = true) ?: return null
        return if (offer.legs.size == 2) offer else null
    }

    /** Línea de tarifa de una tarjeta de solicitud de Cabify: "$ 3.855 para ti". */
    private val REQUEST_PRICE = Regex("""(?:\$|CLP)\s?\d[\d.,]*\s*para ti""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>, requireLegs: Boolean = false): RideOffer? =
        parse(normalize(lines).joinToString("\n"), requireLegs)

    fun parse(text: String, requireLegs: Boolean = false, strict: Boolean = false): RideOffer? {
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

        val price = findPrice(text, firstDistancePos, onlyBefore = strict) ?: return null
        if (price <= 0.0) return null
        val payment = when {
            CASH.containsMatchIn(text) -> Payment.CASH
            CARD.containsMatchIn(text) -> Payment.CARD
            else -> Payment.UNKNOWN
        }
        return RideOffer(price, distanceKm, durationMin?.takeIf { it > 0 }, legs.map { it.second }, payment)
    }

    /**
     * Tarifa = monto válido más cercano ANTES del primer kilometraje (así se evita tomar,
     * por ejemplo, las ganancias del día que se muestran arriba). Si no hay, el más cercano después.
     * Se prefieren los montos que están solos en su línea ("$1.847") por sobre los desgloses
     * ("$625 de tarifa base", "$96 de tarifa de gasolina").
     */
    private fun findPrice(text: String, anchor: Int, onlyBefore: Boolean = false): Double? {
        data class Cand(val pos: Int, val value: Double, val alone: Boolean)

        fun candidates(re: Regex): List<Cand> = re.findAll(text).mapNotNull { m ->
            if (m.groupValues[1].isNotBlank()) return@mapNotNull null          // "+$400" -> extra, no tarifa
            if (re === MONEY_PREFIX && m.groupValues[3].isNotBlank()) return@mapNotNull null // "$397/km"
            val line = lineAt(text, m.range.first)
            if (IGNORE_LINE.containsMatchIn(line)) return@mapNotNull null
            val rest = line.replace(m.value, "").replace(Regex("""(?i)CLP|est\.?|para ti|[\s$.,:]"""), "")
            Cand(m.range.first, amount(m.groupValues[2]), rest.isEmpty())
        }.toList()

        val all = candidates(MONEY_PREFIX).ifEmpty { candidates(MONEY_SUFFIX) }
        val cands = all.filter { it.alone }.ifEmpty { all }
        if (cands.isEmpty()) return null
        val before = cands.filter { it.pos <= anchor }.maxByOrNull { it.pos }
        if (onlyBefore) return before?.value
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
        val clean = lines.map { fixOcrDigits(it.replace('\u00A0', ' ').trim()) }.filter { it.isNotEmpty() }.distinct()
        return clean.filter { line -> clean.none { other -> other !== line && other.length > line.length && other.contains(line) } }
    }

    // El reconocimiento de imagen confunde 1 con l / I / | y 0 con O dentro de los números:
    // "2.l km" -> "2.1 km", "+CLPl,750.00" -> "+CLP1,750.00".
    private val OCR_NUM = """([0-9lIO|][0-9lIO|.,]*)"""
    private val OCR_BEFORE_UNIT = Regex("""(?<![A-Za-z])$OCR_NUM(?=\s*(?i:km|kms|min|mins)\b)""")
    private val OCR_AFTER_MONEY = Regex("""(CLP|\$)(\s?)$OCR_NUM""")

    private fun digits(s: String) = s.replace('l', '1').replace('I', '1').replace('|', '1').replace('O', '0')

    fun fixOcrDigits(line: String): String {
        var s = OCR_BEFORE_UNIT.replace(line) { m ->
            if (m.value.any { it.isDigit() } || m.value.length == 1) digits(m.value) else m.value
        }
        s = OCR_AFTER_MONEY.replace(s) { m ->
            val n = m.groupValues[3]
            m.groupValues[1] + m.groupValues[2] + (if (n.any { it.isDigit() }) digits(n) else n)
        }
        return s
    }
}
