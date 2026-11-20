package cl.carrerarentable.core

/** Datos del auto para calcular el costo por km. */
data class Vehicle(
    /** Precio del litro de combustible (diésel, bencina...). */
    val fuelPricePerLiter: Double = 1591.0,
    /** Rendimiento: km recorridos por litro. */
    val kmPerLiter: Double = 9.6,
    /** Otros costos por km (desgaste, mantención...). */
    val otherCostPerKm: Double = 0.0,
) {
    val fuelPerKm: Double get() = if (kmPerLiter > 0) fuelPricePerLiter / kmPerLiter else 0.0
    val costPerKm: Double get() = fuelPerKm + otherCostPerKm
}

/** Una carrera de la bitácora. Los km incluyen la recogida (también se manejan y gastan combustible). */
data class Trip(
    val id: Long = 0,
    /** Momento en que se detectó o anotó (ms desde 1970). */
    val time: Long,
    val app: String,
    /** Monto "para ti" de la solicitud (ya sin la comisión de la app). */
    val price: Double,
    val pickupKm: Double,
    val tripKm: Double,
    val minutes: Double?,
    /** "auto" (detectada) o "manual". */
    val source: String = "auto",
) {
    val totalKm: Double get() = pickupKm + tripKm
}

/** Cuánto queda de una carrera después de la retención y los costos del auto. */
data class TripEarnings(
    val gross: Double,
    val retention: Double,
    val fuel: Double,
    val otherCosts: Double,
) {
    val net: Double get() = gross - retention - fuel - otherCosts
    /** Lo que queda sin descontar la retención (que en parte se puede recuperar en la Operación Renta). */
    val beforeRetention: Double get() = gross - fuel - otherCosts
}

/** Totales de un período (hoy, semana, mes). */
data class Summary(
    val trips: Int,
    val gross: Double,
    val retention: Double,
    val fuel: Double,
    val otherCosts: Double,
    val km: Double,
    val tripMinutes: Double,
    /** Minutos de turno (para el $/hora real). */
    val shiftMinutes: Double,
) {
    val net: Double get() = gross - retention - fuel - otherCosts
    val netPerKm: Double? get() = if (km > 0) net / km else null
    val netPerHour: Double? get() = if (shiftMinutes > 0) net / (shiftMinutes / 60.0) else null
}

object Ledger {
    fun earnings(trip: Trip, vehicle: Vehicle, retentionPct: Double) = TripEarnings(
        gross = trip.price,
        retention = trip.price * retentionPct / 100.0,
        fuel = trip.totalKm * vehicle.fuelPerKm,
        otherCosts = trip.totalKm * vehicle.otherCostPerKm,
    )

    fun summarize(trips: List<Trip>, vehicle: Vehicle, retentionPct: Double, shiftMinutes: Double): Summary {
        val e = trips.map { earnings(it, vehicle, retentionPct) }
        return Summary(
            trips = trips.size,
            gross = e.sumOf { it.gross },
            retention = e.sumOf { it.retention },
            fuel = e.sumOf { it.fuel },
            otherCosts = e.sumOf { it.otherCosts },
            km = trips.sumOf { it.totalKm },
            tripMinutes = trips.sumOf { it.minutes ?: 0.0 },
            shiftMinutes = shiftMinutes,
        )
    }

    /** Carrera a partir de la solicitud leída: el primer tramo es la recogida y el segundo el viaje. */
    fun tripFromOffer(offer: RideOffer, app: String, time: Long): Trip {
        val pickup = offer.legs.getOrNull(0)?.km ?: 0.0
        val ride = offer.legs.getOrNull(1)?.km ?: (offer.distanceKm - pickup)
        return Trip(time = time, app = app, price = offer.price, pickupKm = pickup, tripKm = ride, minutes = offer.durationMin)
    }
}

/** Qué app de conducción se ve en la pantalla. */
object RideAppGuess {
    fun from(lines: List<String>): String {
        val t = lines.joinToString("\n")
        return when {
            Regex("""(?i)cabify|para ti\b""").containsMatchIn(t) -> "Cabify"
            Regex("""(?i)\bdidi\b""").containsMatchIn(t) -> "DiDi"
            Regex("""(?i)\buber|\bCLP\s?\d""").containsMatchIn(t) -> "Uber"
            else -> "Otra"
        }
    }
}

/**
 * ¿La pantalla muestra que se aceptó una carrera (ir a buscar al pasajero / viaje en curso)?
 * Se usa justo después de ver una solicitud para anotarla en la bitácora.
 */
object TripStateDetector {
    private val ACCEPTED = Regex(
        """iniciar (viaje|uber|cabify|el viaje)|llegu[eé]\b|he llegado|ya llegu[eé]|recoger (a|al)\b|ir a recoger|""" +
            """yendo a recoger|recogiendo|esperando al pasajero|esperando a tu pasajero|pin obligatorio|""" +
            """finalizar viaje|terminar viaje|viaje en curso|dirígete|navegar""",
        RegexOption.IGNORE_CASE,
    )

    /** Señal de que se aceptó el SIGUIENTE viaje estando en otro (Uber "Siguiente viaje", etc.). */
    private val NEXT_TRIP = Regex("""siguiente (viaje|recogida|pasajero)|pr[oó]ximo (viaje|pasajero)|viaje en cola|a continuaci[oó]n""", RegexOption.IGNORE_CASE)

    fun looksNextTripAccepted(lines: List<String>): Boolean =
        looksAccepted(lines) && lines.any { NEXT_TRIP.containsMatchIn(it) }

    fun looksAccepted(lines: List<String>): Boolean {
        if (lines.isEmpty() || ScreenFilter.hasAcceptButton(lines)) return false
        return lines.any { ACCEPTED.containsMatchIn(it) }
    }
}

/** Exportación de la bitácora a CSV (separado por ";" y con coma decimal, para Excel en español). */
object LedgerCsv {
    fun build(trips: List<Trip>, vehicle: Vehicle, retentionPct: Double, formatDate: (Long) -> Pair<String, String>): String {
        fun n(v: Double, d: Int = 0) = if (d == 0) Math.round(v).toString() else Money.decimal(v, d)
        val sb = StringBuilder("﻿")
        sb.append("fecha;hora;app;monto;km_recogida;km_viaje;km_total;minutos;retencion;combustible;otros_costos;ganancia_real;origen\n")
        for (t in trips) {
            val e = Ledger.earnings(t, vehicle, retentionPct)
            val (date, hour) = formatDate(t.time)
            sb.append(listOf(
                date, hour, t.app, n(t.price), n(t.pickupKm, 1), n(t.tripKm, 1), n(t.totalKm, 1),
                t.minutes?.let { n(it) } ?: "", n(e.retention), n(e.fuel), n(e.otherCosts), n(e.net), t.source,
            ).joinToString(";")).append('\n')
        }
        return sb.toString()
    }
}
