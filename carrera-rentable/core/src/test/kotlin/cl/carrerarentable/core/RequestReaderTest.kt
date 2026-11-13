package cl.carrerarentable.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lectura estricta: solo la tarjeta de una solicitud real abre el panel. */
class RequestReaderTest {

    private val uberRequest = listOf(
        "Santiago", "Lo Barnechea", "Uber Priority", "Exclusivo", "CLP9,518", "CLP358/km est.",
        "+CLP1,427.00 incluido para", "4 min • 1.6 km", "Avenida Ossa, Metro Plaza Egaña, La Reina",
        "Tarjeta de crédito verifica...", "Pago con tarjeta", "5.00", "MARCO ANTONIO",
        "29 min • 25.0 km", "Calle Pedro Riveros 1618, Quilicura", "Aceptar", "Codegua",
    )

    @Test
    fun uberRequestIsRead() {
        val o = OfferParser.parseRequest(uberRequest)
        assertNotNull(o)
        assertEquals(9518.0, o.price)
        assertTrue(kotlin.math.abs(o.distanceKm - 26.6) < 0.01)
        assertEquals(Payment.CARD, o.payment)
        val ev = ProfitCalculator.evaluate(o, Rules(minPerKm = 600.0))
        assertTrue(ev.paymentOk)
        assertFalse(ev.profitable) // $358/km
    }

    @Test
    fun didiRequestIsRead() {
        val o = OfferParser.parseRequest(
            listOf(
                "Rechazo permitido", "Toca para aceptar la solicitud.", "Pago con tarjeta", "$1.847",
                "$625 de tarifa base dinámica", "$96 de tarifa de gasolina", "yeri · 4,91 · 46 viajes",
                "Tarjeta bancaria verificada", "7 min (3,7 km)", "Avenida Quilín 4931, Macul",
                "5 min (2,4 km)", "Juvenal Hernández Jaque 4821, Macul",
            )
        )
        assertNotNull(o)
        assertEquals(1847.0, o.price)
        assertEquals(Payment.CARD, o.payment)
    }

    @Test
    fun pickupScreenDuringTripIsIgnored() {
        // Captura 1 del conductor: ir a buscar a la pasajera (un solo tramo, sin "Aceptar").
        assertNull(
            OfferParser.parseRequest(
                listOf("Navegar", "4 min 0.8 km", "PIN obligatorio", "Carolina", "Iniciar UberX", "$36.510")
            )
        )
    }

    @Test
    fun uberWarningDialogWithAcceptButtonIsIgnored() {
        // Captura 2: aviso de Uber con botón "Aceptar" pero sin tarifa ni tramos.
        assertNull(
            OfferParser.parseRequest(
                listOf(
                    "La información del usuario es solo para usarse durante el viaje",
                    "Tomar capturas de pantalla o compartir direcciones fuera de la App infringe los términos",
                    "4 min 0.7 km", "Aceptar",
                )
            )
        )
    }

    @Test
    fun earningsTabIsIgnored() {
        assertNull(
            OfferParser.parseRequest(
                listOf("Ganancias", "CLP45,300", "Esta semana", "CLP36,510", "12 viajes", "8 h 20 min en línea", "142.5 km")
            )
        )
    }

    @Test
    fun priceBelowTheLegsIsNotAccepted() {
        assertNull(OfferParser.parseRequest(listOf("4 min • 1.6 km", "29 min • 25.0 km", "CLP9,518", "Aceptar")))
    }

    @Test
    fun cashRideIsNeverProfitable() {
        val o = OfferParser.parseRequest(
            listOf("$12.000", "Efectivo", "3 min (1,0 km)", "10 min (5,0 km)", "Aceptar")
        )
        assertNotNull(o)
        assertEquals(Payment.CASH, o.payment)
        val ev = ProfitCalculator.evaluate(o, Rules(minPerKm = 600.0)) // $2.000/km
        assertTrue(ev.perKmOk)
        assertFalse(ev.paymentOk)
        assertFalse(ev.profitable)
        // Si el conductor apaga "solo tarjeta", sí es rentable.
        assertTrue(ProfitCalculator.evaluate(o, Rules(minPerKm = 600.0, cardOnly = false)).profitable)
    }
}
