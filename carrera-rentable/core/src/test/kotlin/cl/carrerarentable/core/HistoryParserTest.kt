package cl.carrerarentable.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Detalle de viajes ya hechos, con el texto de las capturas reales del conductor. */
class HistoryParserTest {
    private val zone = ZoneId.of("America/Santiago")
    private val today = LocalDate.of(2026, 11, 18)
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun uberTripDetails() {
        val r = HistoryParser.parse(
            listOf(
                "Detalles del viaje", "Uber X • 16 nov 2026 • 10:59", "2.102 CLP", "Tarifa por adelantado: 2.102 CLP",
                "PEDRO DE VALDIVIA NORTE", "Las Condes", "Duración", "Distancia", "10 min 36 seg", "3.63 km",
                "Felipe II, Las Condes, Región Metropolitana, Chile", "Rapel, Ñuñoa, Región Metropolitana, Chile",
            ),
            zone, today,
        )
        assertNotNull(r)
        assertEquals("Uber", r.app)
        assertEquals(ms(2026, 11, 16, 10, 59), r.time)
        assertEquals(2102.0, r.price)
        assertEquals(3.63, r.km)
        assertTrue(kotlin.math.abs(r.minutes!! - 10.6) < 0.01)
    }

    @Test
    fun uberEarningsSection() {
        val r = HistoryParser.parse(
            listOf("Detalles del viaje", "Uber X • 16 nov 2026 • 10:59", "Tus ganancias", "Tarifa 2.102 CLP",
                "Tus ganancias 2.102 CLP", "Impuestos -321 CLP", "Saldo de viajes 1.781 CLP"),
            zone, today,
        )
        assertNotNull(r)
        assertEquals(2102.0, r.price)
    }

    @Test
    fun cabifyTopOfDetail() {
        val r = HistoryParser.parse(
            listOf("Hoy, 18:07", "Cabify", "$ 11.258 para ti", "+1 punto Cabify Stars", "Total a cobrar", "COBRO EN APP",
                "$ 11.258", "Rentabilidad", "Ganancias por km", "$ 712", "Ganancias por hora", "$ 8.300", "Ver ganancias",
                "Trayecto", "Origen", "General Holley, 2325 - Providencia"),
            zone, today,
        )
        assertNotNull(r)
        assertEquals("Cabify", r.app)
        assertEquals(ms(2026, 11, 18, 18, 7), r.time)
        assertEquals(11258.0, r.price)
        assertNull(r.km)
    }

    @Test
    fun cabifyMiddleOfDetailBringsDistance() {
        val r = HistoryParser.parse(
            listOf("Hoy, 18:07", "Cabify", "Trayecto", "Origen", "General Holley, 2325 - Providencia", "Destino",
                "Condominio Altos de Santa Amalia,", "550 - La Florida", "Duración", "81 min", "Distancia", "15.8 km",
                "Categoría", "Cabify", "Santiago", "Macul"),
            zone, today,
        )
        assertNotNull(r)
        assertEquals(ms(2026, 11, 18, 18, 7), r.time)
        assertEquals(15.8, r.km)
        assertEquals(81.0, r.minutes)
        assertNull(r.price)
    }

    @Test
    fun cabifyBottomOfDetail() {
        val r = HistoryParser.parse(
            listOf("Hoy, 18:07", "Cabify", "Para ti", "Trayecto $ 7.873", "Alta Demanda $ 3.385",
                "Total viaje para ti $ 11.258", "Retenciones $ -1.717"),
            zone, today,
        )
        assertNotNull(r)
        assertEquals(11258.0, r.price)
    }

    @Test
    fun yesterdayAndNoYear() {
        val y = HistoryParser.parse(listOf("Ayer, 9:15", "Cabify", "$ 4.500 para ti", "Trayecto"), zone, today)
        assertEquals(ms(2026, 11, 17, 9, 15), y?.time)
        // 20 dic sin año visto el 18 nov = diciembre del año pasado.
        val d = HistoryParser.parse(listOf("Detalles del viaje", "UberX • 20 dic • 22:10", "5.000 CLP", "4.2 km"), zone, today)
        assertEquals(ms(2025, 12, 20, 22, 10), d?.time)
    }

    @Test
    fun otherScreensAreIgnored() {
        // Solicitud nueva de Cabify (lista), mapa de Uber y ganancias sin detalle.
        assertNull(HistoryParser.parse(listOf("Cabify", "$ 3.855 para ti", "14 min · 4 km", "17 min · 5 km", "Aceptar"), zone, today))
        assertNull(HistoryParser.parse(listOf("Precios dinámicos ahora", "Oportunidades", "Ganancias"), zone, today))
    }

    @Test
    fun ocrSeparatorsInUberDate() {
        for (line in listOf("Uber X . 16 nov 2026 . 10:59", "UberX e 16 nov 2026 e 10:59", "Uber X - 16 nov. 2026 - 10.59", "Uber X 16 nov 2026 10:59")) {
            val r = HistoryParser.parse(listOf("Detalles del viaje", line, "2.102 CLP", "3.63 km"), zone, today)
            assertEquals(ms(2026, 11, 16, 10, 59), r?.time, line)
        }
    }
}
