package cl.carrerarentable

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import cl.carrerarentable.core.Ledger
import cl.carrerarentable.core.OfferParser
import cl.carrerarentable.core.Payment
import cl.carrerarentable.core.ProfitCalculator
import cl.carrerarentable.core.RideAppGuess
import cl.carrerarentable.core.RideOffer
import cl.carrerarentable.core.TripStateDetector

/**
 * Recibe el texto reconocido en cada captura de pantalla (ver ShiftService), decide si hay una
 * solicitud real y muestra, actualiza u oculta el panel flotante. Todo se llama en el hilo principal.
 */
object OfferController {

    private var panel: OverlayPanel? = null
    private lateinit var app: Context

    private var lastSignature: String? = null
    private var lastOfferAt = 0L
    /** Solicitud que todavía no se confirma (debe repetirse en la captura siguiente). */
    private var pendingSignature: String? = null
    /** Tarifa de la solicitud que muestra el panel. */
    private var shownPrice: Double? = null
    /** Tarifa de la solicitud cuyo panel cerró el conductor (no se vuelve a mostrar mientras siga en pantalla). */
    private var dismissedPrice: Double? = null

    /** Última solicitud vista: si después aparece la pantalla de "ir a buscar al pasajero", se anota. */
    /**
     * inTrip: la solicitud llegó estando en otro viaje (la pantalla de viaje ya se veía). Ahí la pantalla
     * de viaje sigue visible aunque la solicitud se rechace, así que solo se anota con una señal clara
     * de "siguiente viaje".
     */
    private class Candidate(
        val offer: RideOffer, val app: String, val signature: String, var lastSeen: Long,
        val inTrip: Boolean, var logged: Boolean = false, var reported: Boolean = false,
    )
    /** Última vez que se vio la pantalla de un viaje en curso (ir a buscar / llevar al pasajero). */
    private var lastTripScreenAt = 0L
    private var candidate: Candidate? = null
    /** Cuánto tiempo después de ver la solicitud se acepta la pantalla de recogida como "la aceptó". */
    private const val ACCEPT_WINDOW_MS = 90_000L

    private fun panel(c: Context): OverlayPanel {
        if (!::app.isInitialized) app = c.applicationContext
        return panel ?: OverlayPanel(app) {
            // El conductor cerró el panel: esa misma solicitud no vuelve a abrirlo.
            dismissedPrice = shownPrice
        }.also { panel = it }
    }

    /** ¿Se puede dibujar el panel sobre otras apps? (permiso "Mostrar sobre otras apps"). */
    fun canDraw(c: Context) = Settings.canDrawOverlays(c)

    /** Zona de la pantalla que ocupa el panel (para taparla en la captura). Se puede leer desde otro hilo. */
    fun overlayBounds(): android.graphics.Rect? = panel?.screenBounds()

    /**
     * Texto de una captura. Para evitar "carreras fantasma":
     *  - lectura estricta de la solicitud (ver OfferParser.parseRequest),
     *  - la misma solicitud debe leerse igual en dos capturas seguidas antes de mostrarla.
     * Devuelve true si hay una solicitud en pantalla.
     */
    fun onScreenText(c: Context, lines: List<String>): Boolean {
        if (HistoryImporter.active) {
            // Importando historial: solo se leen detalles de viajes hechos, sin panel.
            HistoryImporter.onLines(c, lines)
            return false
        }
        val p = panel(c)
        val offer = if (lines.isEmpty()) null else OfferParser.parseRequest(lines)
        if (offer == null) {
            pendingSignature = null
            checkAccepted(c, lines)
            handleOffer(c, null, lines)
            return false
        }
        val signature = signatureOf(offer)
        rememberCandidate(offer, lines, signature)
        val alreadyShown = signature == lastSignature && p.isShowing
        if (!alreadyShown && signature != pendingSignature) {
            pendingSignature = signature
            lastOfferAt = SystemClock.elapsedRealtime() // no ocultar el panel actual mientras tanto
            return true
        }
        pendingSignature = null
        handleOffer(c, offer, lines)
        return true
    }

    private fun signatureOf(o: RideOffer) = "${o.price}|${o.distanceKm}|${o.durationMin}"

    private fun rememberCandidate(offer: RideOffer, lines: List<String>, signature: String) {
        val now = SystemClock.elapsedRealtime()
        val cur = candidate
        // Se compara la tarifa (minutos y km pueden leerse un poco distinto entre capturas).
        if (cur != null && kotlin.math.abs(cur.offer.price - offer.price) < 1.0) {
            cur.lastSeen = now
        } else {
            // La empresa del turno manda; si no hay, se adivina por la pantalla.
            candidate = Candidate(
                offer, ShiftService.currentApp ?: RideAppGuess.from(lines), signature, now,
                inTrip = lastTripScreenAt > 0 && now - lastTripScreenAt < 30_000,
            )
        }
    }

