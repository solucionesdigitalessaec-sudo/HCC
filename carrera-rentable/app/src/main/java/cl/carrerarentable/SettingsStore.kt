package cl.carrerarentable

import android.content.Context
import cl.carrerarentable.core.Rules

/** Preferencias guardadas en el teléfono. */
object SettingsStore {
    private const val FILE = "ajustes"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rules(c: Context) = Rules(
        minPerKm = prefs(c).getFloat("min_km", 700f).toDouble(),
        minPerHour = prefs(c).getFloat("min_hora", 0f).toDouble(),
        costPerKm = prefs(c).getFloat("costo_km", 0f).toDouble(),
        cardOnly = prefs(c).getBoolean("solo_tarjeta", true),
    )

    fun saveRules(c: Context, r: Rules) = prefs(c).edit()
        .putFloat("min_km", r.minPerKm.toFloat())
        .putFloat("min_hora", r.minPerHour.toFloat())
        .putFloat("costo_km", r.costPerKm.toFloat())
        .putBoolean("solo_tarjeta", r.cardOnly)
        .apply()

    /** Segundos antes de ocultar el panel solo. 0 (por defecto) = queda hasta que lo toques. */
    fun hideSeconds(c: Context) = prefs(c).getInt("ocultar_seg_v2", 0)
    fun setHideSeconds(c: Context, s: Int) = prefs(c).edit().putInt("ocultar_seg_v2", s).apply()

    fun vibrate(c: Context) = prefs(c).getBoolean("vibrar", true)
    fun setVibrate(c: Context, v: Boolean) = prefs(c).edit().putBoolean("vibrar", v).apply()

    fun sound(c: Context) = prefs(c).getBoolean("sonido", true)
    fun setSound(c: Context, v: Boolean) = prefs(c).edit().putBoolean("sonido", v).apply()

    fun overlayY(c: Context) = prefs(c).getInt("panel_y", 140)
    fun setOverlayY(c: Context, y: Int) = prefs(c).edit().putInt("panel_y", y).apply()

    /** Último texto leído de una app de conductor (para revisar si algo no se detecta bien). */
    fun lastText(c: Context) = prefs(c).getString("ultimo_texto", "") ?: ""
    fun setLastText(c: Context, t: String) = prefs(c).edit().putString("ultimo_texto", t).apply()
}

/** Apps de conductor que se leen. Se reconoce por nombre de paquete. */
object RideApps {
    private val known = setOf(
        "com.ubercab.driver",
        "com.didiglobal.driver",
        "com.didi.driver.global",
        "com.cabify.driver",
    )
    private val keywords = listOf("uber", "didi", "cabify", "indriver")
    /** Apps de pasajero / comida de las mismas empresas: no se leen. */
    private val excluded = setOf("com.ubercab", "com.ubercab.eats", "com.didiglobal.passenger", "com.cabify.rider")

    fun isRideApp(pkg: String?): Boolean {
        if (pkg == null || pkg == BuildConfigPkg.SELF) return false
        val p = pkg.lowercase()
        if (p in excluded) return false
        return p in known || keywords.any { p.contains(it) }
    }
}

object BuildConfigPkg {
    const val SELF = "cl.carrerarentable"
}
