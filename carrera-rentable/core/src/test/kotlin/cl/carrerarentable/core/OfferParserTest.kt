package cl.carrerarentable.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfferParserTest {

    private fun near(expected: Double, actual: Double?, eps: Double = 0.01) {
        assertNotNull(actual)
        assertTrue(kotlin.math.abs(expected - actual) < eps, "esperado $expected, obtenido $actual")
    }

    @Test
    fun uberCardFromScreenshot() {
        val offer = OfferParser.parse(
            listOf(
                "CLP45,300",            // ganancias del día arriba en la pantalla
                "UberX",
                "CLP6,980",
                "CLP397/km est.",
                "Tarjeta de crédito verificada",
                "+CLP400.00 incluido",
                "9 min (3.6 km)",
                "Calle Bellavista, Barrio Bellavista, Recoleta",
                "22 min (14.0 km)",
                "Av. Teniente Cruz 701, Pudahuel",
                "Aceptar",
            )
        )
        assertNotNull(offer)
        assertEquals(6980.0, offer.price)
        near(17.6, offer.distanceKm)
        near(31.0, offer.durationMin)
        assertEquals(2, offer.legs.size)
    }

    @Test
    fun uberPriorityRealScreenshot() {
        val offer = OfferParser.parse(
            listOf(
                "Uber Priority", "Exclusivo", "CLP9,518", "CLP358/km est.",
                "+CLP1,427.00 incluido para",
                "4 min • 1.6 km", "Avenida Ossa, Metro Plaza Egaña, La Reina",
                "Tarjeta de crédito verifica…", "Pago con tarjeta", "5.00", "MARCO ANTONIO",
                "29 min • 25.0 km", "Calle Pedro Riveros 1618, Quilicura", "Aceptar",
            )
        )
        assertNotNull(offer)
        assertEquals(9518.0, offer.price)
        near(26.6, offer.distanceKm)
        near(33.0, offer.durationMin)
        assertFalse(ProfitCalculator.evaluate(offer, Rules()).profitable)
    }

    @Test
    fun didiRealScreenshotWithFareBreakdown() {
        val offer = OfferParser.parse(
            listOf(
                "Rechazo permitido", "Toca para aceptar la solicitud.", "Pago con tarjeta", "$1.847",
                "$625 de tarifa base dinámica", "$96 de tarifa de gasolina",
                "yeri · 4,91 · 46 viajes", "Tarjeta bancaria verificada",
                "7 min (3,7 km)", "Avenida Quilín 4931, Macul",
                "5 min (2,4 km)", "Juvenal Hernández Jaque 4821, Macul",
            )
        )
        assertNotNull(offer)
        assertEquals(1847.0, offer.price)
        near(6.1, offer.distanceKm)
        near(12.0, offer.durationMin)
    }

    @Test
    fun ocrMisreadsDollarAsS() {
        val offer = OfferParser.parse(listOf("S1.847", "7 min (3,7 km)", "5 min (2,4 km)"))
        assertNotNull(offer)
        assertEquals(1847.0, offer.price)
    }

    @Test
    fun didiDotThousandsAndCommaDecimals() {
        val offer = OfferParser.parse(listOf("$5.803", "3,2 km (8 min)", "12,1 km (19 min)", "Aceptar"))
        assertNotNull(offer)
        assertEquals(5803.0, offer.price)
        near(15.3, offer.distanceKm)
        near(27.0, offer.durationMin)
    }

    @Test
    fun separatorFormatAndMeters() {
        val offer = OfferParser.parse("$ 3.500\n5 min · 800 m\n1 h 5 min · 40 km")
        assertNotNull(offer)
        assertEquals(3500.0, offer.price)
        near(40.8, offer.distanceKm)
        near(70.0, offer.durationMin)
    }

    @Test
    fun looseTokensAreSummed() {
        val offer = OfferParser.parse(listOf("Cabify", "$12.400", "A 2,5 km", "6 min", "Viaje de 15 km", "25 min"))
        assertNotNull(offer)
        assertEquals(12400.0, offer.price)
        near(17.5, offer.distanceKm)
        near(31.0, offer.durationMin)
    }

    @Test
    fun containerDescriptionDoesNotDoubleCount() {
        val offer = OfferParser.parse(
            listOf("CLP6,980 9 min (3.6 km) 22 min (14.0 km)", "CLP6,980", "9 min (3.6 km)", "22 min (14.0 km)")
        )
        assertNotNull(offer)
        near(17.6, offer.distanceKm)
    }

    @Test
    fun noOfferWithoutKmOrPrice() {
        assertNull(OfferParser.parse(listOf("Estás conectado", "CLP45,300")))
        assertNull(OfferParser.parse(listOf("9 min (3.6 km)")))
    }

    @Test
    fun evaluationAgainst700PerKm() {
        val bad = ProfitCalculator.evaluate(RideOffer(6980.0, 17.6, 31.0), Rules(minPerKm = 700.0))
        near(396.59, bad.perKm)
        near(13509.68, bad.perHour)
        assertFalse(bad.profitable)

        val good = ProfitCalculator.evaluate(RideOffer(9000.0, 12.0, 25.0), Rules(minPerKm = 700.0, costPerKm = 100.0))
        near(750.0, good.perKm)
        near(7800.0, good.net)
        assertTrue(good.profitable)

        val slow = ProfitCalculator.evaluate(RideOffer(9000.0, 12.0, 60.0), Rules(minPerKm = 700.0, minPerHour = 12000.0))
        assertTrue(slow.perKmOk)
        assertFalse(slow.profitable)
    }

    @Test
    fun moneyFormat() {
        assertEquals("$6.980", Money.clp(6980.4))
        assertEquals("$397", Money.clp(396.6))
        assertEquals("17,6", Money.decimal(17.6))
    }
}