    /** Si justo después de una solicitud aparece la pantalla de recogida, se anota en la bitácora. */
    private fun checkAccepted(c: Context, lines: List<String>) {
        val now = SystemClock.elapsedRealtime()
        val tripScreen = TripStateDetector.looksAccepted(lines)
        val wasInTripBefore = lastTripScreenAt
        if (tripScreen) lastTripScreenAt = now
        val cand = candidate ?: return
        if (now - cand.lastSeen > ACCEPT_WINDOW_MS) { candidate = null; return }
        if (cand.logged || !SettingsStore.autoLog(c)) return
        if (!tripScreen) return
        if (cand.inTrip && !TripStateDetector.looksNextTripAccepted(lines)) {
            // Solicitud que llegó durante otro viaje: sin señal de "siguiente viaje" no se anota
            // (lo más probable es que se haya dejado pasar). Se puede agregar a mano.
            if (!cand.reported && wasInTripBefore != 0L) {
                cand.reported = true
                Diag.ocr(c, "solicitud durante un viaje: no se anotó (sin señal de siguiente viaje)", lines)
            }
            return
        }
        cand.logged = true
        val trip = Ledger.tripFromOffer(cand.offer, cand.app, System.currentTimeMillis())
        Thread { TripLog.get(c).add(trip) }.start()
        Diag.ocr(c, "carrera anotada en la bitácora: ${cand.app} ${cl.carrerarentable.core.Money.clp(trip.price)}", lines)
    }

    private fun handleOffer(c: Context, offer: RideOffer?, lines: List<String>) {
        val p = panel(c)
        val now = SystemClock.elapsedRealtime()
        if (offer == null) {
            // Si la solicitud cerrada ya no está en pantalla, una nueva con la misma tarifa sí se muestra.
            if (dismissedPrice != null && now - lastOfferAt > 3000) dismissedPrice = null
            // Por defecto el panel queda hasta que el conductor lo toque; solo se oculta solo si configuró segundos.
            if (SettingsStore.hideSeconds(c) > 0 && p.isShowing && now - lastOfferAt > 2500) {
                p.hide()
                lastSignature = null
            }
            return
        }

        lastOfferAt = now
        // Misma solicitud que el conductor ya cerró: no reabrir el panel.
        dismissedPrice?.let { if (kotlin.math.abs(it - offer.price) < 1.0) return else dismissedPrice = null }
        val signature = signatureOf(offer)
        if (signature == lastSignature && p.isShowing) return
        lastSignature = signature
        shownPrice = offer.price

        SettingsStore.setLastText(c, OfferParser.normalize(lines).joinToString("\n"))
        val rules = SettingsStore.rules(c)
        // En Cabify el conductor desactiva el efectivo: si no se ve la forma de pago, se toma como tarjeta.
        val cabify = ShiftService.currentApp == "Cabify" ||
            lines.any { it.contains("cabify", ignoreCase = true) || it.contains("para ti", ignoreCase = true) }
        val adjusted = if (cabify && offer.payment == Payment.UNKNOWN) offer.copy(payment = Payment.CARD) else offer
        val evaluation = ProfitCalculator.evaluate(adjusted, rules)
        if (!canDraw(c)) {
            Diag.ocr(c, "solicitud detectada, pero falta el permiso \"Mostrar sobre otras apps\"")
            return
        }
        p.show(evaluation, rules, SettingsStore.hideSeconds(c))
        if (SettingsStore.vibrate(c)) buzz(c, evaluation.profitable)
        if (evaluation.profitable && SettingsStore.sound(c)) Alerts.playProfitable()
    }

    /** Panel de ejemplo para comprobar el permiso y cómo se ve. */
    fun showTestPanel(c: Context): Boolean {
        if (!canDraw(c)) return false
        val rules = SettingsStore.rules(c)
        val offer = OfferParser.parse("CLP9,518\n4 min • 1.6 km\nPago con tarjeta\n29 min • 25.0 km") ?: return false
        panel(c).show(ProfitCalculator.evaluate(offer, rules), rules, 8)
        if (SettingsStore.vibrate(c)) buzz(c, false)
        return true
    }

    fun hide() {
        panel?.hide()
        lastSignature = null
        pendingSignature = null
    }

    private fun buzz(c: Context, good: Boolean) {
        val v = c.getSystemService(Vibrator::class.java) ?: return
        val effect = if (good) VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
        runCatching { v.vibrate(effect) }
    }
}
