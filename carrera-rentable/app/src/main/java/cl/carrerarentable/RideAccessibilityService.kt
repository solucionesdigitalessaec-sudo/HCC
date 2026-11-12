package cl.carrerarentable

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cl.carrerarentable.core.OfferParser
import cl.carrerarentable.core.ProfitCalculator

/**
 * Lee el texto visible de Uber / DiDi / Cabify. Cuando aparece una solicitud con tarifa y
 * kilómetros, muestra el panel flotante con el resumen. No toca nada en la otra app.
 */
class RideAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayPanel
    private val scan = Runnable { scanNow() }

    private var lastSignature: String? = null
    private var lastOfferAt = 0L
    private var lastDiagWrite = 0L

    /** Raíz de la pantalla obtenida desde el último aviso de la app de conductor. */
    private var eventRoot: AccessibilityNodeInfo? = null
    /** Textos que vienen dentro de los avisos recientes (por si no se puede recorrer la pantalla). */
    private val eventTexts = LinkedHashMap<String, Long>()
    private var eventSourceInfo = "sin datos"

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayPanel(this)
        instance = this
        Diag.serviceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        Diag.eventFrom(this, pkg)
        if (!RideApps.isRideApp(pkg)) return
        Diag.rideEvent(this, pkg)

        val now = SystemClock.elapsedRealtime()
        (event.text.orEmpty() + listOfNotNull(event.contentDescription)).forEach { t ->
            t?.toString()?.takeIf { it.isNotBlank() }?.let { eventTexts[it] = now }
        }
        eventTexts.entries.removeAll { now - it.value > 4000 }
        while (eventTexts.size > 200) eventTexts.remove(eventTexts.keys.first())

        val source = runCatching { event.source }.getOrNull()
        eventSourceInfo = if (source == null) "aviso sin contenido (source=null)" else "aviso con contenido"
        source?.let { eventRoot = topOf(it) }

        // Las apps lanzan muchos eventos seguidos: se agrupan y se lee la pantalla una sola vez.
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, 250)
    }

    private fun scanNow() {
        if (!::overlay.isInitialized) return
        val (windowCount, lines) = collectRideAppText()
        val offer = if (lines.isEmpty()) null else OfferParser.parse(lines)
        val now = SystemClock.elapsedRealtime()
        // Diagnóstico: como máximo una vez por segundo, y siempre que se detecta una oferta.
        if (offer != null || now - lastDiagWrite > 1000) {
            lastDiagWrite = now
            Diag.scan(this, windowCount, OfferParser.normalize(lines), offer != null)
            Diag.windows(this, describeWindows() + " · " + eventSourceInfo)
        }
        if (lines.isEmpty()) return
        if (offer == null) {
            // La solicitud desapareció (aceptada, rechazada o expirada).
            if (overlay.isShowing && now - lastOfferAt > 2500) {
                overlay.hide()
                lastSignature = null
            }
            return
        }

        lastOfferAt = now
        val signature = "${offer.price}|${offer.distanceKm}|${offer.durationMin}"
        if (signature == lastSignature && overlay.isShowing) return
        lastSignature = signature

        SettingsStore.setLastText(this, OfferParser.normalize(lines).joinToString("\n"))
        val rules = SettingsStore.rules(this)
        val evaluation = ProfitCalculator.evaluate(offer, rules)
        overlay.show(evaluation, rules, SettingsStore.hideSeconds(this))
        if (SettingsStore.vibrate(this)) buzz(evaluation.profitable)
        if (evaluation.profitable && SettingsStore.sound(this)) Alerts.playProfitable()
    }

    /** Junta el texto de todas las ventanas que pertenecen a una app de conductor. */
    private fun collectRideAppText(): Pair<Int, List<String>> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        fun addRoot(n: AccessibilityNodeInfo?) {
            if (n != null && roots.none { it.windowId == n.windowId }) roots += n
        }
        runCatching { windows }.getOrNull()?.forEach { w -> addRoot(runCatching { w.root }.getOrNull()) }
        addRoot(rootInActiveWindow)
        // Algunas apps no aparecen en la lista de ventanas: se usa la pantalla del último aviso.
        eventRoot?.let { r -> if (runCatching { r.refresh() }.getOrDefault(false)) addRoot(r) else eventRoot = null }

        val out = ArrayList<String>()
        var count = 0
        for (root in roots) {
            if (!RideApps.isRideApp(root.packageName?.toString())) continue
            count++
            walk(root, out, 0)
        }
        // Respaldo: el texto que venía dentro de los avisos.
        if (out.isEmpty()) out += eventTexts.keys
        return count to out
    }

    private fun topOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n = node
        repeat(60) { n = runCatching { n.parent }.getOrNull() ?: return n }
        return n
    }

    /** Resumen de las ventanas visibles para el diagnóstico: "app(tipo)" o "?(tipo)" si no se puede leer. */
    private fun describeWindows(): String {
        val ws = runCatching { windows }.getOrNull() ?: return "ventanas: no disponibles"
        return "ventanas: " + ws.joinToString(", ") { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: "?"
            val type = when (w.type) {
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "sistema"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "teclado"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "panel"
                else -> "otro"
            }
            "${pkg.substringAfterLast('.')}($type${w.title?.let { ":" + it.toString().take(20) } ?: ""})"
        }
    }

    /** Panel de ejemplo para comprobar que el servicio puede dibujar sobre la pantalla. */
    fun showTestPanel() {
        if (!::overlay.isInitialized) return
        val rules = SettingsStore.rules(this)
        val offer = OfferParser.parse("CLP9,518\n4 min • 1.6 km\n29 min • 25.0 km") ?: return
        overlay.show(ProfitCalculator.evaluate(offer, rules), rules, 8)
        if (SettingsStore.vibrate(this)) buzz(false)
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 1500) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, out, depth + 1) }
        }
    }

    private fun buzz(good: Boolean) {
        val v = getSystemService(Vibrator::class.java) ?: return
        val effect = if (good) VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
        runCatching { v.vibrate(effect) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    companion object {
        /** Servicio en ejecución (null si la accesibilidad está apagada o Android lo detuvo). */
        @Volatile var instance: RideAccessibilityService? = null
    }
}
