package cl.carrerarentable

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cl.carrerarentable.core.Money
import cl.carrerarentable.core.ProfitCalculator
import cl.carrerarentable.core.RideOffer
import cl.carrerarentable.core.Rules

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var minKm: EditText
    private lateinit var minHour: EditText
    private lateinit var costKm: EditText
    private lateinit var hideSec: EditText
    private lateinit var vibrate: CheckBox
    private lateinit var cardOnly: CheckBox
    private lateinit var sound: CheckBox
    private lateinit var tPrice: EditText
    private lateinit var tKm: EditText
    private lateinit var tMin: EditText
    private lateinit var tResult: TextView
    private lateinit var diag: TextView
    private lateinit var shiftStatus: TextView
    private lateinit var shiftButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        col.addView(note("Versión ${version ?: "?"}"))

        // 1. Activación
        col.addView(title("1. Activar lectura de solicitudes"))
        status = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        col.addView(status)
        col.addView(note(
            "Activa \"KMRentable\" en Ajustes › Accesibilidad. Si aparece como \"Ajuste restringido\": " +
                "Ajustes › Apps › KMRentable › menú ⋮ › \"Permitir ajustes restringidos\", y vuelve a intentarlo."
        ))
        col.addView(button("Abrir ajustes de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Abrir info de la app (ajustes restringidos)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        })

        // Turno: lectura por imagen
        col.addView(title("Turno (Uber y DiDi)"))
        col.addView(note(
            "Uber y DiDi esconden su texto, así que se lee la pantalla como imagen. Al empezar, Android " +
                "pregunta si permites grabar la pantalla: elige \"Pantalla completa\" y acepta. " +
                "Necesita también la lectura de arriba activada (es la que dibuja el panel)."
        ))
        shiftStatus = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        col.addView(shiftStatus)
        shiftButton = button("") {
            if (ShiftService.running) {
                ShiftService.stop(this)
                it.postDelayed({ refreshShift() }, 500)
            } else {
                startShift()
            }
        }
        col.addView(shiftButton)

        // 2. Reglas
        col.addView(title("2. Mis reglas"))
        val r = SettingsStore.rules(this)
        minKm = field(col, "Mínimo por km ($)", r.minPerKm)
        minHour = field(col, "Mínimo por hora ($) · 0 = no exigir", r.minPerHour)
        costKm = field(col, "Mi costo por km ($, bencina + desgaste) · 0 = no descontar", r.costPerKm)
        cardOnly = CheckBox(this).apply {
            text = "Solo carreras con tarjeta (efectivo = NO RENTABLE)"
            isChecked = r.cardOnly
        }
        col.addView(cardOnly)
        hideSec = field(col, "Ocultar panel solo después de (segundos) · 0 = queda hasta que lo toques", SettingsStore.hideSeconds(this).toDouble())
        vibrate = CheckBox(this).apply {
            text = "Vibrar al llegar una solicitud (1 vibración = rentable, 2 = no)"
            isChecked = SettingsStore.vibrate(this@MainActivity)
        }
        col.addView(vibrate)
        sound = CheckBox(this).apply {
            text = "Sonido cuando la carrera es rentable"
            isChecked = SettingsStore.sound(this@MainActivity)
        }
        col.addView(sound)
        col.addView(button("Probar sonido") { Alerts.playProfitable() })
        col.addView(button("Guardar reglas") {
            saveRules()
            Toast.makeText(this, "Reglas guardadas", Toast.LENGTH_SHORT).show()
        })

        // 3. Calculadora manual
        col.addView(title("3. Calcular a mano"))
        tPrice = field(col, "Tarifa ($)", null)
        tKm = field(col, "Km totales (recogida + viaje)", null)
        tMin = field(col, "Minutos totales (opcional)", null)
        tResult = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(button("Calcular") { calculate() })
        col.addView(tResult)

        // 4. Diagnóstico
        col.addView(title("4. Si algo no funciona"))
        col.addView(button("Mostrar panel de prueba") {
            val svc = RideAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "El servicio no está corriendo: actívalo en Accesibilidad", Toast.LENGTH_LONG).show()
            } else {
                svc.showTestPanel()
            }
        })
        diag = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8)) }
        col.addView(diag)
        col.addView(note("Si no aparece el panel en Uber/DiDi/Cabify: deja llegar una solicitud, vuelve aquí y toca \"Copiar diagnóstico\". Pégalo en el chat."))
        col.addView(button("Copiar diagnóstico") {
            val t = Diag.report(this) + "\n\n--- Último texto leído ---\n" + Diag.lastText(this).ifBlank { SettingsStore.lastText(this) }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnostico", t))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "● Lectura ACTIVA" else "● Lectura DESACTIVADA"
        status.setTextColor(Color.parseColor(if (on) "#16A34A" else "#DC2626"))
        if (on && RideAccessibilityService.instance == null) {
            status.text = "● Activada, pero el servicio no está corriendo: apágala y préndela en Accesibilidad"
            status.setTextColor(Color.parseColor("#D97706"))
        }
        diag.text = Diag.report(this)
        refreshShift()
    }

    override fun onPause() {
        saveRules()
        super.onPause()
    }

    private fun refreshShift() {
        val on = ShiftService.running
        shiftStatus.text = if (on) "● Turno ACTIVO (leyendo la pantalla)" else "● Turno detenido"
        shiftStatus.setTextColor(Color.parseColor(if (on) "#16A34A" else "#6B7280"))
        shiftButton.text = if (on) "■ Terminar turno" else "▶ Empezar turno"
    }

    private fun startShift() {
        if (RideAccessibilityService.instance == null) {
            Toast.makeText(this, "Primero activa KMRentable en Accesibilidad (paso 1)", Toast.LENGTH_LONG).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Con o sin permiso de notificaciones, el turno puede funcionar.
        if (requestCode == REQ_NOTIF) {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
    }

    @Deprecated("API de Activity sin AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Sin permiso para leer la pantalla, el turno no empezó", Toast.LENGTH_LONG).show()
            return
        }
        ShiftService.start(this, resultCode, data)
        Toast.makeText(this, "Turno iniciado: abre Uber o DiDi", Toast.LENGTH_SHORT).show()
        shiftButton.postDelayed({ refreshShift() }, 800)
    }

    private fun saveRules() {
        SettingsStore.saveRules(this, Rules(num(minKm) ?: 700.0, num(minHour) ?: 0.0, num(costKm) ?: 0.0, cardOnly.isChecked))
        SettingsStore.setHideSeconds(this, (num(hideSec) ?: 0.0).toInt())
        SettingsStore.setVibrate(this, vibrate.isChecked)
        SettingsStore.setSound(this, sound.isChecked)
    }

    private fun calculate() {
        val price = num(tPrice)
        val km = num(tKm)
        if (price == null || km == null || km <= 0) {
            tResult.text = "Ingresa tarifa y kilómetros."
            return
        }
        saveRules()
        val rules = SettingsStore.rules(this)
        val ev = ProfitCalculator.evaluate(
            RideOffer(price, km, num(tMin)?.takeIf { it > 0 }, payment = cl.carrerarentable.core.Payment.CARD), rules,
        )
        tResult.setTextColor(Color.parseColor(if (ev.profitable) "#16A34A" else "#DC2626"))
        tResult.text = buildString {
            appendLine(if (ev.profitable) "✅ RENTABLE" else "❌ NO RENTABLE")
            appendLine("Valor km: ${Money.clp(ev.perKm)}/km (mínimo ${Money.clp(rules.minPerKm)})")
            ev.perHour?.let { appendLine("Por hora: ${Money.clp(it)}/h") }
            if (rules.costPerKm > 0) appendLine("Te queda: ${Money.clp(ev.net)}")
            if (!ev.perKmOk) append("Para ser rentable debería pagar ${Money.clp(rules.minPerKm * km)}")
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, RideAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------- helpers de interfaz ----------

    /** Acepta "6.980", "6,980", "700" o "15,3" (km). */
    private fun num(e: EditText): Double? {
        val s = e.text.toString().trim().replace(" ", "").replace("$", "")
        if (s.isEmpty()) return null
        val normalized = if (Regex("""\d{1,3}([.,]\d{3})+""").matches(s)) s.replace(".", "").replace(",", "")
        else s.replace(',', '.')
        return normalized.toDoubleOrNull()
    }

    private fun field(parent: LinearLayout, label: String, value: Double?): EditText {
        parent.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (value != null) setText(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString())
            parent.addView(this)
        }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply { text = t; setTextColor(Color.GRAY); textSize = 13f }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; setOnClickListener(onClick)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAPTURE = 41
        private const val REQ_NOTIF = 42
    }
}
