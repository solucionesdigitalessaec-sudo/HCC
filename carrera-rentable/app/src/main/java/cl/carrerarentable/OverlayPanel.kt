package cl.carrerarentable

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cl.carrerarentable.core.Evaluation
import cl.carrerarentable.core.Money
import cl.carrerarentable.core.Payment
import cl.carrerarentable.core.Rules
import kotlin.math.abs

/**
 * Panel flotante sobre la app de conductor. Usa una ventana de accesibilidad, así que no
 * necesita el permiso "mostrar sobre otras apps". Tocar = cerrar; arrastrar = moverlo.
 */
class OverlayPanel(
    private val service: AccessibilityService,
    /** Se llama cuando el conductor cierra el panel (o se cierra por tiempo). */
    private val onDismissed: () -> Unit = {},
) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide(); onDismissed() }
    private var view: View? = null

    val isShowing get() = view != null

    fun show(ev: Evaluation, rules: Rules, hideAfterSeconds: Int) {
        hide()
        val metrics = service.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.95).toInt(),
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

    /** Rectángulo en pantalla del panel visible, o null si no se muestra. */
    fun screenBounds(): android.graphics.Rect? {
        val v = view ?: return null
        if (v.width == 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
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
                MotionEvent.ACTION_UP -> if (moved) SettingsStore.setOverlayY(service, lp.y) else { hide(); onDismissed() }
            }
            true
        }
    }

    // ---------- diseño (ver carrera-rentable/docs/panel-propuesto.png) ----------

    private val green = Color.parseColor("#4ADE80")
    private val red = Color.parseColor("#F87171")
    private val white = Color.WHITE
    private val muted = Color.parseColor("#9CA3AF")

    private fun build(ev: Evaluation, rules: Rules): View {
        val o = ev.offer
        val radius = dp(22).toFloat()
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F5111827"), 22)
            clipToOutline = true
            elevation = dp(8).toFloat()
        }

        // 1. Franja de color: veredicto + valor por km en grande
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (ev.profitable) "#16A34A" else "#DC2626"))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        header.addView(text(if (ev.profitable) "✅ RENTABLE" else "❌ NO RENTABLE", 30f, white, bold = true).apply {
            maxLines = 1
        })
        header.addView(text("", 50f, white, bold = true).apply {
            val main = Money.clp(ev.perKm)
            text = SpannableString("$main/km").apply {
                setSpan(RelativeSizeSpan(0.5f), main.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            includeFontPadding = false
        })
        if (!ev.paymentOk) {
            // Solo tarjeta: el motivo va en grande, debajo del $/km.
            val why = if (o.payment == Payment.CASH) "💵 PAGO EN EFECTIVO" else "⚠️ NO SE VE PAGO CON TARJETA"
            header.addView(text(why, 18f, white, bold = true).apply { maxLines = 1 })
        } else {
            val pay = if (o.payment == Payment.CARD) "💳 Tarjeta · " else ""
            val sub = if (ev.perKmOk) "${pay}mínimo ${Money.clp(rules.minPerKm)}/km"
            else "${pay}faltan ${Money.clp(rules.minPerKm * o.distanceKm - o.price)} para llegar a ${Money.clp(rules.minPerKm)}/km"
            header.addView(text(sub, 14f, white).apply { alpha = 0.92f; maxLines = 1 })
        }
        root.addView(header)

        // 2. Cuatro cuadros con los datos de la carrera
        val hourColor = when {
            ev.perHour == null -> muted
            rules.minPerHour <= 0 -> white
            ev.perHourOk -> green
            else -> red
        }
        val legsHint = if (o.legs.size >= 2) o.legs.joinToString(" + ") { Money.decimal(it.km) } + " km" else null
        val netHint = if (rules.costPerKm > 0) "te quedan ${Money.clp(ev.net)}" else null

        val grid = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), 0)
        }
        grid.addView(row(
            cell("PAGAN", Money.clp(o.price), white, netHint),
            cell("POR HORA", ev.perHour?.let { Money.clp(it) } ?: "sin dato", hourColor),
        ))
        grid.addView(row(
            cell("KM A MANEJAR", "${Money.decimal(o.distanceKm)} km", white, legsHint),
            cell("MINUTOS", o.durationMin?.let { "${Math.round(it)} min" } ?: "sin dato", white),
        ))
        root.addView(grid)

        root.addView(text("toca para cerrar · arrastra para mover", 11f, muted).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        return root
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = t
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun row(vararg cells: View) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, 0)
        cells.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
    }

    private fun cell(label: String, value: String, color: Int, hint: String? = null) =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#1F2937"), 12)
            setPadding(dp(10), dp(6), dp(10), dp(8))
            addView(TextView(service).apply {
                text = label; setTextColor(muted); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(service).apply {
                text = value; setTextColor(color); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            if (hint != null) addView(TextView(service).apply { text = hint; setTextColor(muted); textSize = 12f })
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
}
