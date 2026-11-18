package cl.carrerarentable

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cl.carrerarentable.core.Ledger
import cl.carrerarentable.core.LedgerCsv
import cl.carrerarentable.core.Money
import cl.carrerarentable.core.Summary
import cl.carrerarentable.core.Trip
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Bitácora de carreras aceptadas y resumen de ganancias reales (hoy / semana / mes). */
class LogActivity : Activity() {

    private enum class Period(val label: String) { HOY("Hoy"), SEMANA("Semana"), MES("Mes") }

    private var period = Period.HOY
    private lateinit var tabs: LinearLayout
    private lateinit var totals: LinearLayout
    private lateinit var summaryBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var importButton: Button
    private var shown: List<Trip> = emptyList()

    private val log by lazy { TripLog.get(this) }
    private val dayFmt = SimpleDateFormat("EEE dd/MM", Locale("es", "CL"))
    private val hourFmt = SimpleDateFormat("HH:mm", Locale.ROOT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#001220"))
            setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(TextView(context).apply {
                text = "←"; textSize = 24f; setTextColor(Color.WHITE); setPadding(dp(8), 0, dp(16), 0)
                setOnClickListener { finish() }
            })
            addView(TextView(context).apply {
                text = "Bitácora y ganancias"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            })
        }
        val scroll = ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#F9FAFB")) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#001220"))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        screen.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            }
            insets
        }
        setContentView(screen)

        // Ganancia real de hoy, la semana y el mes, siempre a la vista.
        totals = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(10)) }
        col.addView(totals)
        col.addView(note("Detalle de:"))
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(10)) }
        col.addView(tabs)
        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(summaryBox)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        actions.addView(button("+ Agregar carrera") { editTrip(null) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Exportar a Excel") { exportCsv() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        col.addView(actions)
        importButton = button("") { toggleImport() }.apply { minHeight = dp(52) }
        col.addView(importButton)

        col.addView(TextView(this).apply {
            text = "Carreras"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(16), 0, dp(4))
        })
        col.addView(note("Se anotan solas cuando aceptas durante el turno. Toca una para corregirla o borrarla."))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
    }

    override fun onResume() {
        super.onResume()
        refreshImportButton()
        refresh()
    }

    // ---------- importar historial ----------

    private fun refreshImportButton() {
        importButton.text = if (HistoryImporter.active)
            "■ Terminar importación (${HistoryImporter.imported} anotadas)"
        else "⤓ Importar carreras del historial de Uber / Cabify"
    }

    private fun toggleImport() {
        if (HistoryImporter.active) {
            val n = HistoryImporter.imported
            HistoryImporter.stop(this)
            Toast.makeText(this, "Importación terminada: $n carreras nuevas", Toast.LENGTH_LONG).show()
            refreshImportButton(); refresh()
            return
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        form.addView(TextView(this).apply {
            text = "1. Toca \"Empezar\" (si el turno no está activo, Android pide permiso para leer la pantalla).\n" +
                "2. Abre Uber › Ganancias, o Cabify › tus viajes.\n" +
                "3. Entra a cada viaje, uno por uno. En Cabify baja hasta ver la distancia.\n" +
                "4. Cada viaje anotado vibra y muestra \"Anotada\". Si abres uno dos veces, no se duplica.\n" +
                "5. Vuelve aquí y toca \"Terminar importación\".\n\n" +
                "El historial no muestra los km de recogida: se suma este promedio a cada viaje para el combustible."
            textSize = 14f
        })
        form.addView(TextView(this).apply { text = "Km de recogida promedio por viaje"; setPadding(0, dp(10), 0, 0) })
        val pickup = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Money.decimal(SettingsStore.historyPickupKm(this@LogActivity)))
        }
        form.addView(pickup)
        AlertDialog.Builder(this)
            .setTitle("Importar historial")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Empezar") { _, _ ->
                SettingsStore.setHistoryPickupKm(this, num(pickup.text.toString()) ?: 2.0)
                HistoryImporter.start()
                if (ShiftService.running) {
                    importStarted()
                } else {
                    HistoryImporter.startedShift = true
                    val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
                    @Suppress("DEPRECATION")
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun importStarted() {
        refreshImportButton()
        Toast.makeText(this, "Importando: abre el historial de Uber o Cabify y entra a cada viaje", Toast.LENGTH_LONG).show()
    }

    // ---------- períodos ----------

    private fun range(p: Period): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        when (p) {
            Period.HOY -> {}
            Period.SEMANA -> {
                // Desde el lunes.
                val back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
                c.add(Calendar.DAY_OF_MONTH, -back)
            }
            Period.MES -> c.set(Calendar.DAY_OF_MONTH, 1)
        }
        val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        return c.timeInMillis to end
    }

    private fun refresh() {
        tabs.removeAllViews()
        Period.values().forEachIndexed { i, p ->
            tabs.addView(Button(this).apply {
                text = p.label
                isAllCaps = false
                typeface = if (p == period) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (p == period) Color.WHITE else Color.parseColor("#111827"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (p == period) "#16A34A" else "#E5E7EB")); cornerRadius = dp(10).toFloat()
                }
                setOnClickListener { period = p; refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
        }

        val (from, to) = range(period)
        Thread {
            val trips = log.trips(from, to)
            val shiftMin = log.shiftMinutes(from, to)
            val vehicle = SettingsStore.vehicle(this)
            val ret = SettingsStore.retentionPct(this)
            val all = Period.values().map { p ->
                val (f, t) = range(p)
                p to Ledger.summarize(log.trips(f, t), vehicle, ret, log.shiftMinutes(f, t))
            }
            runOnUiThread { showTotals(all); show(trips, shiftMin) }
        }.start()
    }

    private fun showTotals(all: List<Pair<Period, Summary>>) {
        totals.removeAllViews()
        all.forEachIndexed { i, (p, s) ->
            totals.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = GradientDrawable().apply { setColor(Color.parseColor("#001220")); cornerRadius = dp(12).toFloat() }
                addView(TextView(context).apply {
                    text = p.label.uppercase(); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#9CA3AF")); gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = Money.clp(s.net); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
                    setTextColor(Color.parseColor(if (s.net >= 0) "#4ADE80" else "#F87171")); gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = "${s.trips} carreras"; textSize = 11f; setTextColor(Color.parseColor("#D1D5DB")); gravity = Gravity.CENTER
                })
                setOnClickListener { period = p; refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private fun show(trips: List<Trip>, shiftMinutes: Double) {
        shown = trips
        val vehicle = SettingsStore.vehicle(this)
        val ret = SettingsStore.retentionPct(this)
        val s = Ledger.summarize(trips, vehicle, ret, shiftMinutes)
        summaryBox.removeAllViews()
        summaryBox.addView(summaryCard(s))

        listBox.removeAllViews()
        if (trips.isEmpty()) {
            listBox.addView(note("Todavía no hay carreras en este período."))
            return
        }
        trips.forEach { t -> listBox.addView(tripRow(t, Ledger.earnings(t, vehicle, ret).net)) }
    }

    private fun summaryCard(s: Summary): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(Color.parseColor("#111827")); cornerRadius = dp(14).toFloat() }
        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)

        addView(TextView(context).apply {
            text = "GANANCIA REAL"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#9CA3AF"))
        })
        addView(TextView(context).apply {
            text = Money.clp(s.net); textSize = 34f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (s.net >= 0) "#4ADE80" else "#F87171"))
        })
        addView(TextView(context).apply {
            text = "${s.trips} carreras · ${Money.decimal(s.km)} km · " +
                (s.netPerKm?.let { "${Money.clp(it)}/km real" } ?: "—") +
                (s.netPerHour?.let { " · ${Money.clp(it)}/h real" } ?: "")
            textSize = 14f; setTextColor(Color.WHITE)
        })
        val lines = listOf(
            "Cobrado (para ti)" to Money.clp(s.gross),
            "Retención de impuestos" to "−" + Money.clp(s.retention),
            "Combustible" to "−" + Money.clp(s.fuel),
        ) + (if (s.otherCosts > 0) listOf("Otros costos" to "−" + Money.clp(s.otherCosts)) else emptyList()) + listOf(
            "Horas de turno" to (if (s.shiftMinutes > 0) "${Money.decimal(s.shiftMinutes / 60.0)} h" else "—"),
            "Antes de retención" to Money.clp(s.net + s.retention),
        )
        lines.forEach { (k, v) ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                addView(TextView(context).apply { text = k; textSize = 14f; setTextColor(Color.parseColor("#D1D5DB")) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply { text = v; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            })
        }
        addView(TextView(context).apply {
            text = "La retención (${Money.decimal(SettingsStore.retentionPct(this@LogActivity), 2)}%) es un pago adelantado de impuestos: " +
                "según tus ingresos del año, en la Operación Renta se puede devolver en parte o completa."
            textSize = 11f; setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, dp(8), 0, 0)
        })
    }

    private fun tripRow(t: Trip, net: Double): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(1, Color.parseColor("#E5E7EB")) }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "${dayFmt.format(Date(t.time))} ${hourFmt.format(Date(t.time))} · ${t.app}" + when (t.source) {
                    "manual" -> " · manual"; "historial" -> " · historial"; else -> ""
                }
                textSize = 12f; setTextColor(Color.parseColor("#6B7280"))
            })
            addView(TextView(context).apply {
                text = "${Money.clp(t.price)} · ${Money.decimal(t.totalKm)} km (${Money.decimal(t.pickupKm)} + ${Money.decimal(t.tripKm)})"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#111827"))
            })
        }
        addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = Money.clp(net); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (net >= 0) "#16A34A" else "#DC2626"))
        })
        setOnClickListener { editTrip(t) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
    }

    // ---------- agregar / editar ----------

    private fun editTrip(t: Trip?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun input(label: String, value: String, decimal: Boolean = true): EditText {
            form.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                setText(value); form.addView(this)
            }
        }
        fun txt(v: Double?) = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else Money.decimal(it) } ?: ""
        val app = EditText(this).apply { setText(t?.app ?: "Cabify") }
        form.addView(TextView(this).apply { text = "App (Uber, Cabify, DiDi...)" })
        form.addView(app)
        val whenFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT)
        form.addView(TextView(this).apply { text = "Fecha y hora (dd/mm/aaaa hh:mm)"; setPadding(0, dp(8), 0, 0) })
        val whenInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_DATETIME
            setText(whenFmt.format(Date(t?.time ?: System.currentTimeMillis())))
        }
        form.addView(whenInput)
        val price = input("Monto para ti ($)", txt(t?.price), decimal = false)
        val pickup = input("Km de recogida", txt(t?.pickupKm))
        val ride = input("Km del viaje", txt(t?.tripKm))
        val minutes = input("Minutos (opcional)", txt(t?.minutes))

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (t == null) "Agregar carrera" else "Corregir carrera")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Guardar") { _, _ ->
                val p = num(price.text.toString())
                if (p == null || p <= 0) {
                    Toast.makeText(this, "Falta el monto", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                val time = runCatching { whenFmt.parse(whenInput.text.toString().trim())?.time }.getOrNull()
                if (time == null) {
                    Toast.makeText(this, "Revisa la fecha y hora (ej: 18/11/2026 18:07)", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val trip = Trip(
                    id = t?.id ?: 0, time = time,
                    app = app.text.toString().trim().ifBlank { "Otra" }, price = p,
                    pickupKm = num(pickup.text.toString()) ?: 0.0, tripKm = num(ride.text.toString()) ?: 0.0,
                    minutes = num(minutes.text.toString()), source = t?.source ?: "manual",
                )
                Thread { if (t == null) log.add(trip) else log.update(trip); runOnUiThread { refresh() } }.start()
            }
            .setNegativeButton("Cancelar", null)
        if (t != null) dlg.setNeutralButton("Borrar") { _, _ ->
            AlertDialog.Builder(this).setMessage("¿Borrar esta carrera de la bitácora?")
                .setPositiveButton("Borrar") { _, _ -> Thread { log.delete(t.id); runOnUiThread { refresh() } }.start() }
                .setNegativeButton("Cancelar", null).show()
        }
        dlg.show()
    }

    // ---------- exportar ----------

    private fun exportCsv() {
        if (shown.isEmpty()) {
            Toast.makeText(this, "No hay carreras en este período", Toast.LENGTH_SHORT).show(); return
        }
        val name = "kmrentable-${period.label.lowercase()}-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv").putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("API de Activity sin AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                ShiftService.start(this, resultCode, data)
                importStarted()
            } else {
                HistoryImporter.stop(this)
                refreshImportButton()
                Toast.makeText(this, "Sin permiso para leer la pantalla", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val uri = data?.data
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || uri == null) return
        val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT)
        val csv = LedgerCsv.build(shown.sortedBy { it.time }, SettingsStore.vehicle(this), SettingsStore.retentionPct(this)) {
            dateFmt.format(Date(it)) to hourFmt.format(Date(it))
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("sin archivo")
        }.isSuccess
        Toast.makeText(this, if (ok) "Bitácora exportada" else "No se pudo guardar el archivo", Toast.LENGTH_SHORT).show()
    }

    // ---------- helpers ----------

    private fun num(s: String): Double? {
        val v = s.trim().replace(" ", "").replace("$", "")
        if (v.isEmpty()) return null
        val n = if (Regex("""\d{1,3}([.,]\d{3})+""").matches(v)) v.replace(".", "").replace(",", "") else v.replace(',', '.')
        return n.toDoubleOrNull()
    }

    private fun note(t: String) = TextView(this).apply { text = t; setTextColor(Color.GRAY); textSize = 13f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT = 51
        private const val REQ_CAPTURE = 52
    }
}
