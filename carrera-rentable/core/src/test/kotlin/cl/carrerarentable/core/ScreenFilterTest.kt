package cl.carrerarentable.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenFilterTest {

    private fun l(text: String, top: Int) = ScreenLine(text, top, top + 30)

    /** Nuestro panel arriba y la tarjeta de Uber abajo, como en una captura real. */
    private val screen = listOf(
        l("X NO RENTABLE", 100), l("$358/km", 150), l("faltan $9.102 para llegar a $700/km", 210),
        l("PAGAN", 260), l("$9.518", 290), l("POR HORA", 260), l("$17.305", 290),
        l("KM A MANEJAR", 340), l("26,6 km", 370), l("1,6 + 25,0 km", 400), l("MINUTOS", 340), l("33 min", 370),
        l("toca para cerrar · arrastra para mover", 440),
        l("Uber Priority", 700), l("CLP9,518", 740), l("CLP358/km est.", 790),
        l("4 min • 1.6 km", 900), l("29 min • 25.0 km", 1100), l("Aceptar", 1300),
    )

    @Test
    fun ownPanelIsRemovedAndRealOfferRemains() {
        val cleaned = ScreenFilter.removeOwnPanel(screen)
        assertNotNull(cleaned)
        assertTrue(cleaned.none { it.text.contains("RENTABLE") || it.text == "26,6 km" })
        val offer = OfferParser.parse(cleaned.map { it.text })
        assertNotNull(offer)
        assertEquals(9518.0, offer.price)
        assertTrue(kotlin.math.abs(offer.distanceKm - 26.6) < 0.01)
    }

    @Test
    fun onlyOurPanelVisibleGivesNoOffer() {
        val onlyPanel = screen.take(13)
        val cleaned = ScreenFilter.removeOwnPanel(onlyPanel)
        assertNotNull(cleaned)
        assertNull(OfferParser.parse(cleaned.map { it.text }))
    }

    @Test
    fun partialPanelDiscardsTheCapture() {
        // El panel a medio cerrar: se ve el encabezado pero no el pie.
        assertNull(ScreenFilter.removeOwnPanel(screen.take(5)))
    }

    @Test
    fun mapLabelsDoNotCountWhenLegsAreRequired() {
        // OCR sin el punto separador, y textos del mapa con km y min sueltos.
        val lines = listOf(
            "Santiago", "Lo Barnechea", "5 min", "Autopista 12 km", "CLP9,518",
            "4 min 1.6 km", "Avenida Ossa", "29 min 25.0 km", "Aceptar",
        )
        val offer = OfferParser.parse(lines, requireLegs = true)
        assertNotNull(offer)
        assertEquals(9518.0, offer.price)
        assertTrue(kotlin.math.abs(offer.distanceKm - 26.6) < 0.01)
        assertTrue(kotlin.math.abs(offer.durationMin!! - 33.0) < 0.01)
        assertNull(OfferParser.parse(listOf("CLP9,518", "Autopista 12 km", "5 min", "Aceptar"), requireLegs = true))
    }

    @Test
    fun settingsTextDoesNotDiscardTheCapture() {
        val lines = listOf(l("Mínimo por hora ($) · 0 = no exigir", 100), l("Desliza para conectarte", 900), l("KMRentable", 50))
        assertEquals(3, ScreenFilter.removeOwnPanel(lines)?.size)
    }

    @Test
    fun acceptButtonIsRequired() {
        assertTrue(ScreenFilter.hasAcceptButton(listOf("$1.847", "Toca para aceptar la solicitud.")))
        assertTrue(ScreenFilter.hasAcceptButton(listOf("Aceptar")))
        assertFalse(ScreenFilter.hasAcceptButton(listOf("CLP45,300", "12 min · 5.3 km", "Navegar")))
    }
}
