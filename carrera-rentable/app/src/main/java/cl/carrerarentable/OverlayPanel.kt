package cl.carrerarentable

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cl.carrerarentable.core.Evaluation
import cl.carrerarentable.core.Money
import cl.carrerarentable.core.Rules
import kotlin.math.abs

/**
 * Panel flotante sobre la app de conductor. Usa una ventana de accesibilidad, así que no
 * necesita el permiso "mostrar sobre otras apps". Tocar = cerrar; arrastrar = moverlo.
 */
class OverlayPanel(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var view: View? = null

    val isShowing get() = view != null

    fun show(ev: Evaluation, rules: Rules, hideAfterSeconds: Int) {
        hide()
        val metrics = service.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = SettingsStore.overlayY(service)
        }
        val v = build(ev, rules)
        attachDrag(v, lp)
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
        if (hideAfterSeconds > 0) handler.postDelayed(hideRunnable, hideAfterSeconds * 1000L)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View, lp: WindowManager.LayoutParams) {
        var startY = 0f
        var startLp = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startLp = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - startY).toInt()
                    if (abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.y = (startLp + dy).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) SettingsStore.setOverlayY(service, lp.y) else hide()
            }
            true
        }
    }

    // ---------- diseño ----------

    private val green = Color.parseColor("#22C55E")
    private val red = Color.parseColor("#EF4444")
    private val white = Color.WHITE
    private val muted = Color.parseColor("#9CA3AF")

    private fun build(ev: Evaluation, rules: Rules): View {
        val o = ev.offer
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F0111827"), 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // Encabezado: veredicto + $/km
        val verdict = if (ev.profitable) "✅ RENTABLE" else "❌ NO RENTABLE"
        root.addView(TextView(service).apply {
            text = "$verdict   ${Money.clp(ev.perKm)}/km"
            setTextColor(white)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(if (ev.profitable) Color.parseColor("#15803D") else Color.parseColor("#B91C1C"), 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })

        val hourColor = when {
            ev.perHour == null -> muted
            rules.minPerHour <= 0 -> white
            ev.perHourOk -> green
            else -> red
        }
        val legs = if (o.legs.size >= 2)
            o.legs.joinToString(" + ") { Money.decimal(it.km) } + " km"
        else "recogida + viaje"

        root.addView(row(
            cell("Valor km", "${Money.clp(ev.perKm)}/km", if (ev.perKmOk) green else red),
            cell("Por hora", ev.perHour?.let { "${Money.clp(it)}/h" } ?: "sin dato", hourColor),
        ))
        root.addView(row(
            cell("Pagan", Money.clp(o.price), white),
            cell("Km a manejar", "${Money.decimal(o.distanceKm)} km", white, legs),
        ))
        root.addView(row(
            cell("Minutos", o.durationMin?.let { "${Math.round(it)} min" } ?: "sin dato", white),
            if (rules.costPerKm > 0)
                cell("Te queda", Money.clp(ev.net), if (ev.net > 0) green else red,
                    ev.netPerHour?.let { "${Money.clp(it)}/h neto" })
            else
                cell("Faltan", if (ev.perKmOk) "—" else Money.clp(rules.minPerKm * o.distanceKm - o.price),
                    if (ev.perKmOk) muted else red, "para llegar a ${Money.clp(rules.minPerKm)}/km"),
        ))

        root.addView(TextView(service).apply {
            text = "Mínimo ${Money.clp(rules.minPerKm)}/km" +
                (if (rules.minPerHour > 0) " · ${Money.clp(rules.minPerHour)}/h" else "") +
                " · toca para cerrar, arrastra para mover"
            setTextColor(muted)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        return root
    }

    private fun row(vararg cells: View) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, 0)
        cells.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
    }

    private fun cell(label: String, value: String, color: Int, hint: String? = null) =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#1F2937"), 10)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(TextView(service).apply { text = label; setTextColor(muted); textSize = 11f })
            addView(TextView(service).apply {
                text = value; setTextColor(color); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            })
            if (hint != null) addView(TextView(service).apply { text = hint; setTextColor(muted); textSize = 10f })
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
}
