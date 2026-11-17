package cl.carrerarentable

import android.content.Context
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
 * Panel flotante sobre la app de conductor (permiso "Mostrar sobre otras apps").
 * Tocar = cerrar; arrastrar = moverlo.
 */
class OverlayPanel(
    private val service: Context,
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // WATCH_OUTSIDE_TOUCH: un toque en cualquier otra parte de la pantalla también cierra el panel
            // (y ese toque igual llega a la app de abajo, por ejemplo al botón Aceptar).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = SettingsStore.overlayY(service)
        }
        val v = build(ev, rules)
        attachDrag(v, lp)
        runCatching { wm.addView(v, lp) }.onSuccess { view = v; updateBounds(v) }
        if (hideAfterSeconds > 0) handler.postDelayed(hideRunnable, hideAfterSeconds * 1000L)
    }

    /** Rectángulo en pantalla del panel visible (se puede leer desde otro hilo), o null si no se muestra. */
    @Volatile private var bounds: android.graphics.Rect? = null

    fun screenBounds(): android.graphics.Rect? = bounds

    private fun updateBounds(v: View) {
        v.post {
            if (view !== v || v.width == 0) return@post
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            bounds = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        bounds = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View, lp: WindowManager.LayoutParams) {
        var startY = 0f
        var startLp = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_OUTSIDE -> { hide(); onDismissed() }
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startLp = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - startY).toInt()
                    if (abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.y = (startLp + dy).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, lp) }
                        updateBounds(v)
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) SettingsStore.setOverlayY(service, lp.y) else { hide(); onDismissed() }
            }
            true
        }
    }

    // ---------- diseño compacto ----------
    // Importante: ScreenFilter reconoce este panel en las capturas por "RENTABLE" (arriba) y
    // "POR HORA" (fila de etiquetas, lo más abajo). Si cambias esos textos, actualízalo también.

    private val green = Color.parseColor("#4ADE80")
    private val red = Color.parseColor("#F87171")
    private val white = Color.WHITE
    private val muted = Color.parseColor("#9CA3AF")

    private fun build(ev: Evaluation, rules: Rules): View {
        val o = ev.offer
        val radius = dp(18).toFloat()
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#F5111827"), 18)
            clipToOutline = true
            elevation = dp(8).toFloat()
        }

        // 1. Franja de color: veredicto a la izquierda, valor por km a la derecha (una sola línea)
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (ev.profitable) "#16A34A" else "#DC2626"))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        val top = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(if (ev.profitable) "✅ RENTABLE" else "❌ NO RENTABLE", 20f, white, bold = true).apply {
            maxLines = 1
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text("", 34f, white, bold = true).apply {
            val main = Money.clp(ev.perKm)
            text = SpannableString("$main/km").apply {
                setSpan(RelativeSizeSpan(0.5f), main.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            includeFontPadding = false
            maxLines = 1
        })
        header.addView(top)

        val sub = when {
            !ev.paymentOk -> if (o.payment == Payment.CASH) "💵 PAGO EN EFECTIVO" else "⚠️ NO SE VE PAGO CON TARJETA"
            else -> {
                val pay = if (o.payment == Payment.CARD) "💳 Tarjeta · " else ""
                val net = if (rules.costPerKm > 0) " · te quedan ${Money.clp(ev.net)}" else ""
                if (ev.perKmOk) "${pay}mínimo ${Money.clp(rules.minPerKm)}/km$net"
                else "${pay}faltan ${Money.clp(rules.minPerKm * o.distanceKm - o.price)} para ${Money.clp(rules.minPerKm)}/km$net"
            }
        }
        header.addView(text(sub, if (ev.paymentOk) 12f else 15f, white, bold = !ev.paymentOk).apply {
            alpha = 0.95f; maxLines = 1
            gravity = Gravity.START
        })
        root.addView(header)

        // 2. Una sola fila con los cuatro datos: valor grande arriba, etiqueta chica abajo
        val hourColor = when {
            ev.perHour == null -> muted
            rules.minPerHour <= 0 -> white
            ev.perHourOk -> green
            else -> red
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        listOf(
            cell(Money.clp(o.price), "PAGAN", white),
            cell(ev.perHour?.let { Money.clp(it) } ?: "—", "POR HORA", hourColor),
            cell("${Money.decimal(o.distanceKm)} km", "KM", white),
            cell(o.durationMin?.let { "${Math.round(it)} min" } ?: "—", "MINUTOS", white),
        ).forEachIndexed { i, c ->
            row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        root.addView(row)
        return root
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = t
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun cell(value: String, label: String, color: Int) =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.parseColor("#1F2937"), 10)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(TextView(service).apply {
                text = value; setTextColor(color); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; gravity = Gravity.CENTER
            })
            addView(TextView(service).apply {
                text = label; setTextColor(muted); textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; gravity = Gravity.CENTER
            })
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
}
