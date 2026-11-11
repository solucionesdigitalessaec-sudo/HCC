package cl.carrerarentable.core

data class Rules(
    /** Mínimo que debe pagar la carrera por cada km manejado (recogida + viaje). */
    val minPerKm: Double = 700.0,
    /** Mínimo por hora; 0 = no se exige. */
    val minPerHour: Double = 0.0,
    /** Costo propio por km (bencina, desgaste...); 0 = no se descuenta. */
    val costPerKm: Double = 0.0,
)

data class Evaluation(
    val offer: RideOffer,
    val perKm: Double,
    val perHour: Double?,
    val net: Double,
    val netPerHour: Double?,
    val perKmOk: Boolean,
    val perHourOk: Boolean,
    val profitable: Boolean,
)

object ProfitCalculator {
    fun evaluate(offer: RideOffer, rules: Rules): Evaluation {
        val perKm = offer.price / offer.distanceKm
        val hours = offer.durationMin?.let { it / 60.0 }
        val perHour = hours?.let { offer.price / it }
        val net = offer.price - rules.costPerKm * offer.distanceKm
        val netPerHour = hours?.let { net / it }

        val perKmOk = perKm >= rules.minPerKm
        // Si se exige un mínimo por hora pero no se pudo leer el tiempo, no se bloquea por eso.
        val perHourOk = rules.minPerHour <= 0 || perHour == null || perHour >= rules.minPerHour
        return Evaluation(offer, perKm, perHour, net, netPerHour, perKmOk, perHourOk, perKmOk && perHourOk)
    }
}

object Money {
    /** 6980.4 -> "$6.980" (formato chileno). */
    fun clp(value: Double): String {
        val n = Math.round(value)
        val digits = kotlin.math.abs(n).toString().reversed().chunked(3).joinToString(".").reversed()
        return (if (n < 0) "-$" else "$") + digits
    }

    /** 17.6 -> "17,6" */
    fun decimal(value: Double, decimals: Int = 1): String =
        String.format(java.util.Locale.ROOT, "%.${decimals}f", value).replace('.', ',')
}
