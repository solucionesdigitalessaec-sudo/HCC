package cl.carrerarentable

import android.content.Context
import cl.carrerarentable.core.Rules
import cl.carrerarentable.core.Vehicle

/** Preferencias guardadas en el teléfono. */
object SettingsStore {
    private const val FILE = "ajustes"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Reglas del panel. El costo por km sale de los datos del auto. */
    fun rules(c: Context) = Rules(
        minPerKm = prefs(c).getFloat("min_km", 700f).toDouble(),
        minPerHour = prefs(c).getFloat("min_hora", 0f).toDouble(),
        costPerKm = vehicle(c).costPerKm,
        retentionPct = retentionPct(c),
        cardOnly = prefs(c).getBoolean("solo_tarjeta", true),
    )

    fun saveRules(c: Context, r: Rules) = prefs(c).edit()
        .putFloat("min_km", r.minPerKm.toFloat())
        .putFloat("min_hora", r.minPerHour.toFloat())
        .putBoolean("solo_tarjeta", r.cardOnly)
        .apply()

    /** Datos del auto (por defecto: diésel a $1.591 y 9,6 km/l). */
    fun vehicle(c: Context) = Vehicle(
        fuelPricePerLiter = prefs(c).getFloat("combustible_litro", 1591f).toDouble(),
        kmPerLiter = prefs(c).getFloat("km_por_litro", 9.6f).toDouble(),
        otherCostPerKm = prefs(c).getFloat("otros_costos_km", 0f).toDouble(),
    )

    fun saveVehicle(c: Context, v: Vehicle) = prefs(c).edit()
        .putFloat("combustible_litro", v.fuelPricePerLiter.toFloat())
        .putFloat("km_por_litro", v.kmPerLiter.toFloat())
        .putFloat("otros_costos_km", v.otherCostPerKm.toFloat())
        .apply()

    /** Retención de impuestos (boleta de honorarios): 15,25% en 2026. */
    fun retentionPct(c: Context) = prefs(c).getFloat("retencion_pct", 15.25f).toDouble()
    fun setRetentionPct(c: Context, v: Double) = prefs(c).edit().putFloat("retencion_pct", v.toFloat()).apply()

    /** Modo liviano: lee la pantalla con menos frecuencia y a menor resolución (teléfonos que se ponen lentos). */
    fun lightMode(c: Context) = prefs(c).getBoolean("modo_liviano", true)
    fun setLightMode(c: Context, v: Boolean) = prefs(c).edit().putBoolean("modo_liviano", v).apply()

    /** Última empresa con la que se empezó un turno. */
    fun lastApp(c: Context) = prefs(c).getString("ultima_app", "Cabify") ?: "Cabify"
    fun setLastApp(c: Context, v: String) = prefs(c).edit().putString("ultima_app", v).apply()

    /** Km de recogida que se suman a cada viaje importado del historial (el historial no los muestra). */
    fun historyPickupKm(c: Context) = prefs(c).getFloat("km_recogida_historial", 2f).toDouble()
    fun setHistoryPickupKm(c: Context, v: Double) = prefs(c).edit().putFloat("km_recogida_historial", v.toFloat()).apply()

    /** Anotar sola en la bitácora la carrera aceptada. */
    fun autoLog(c: Context) = prefs(c).getBoolean("bitacora_auto", true)
    fun setAutoLog(c: Context, v: Boolean) = prefs(c).edit().putBoolean("bitacora_auto", v).apply()

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
