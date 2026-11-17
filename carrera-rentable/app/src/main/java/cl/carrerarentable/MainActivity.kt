package cl.carrerarentable

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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

    private lateinit var setupBox: LinearLayout
    /** Ofrecer empezar el turno apenas se abre la app (solo la primera vez que se muestra). */
    private var autoStart = false
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
        autoStart = savedInstanceState == null

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        col.addView(note("Versión ${version ?: "?"}"))

        // Configuración guiada (una sola vez)
        col.addView(title("Configuración (una sola vez)"))
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(setupBox)

        // Turno
        col.addView(title("Turno"))
        shiftStatus = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        col.addView(shiftStatus)
        shiftButton = button("") {
            if (ShiftService.running) {
                ShiftService.stop(this)
                it.postDelayed({ refreshShift() }, 500)
            } else {
                startShift()
            }
        }.apply {
            textSize = 20f
            minHeight = dp(64)
        }
        col.addView(shiftButton)
        col.addView(note(
            "Al empezar, Android pregunta si permites grabar la pantalla: elige \"Pantalla completa\" y toca " +
                "\"Empezar\". Android lo pregunta en cada turno; no se puede guardar. Luego abre Uber, Cabify o DiDi."
        ))

        // 2. Reglas
        col.addView(title("2. Mis reglas"))
        val r = SettingsStore.rules(this)
        minKm = field(col, "Mínimo por km ($)", r.minPerKm)
        minHour = field(col, "Mínimo por hora ($) · 0 = no exigir", r.minPerHour)
        costKm = field(col, "Mi costo por km ($, bencina + desgaste) · 0 = no descontar", r.costPerKm)
        cardOnly = CheckBox(this).apply {
            text = "Solo carreras con tarjeta (efectivo = NO RENTABLE). En Cabify no se exige: ahí desactivas el efectivo."
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
            if (!OfferController.showTestPanel(this)) {
                Toast.makeText(this, "Primero activa \"Mostrar sobre otras apps\" (paso 1)", Toast.LENGTH_LONG).show()
            }
        })
        diag = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8)) }
        col.addView(diag)
        col.addView(note("Si no aparece el panel en Uber/Cabify/DiDi: deja llegar una solicitud, vuelve aquí y toca \"Copiar diagnóstico\". Pégalo en el chat."))
        col.addView(button("Copiar diagnóstico") {
            val t = Diag.report(this) + "\n\n--- Último texto leído ---\n" + Diag.lastText(this).ifBlank { "(vacío)" } +
                "\n\n--- Última solicitud mostrada ---\n" + SettingsStore.lastText(this).ifBlank { "(ninguna)" }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnostico", t))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        })
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        diag.text = Diag.report(this)
        refreshShift()
        // Al abrir la app, si todo está configurado, se ofrece empezar el turno de inmediato.
        if (autoStart && OfferController.canDraw(this) && !ShiftService.running) {
            autoStart = false
            startShift()
        }
        autoStart = false
    }

    /** Pasos de configuración con su estado (✅ listo / botón para hacerlo). */
    private fun refreshSetup() {
        setupBox.removeAllViews()
        val overlayOk = OfferController.canDraw(this)
        setupBox.addView(step(
            1, "Mostrar sobre otras apps", overlayOk,
            "Permite dibujar el panel encima de Uber, Cabify o DiDi. Busca KMRentable en la lista y activa el interruptor.",
            "Activar",
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
        })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val notifOk = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            setupBox.addView(step(
                2, "Notificaciones", notifOk,
                "Para ver el aviso \"turno activo\" y poder terminarlo desde ahí.", "Permitir",
            ) { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF_ONLY) })
        }
        val xiaomi = android.os.Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
        var n = if (android.os.Build.VERSION.SDK_INT >= 33) 3 else 2
        if (xiaomi) {
            setupBox.addView(step(
                n++, "Xiaomi: ventanas emergentes", null,
                "En \"Otros permisos\" activa \"Mostrar ventanas emergentes mientras se ejecuta en segundo plano\".",
                "Abrir otros permisos",
            ) { openXiaomiPermissions() })
        }
        val batteryOk = if (xiaomi) null else
            getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        setupBox.addView(step(
            n, "Batería sin restricciones", batteryOk,
            if (xiaomi) "En \"Ahorro de batería\" elige \"Sin restricciones\" para que el teléfono no detenga el turno. " +
                "Si se abre la información de la app, entra a \"Ahorro de batería\"."
            else "En \"Batería\" elige \"Sin restricciones\" para que Android no detenga el turno.",
            "Abrir ahorro de batería",
        ) { openBatterySettings(xiaomi) })
        if (overlayOk) {
            setupBox.addView(TextView(this).apply {
                text = "✅ Listo para trabajar"
                textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#16A34A")); setPadding(0, dp(8), 0, 0)
            })
        }
    }

    /** Un paso de la configuración. done = null: paso recomendado sin forma de comprobarlo. */
    private fun step(n: Int, name: String, done: Boolean?, help: String, action: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
            val mark = when (done) { true -> "✅"; false -> "⬜"; null -> "💡" }
            addView(TextView(context).apply {
                text = "$mark  Paso $n · $name"
                textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(if (done == true) "#16A34A" else "#111827"))
            })
            if (done != true) {
                addView(note(help))
                addView(button(action) { onClick() })
            }
        }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    }

    /** Pantalla de batería de la app: en Xiaomi la de "Ahorro de batería"; si no se puede, la info de la app. */
    private fun openBatterySettings(xiaomi: Boolean) {
        if (xiaomi) {
            val miui = Intent()
                .setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name))
            if (runCatching { startActivity(miui) }.isSuccess) return
        }
        openAppDetails()
    }

    private fun openXiaomiPermissions() {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName)
        runCatching { startActivity(miui) }.onFailure { openAppDetails() }
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
        if (!OfferController.canDraw(this)) {
            Toast.makeText(this, "Primero activa \"Mostrar sobre otras apps\" (paso 1)", Toast.LENGTH_LONG).show()
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
        if (requestCode == REQ_NOTIF_ONLY) {
            refreshSetup()
            return
        }
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
        Toast.makeText(this, "Turno iniciado: abre Uber, Cabify o DiDi", Toast.LENGTH_SHORT).show()
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
        private const val REQ_NOTIF_ONLY = 43
    }
}
