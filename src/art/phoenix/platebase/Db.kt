package art.phoenix.platebase

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One sighting = one plate read, written the moment it is found.
 * lat/lon are nullable in spirit: when there is no fresh GPS fix the
 * row is still saved, with lat/lon NULL and gps_stale=1 — a plate is
 * NEVER dropped for lack of GPS, and a location is NEVER falsified
 * (no 0,0 sentinel, no stale fix presented as current).
 */
data class Sighting(
    val id: Long = 0,
    val plate: String = "",
    val seenAt: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float? = null,
    val gpsAgeMs: Long? = null,
    val gpsStale: Boolean = false
)

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "platebase.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sightings(id INTEGER PRIMARY KEY AUTOINCREMENT, plate TEXT NOT NULL, seen_at INTEGER NOT NULL, lat REAL, lon REAL, accuracy REAL, gps_age_ms INTEGER, gps_stale INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_plate ON sightings(plate)")
        db.execSQL("CREATE INDEX idx_seen ON sightings(seen_at DESC)")
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun addSighting(s: Sighting): Long {
        val v = ContentValues().apply {
            put("plate", s.plate); put("seen_at", s.seenAt)
            if (s.lat != null) put("lat", s.lat)
            if (s.lon != null) put("lon", s.lon)
            if (s.accuracy != null) put("accuracy", s.accuracy)
            if (s.gpsAgeMs != null) put("gps_age_ms", s.gpsAgeMs)
            put("gps_stale", if (s.gpsStale) 1 else 0)
        }
        return writableDatabase.insert("sightings", null, v)
    }

    fun listSightings(limit: Int = 1000): List<Sighting> {
        val out = mutableListOf<Sighting>()
        readableDatabase.rawQuery(
            "SELECT id,plate,seen_at,lat,lon,accuracy,gps_age_ms,gps_stale FROM sightings ORDER BY seen_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cur -> while (cur.moveToNext()) out.add(row(cur)) }
        return out
    }

    /** Distinct plates, newest first, with sighting count each. */
    fun listPlates(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT plate, COUNT(*) FROM sightings GROUP BY plate ORDER BY MAX(seen_at) DESC",
            null
        ).use { cur -> while (cur.moveToNext()) out.add((cur.getString(0) ?: "") to cur.getInt(1)) }
        return out
    }

    fun searchPlates(q: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT plate, COUNT(*) FROM sightings WHERE plate LIKE ? GROUP BY plate ORDER BY MAX(seen_at) DESC",
            arrayOf("%${q.uppercase()}%")
        ).use { cur -> while (cur.moveToNext()) out.add((cur.getString(0) ?: "") to cur.getInt(1)) }
        return out
    }

    fun sightingsForPlate(plate: String): List<Sighting> {
        val out = mutableListOf<Sighting>()
        readableDatabase.rawQuery(
            "SELECT id,plate,seen_at,lat,lon,accuracy,gps_age_ms,gps_stale FROM sightings WHERE plate=? ORDER BY seen_at DESC LIMIT 500",
            arrayOf(plate)
        ).use { cur -> while (cur.moveToNext()) out.add(row(cur)) }
        return out
    }

    fun deletePlate(plate: String): Int =
        writableDatabase.delete("sightings", "plate=?", arrayOf(plate))

    fun clearAll(): Int = writableDatabase.delete("sightings", null, null)

    fun countSightings(): Int = count("sightings")
    fun countPlates(): Int {
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT plate) FROM sightings", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun count(t: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun startSession(now: Long): Long {
        val v = ContentValues().apply { put("started_at", now) }
        return writableDatabase.insert("sessions", null, v)
    }

    fun endSession(id: Long, now: Long) {
        val v = ContentValues().apply { put("ended_at", now) }
        writableDatabase.update("sessions", v, "id=?", arrayOf(id.toString()))
    }

    private fun row(cur: android.database.Cursor): Sighting = Sighting(
        id = cur.getLong(0), plate = cur.getString(1) ?: "", seenAt = cur.getLong(2),
        lat = if (cur.isNull(3)) null else cur.getDouble(3),
        lon = if (cur.isNull(4)) null else cur.getDouble(4),
        accuracy = if (cur.isNull(5)) null else cur.getFloat(5),
        gpsAgeMs = if (cur.isNull(6)) null else cur.getLong(6),
        gpsStale = cur.getInt(7) == 1
    )
}
