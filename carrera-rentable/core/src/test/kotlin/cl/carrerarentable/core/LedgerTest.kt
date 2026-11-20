package cl.carrerarentable.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerTest {
    private fun near(e: Double, a: Double?, eps: Double = 1.0) =
        assertTrue(a != null && kotlin.math.abs(e - a) < eps, "esperado $e, obtenido $a")

    private val car = Vehicle(fuelPricePerLiter = 1591.0, kmPerLiter = 9.6)

    @Test
    fun fuelCostPerKm() = near(165.73, car.fuelPerKm, 0.01)

    @Test
    fun cabifyTripFromScreenshot() {
        // $11.258 para ti, 15,8 km de viaje (sin recogida), retención 15,25% = $1.717.
        val trip = Trip(time = 0, app = "Cabify", price = 11258.0, pickupKm = 0.0, tripKm = 15.8, minutes = 81.0)
        val e = Ledger.earnings(trip, car, 15.25)
        near(1716.8, e.retention)
        near(2618.5, e.fuel)
        near(6922.7, e.net)
        near(8639.5, e.beforeRetention)
    }

    @Test
    fun summaryPerKmAndPerHour() {
        val trips = listOf(
            Trip(time = 0, app = "Uber", price = 2102.0, pickupKm = 1.0, tripKm = 3.63, minutes = 11.0),
            Trip(time = 0, app = "Cabify", price = 11258.0, pickupKm = 2.0, tripKm = 15.8, minutes = 81.0),
        )
        val s = Ledger.summarize(trips, car, 15.25, shiftMinutes = 180.0)
        assertEquals(2, s.trips)
        near(13360.0, s.gross)
        near(22.43, s.km, 0.01)
        near(13360 * 0.8475 - 22.43 * 165.73, s.net, 2.0)
        near(s.net / 22.43, s.netPerKm, 0.5)
        near(s.net / 3.0, s.netPerHour, 0.5)
    }

    @Test
    fun tripFromOfferSplitsPickupAndRide() {
        val o = OfferParser.parseRequest(listOf("Cabify", "$ 3.855 para ti", "14 min · 4 km", "Macul", "17 min · 5 km", "La Florida"))!!
        val t = Ledger.tripFromOffer(o, "Cabify", 123L)
        assertEquals(4.0, t.pickupKm)
        assertEquals(5.0, t.tripKm)
        assertEquals(3855.0, t.price)
    }

    @Test
    fun panelNetIncludesRetentionAndFuel() {
        val o = RideOffer(9518.0, 26.6, 33.0, payment = Payment.CARD)
        val ev = ProfitCalculator.evaluate(o, Rules(costPerKm = car.costPerKm, retentionPct = 15.25))
        near(9518 * 0.8475 - 26.6 * 165.73, ev.net, 2.0)
    }

    @Test
    fun acceptedScreenDetection() {
        // Pantalla de Uber después de aceptar (ir a buscar a la pasajera).
        assertTrue(TripStateDetector.looksAccepted(listOf("Navegar", "4 min 0.8 km", "PIN obligatorio", "Carolina", "Iniciar UberX")))
        assertTrue(TripStateDetector.looksAccepted(listOf("Ir a recoger", "Llegué")))
        // La solicitud misma (con botón Aceptar) no cuenta como aceptada.
        assertFalse(TripStateDetector.looksAccepted(listOf("CLP9,518", "4 min • 1.6 km", "Aceptar")))
        // Lista de Cabify o mapa: no.
        assertFalse(TripStateDetector.looksAccepted(listOf("Cabify", "$ 3.855 para ti", "14 min · 4 km", "Inicio", "Billetera")))
    }

    @Test
    fun nextTripAcceptance() {
        assertTrue(TripStateDetector.looksNextTripAccepted(listOf("Navegar", "Siguiente viaje: Carolina", "Iniciar UberX")))
        assertFalse(TripStateDetector.looksNextTripAccepted(listOf("Navegar", "PIN obligatorio", "Iniciar UberX")))
    }

    @Test
    fun appGuess() {
        assertEquals("Cabify", RideAppGuess.from(listOf("$ 3.855 para ti")))
        assertEquals("Uber", RideAppGuess.from(listOf("CLP9,518", "Uber Priority")))
        assertEquals("DiDi", RideAppGuess.from(listOf("DiDi", "$1.847")))
    }

    @Test
    fun csvUsesSemicolonsAndCommaDecimals() {
        val csv = LedgerCsv.build(
            listOf(Trip(time = 0, app = "Uber", price = 2102.0, pickupKm = 1.0, tripKm = 3.63, minutes = 11.0)),
            car, 15.25,
        ) { "18-11-2026" to "10:59" }
        val row = csv.lines()[1]
        assertTrue(row.startsWith("18-11-2026;10:59;Uber;2102;1,0;3,6;4,6;11;"), row)
    }
}
