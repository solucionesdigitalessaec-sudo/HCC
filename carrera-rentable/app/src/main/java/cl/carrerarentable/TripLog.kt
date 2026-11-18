package cl.carrerarentable

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cl.carrerarentable.core.Trip

/** Bitácora guardada solo en el teléfono (SQLite): carreras y turnos. */
class TripLog private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "bitacora.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE carreras (id INTEGER PRIMARY KEY AUTOINCREMENT, tiempo INTEGER NOT NULL,
               app TEXT NOT NULL, monto REAL NOT NULL, km_recogida REAL NOT NULL, km_viaje REAL NOT NULL,
               minutos REAL, origen TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX carreras_tiempo ON carreras(tiempo)")
        db.execSQL("CREATE TABLE turnos (id INTEGER PRIMARY KEY AUTOINCREMENT, inicio INTEGER NOT NULL, fin INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- carreras ----------

    fun add(t: Trip): Long = writableDatabase.insert("carreras", null, values(t))

    fun update(t: Trip) {
        writableDatabase.update("carreras", values(t), "id = ?", arrayOf(t.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("carreras", "id = ?", arrayOf(id.toString()))
    }

    /** Carreras entre [from, to), de la más nueva a la más antigua. */
    fun trips(from: Long, to: Long): List<Trip> {
        val out = ArrayList<Trip>()
        readableDatabase.rawQuery(
            "SELECT id, tiempo, app, monto, km_recogida, km_viaje, minutos, origen FROM carreras " +
                "WHERE tiempo >= ? AND tiempo < ? ORDER BY tiempo DESC",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Trip(
                    id = c.getLong(0), time = c.getLong(1), app = c.getString(2), price = c.getDouble(3),
                    pickupKm = c.getDouble(4), tripKm = c.getDouble(5),
                    minutes = if (c.isNull(6)) null else c.getDouble(6), source = c.getString(7),
                )
            }
        }
        return out
    }

    private fun values(t: Trip) = ContentValues().apply {
        put("tiempo", t.time); put("app", t.app); put("monto", t.price)
        put("km_recogida", t.pickupKm); put("km_viaje", t.tripKm)
        if (t.minutes != null) put("minutos", t.minutes) else putNull("minutos")
        put("origen", t.source)
    }

    // ---------- turnos ----------

    /** Empieza un turno y devuelve su id. El fin se va actualizando mientras el turno sigue activo. */
    fun startShift(now: Long): Long = writableDatabase.insert("turnos", null, ContentValues().apply {
        put("inicio", now); put("fin", now)
    })

    fun touchShift(id: Long, now: Long) {
        writableDatabase.update("turnos", ContentValues().apply { put("fin", now) }, "id = ?", arrayOf(id.toString()))
    }

    /** Minutos de turno dentro de [from, to) (los turnos que cruzan el límite se cortan). */
    fun shiftMinutes(from: Long, to: Long): Double {
        var ms = 0L
        readableDatabase.rawQuery(
            "SELECT inicio, fin FROM turnos WHERE fin > ? AND inicio < ?", arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val a = maxOf(c.getLong(0), from)
                val b = minOf(c.getLong(1), to)
                if (b > a) ms += b - a
            }
        }
        return ms / 60000.0
    }

    companion object {
        @Volatile private var instance: TripLog? = null
        fun get(c: Context): TripLog = instance ?: synchronized(this) {
            instance ?: TripLog(c).also { instance = it }
        }
    }
}
