package art.phoenix.platebase

/** Pure export builders (JVM-testable, no Android): CSV + JSON for sightings. */
object Export {
    fun toCsv(rows: List<Sighting>): String {
        val sb = StringBuilder("plate,time,lat,lon,accuracy\n")
        for (s in rows) {
            sb.append(s.plate).append(',')
                .append(s.seenAt).append(',')
                .append(s.lat?.toString() ?: "").append(',')
                .append(s.lon?.toString() ?: "").append(',')
                .append(s.accuracy?.toString() ?: "").append('\n')
        }
        return sb.toString()
    }

    fun toJson(rows: List<Sighting>): String {
        val sb = StringBuilder("[")
        for ((i, s) in rows.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"plate\":\"").append(s.plate).append("\",\"time\":").append(s.seenAt)
            sb.append(",\"lat\":").append(s.lat?.toString() ?: "null")
            sb.append(",\"lon\":").append(s.lon?.toString() ?: "null")
            sb.append(",\"accuracy\":").append(s.accuracy?.toString() ?: "null")
            sb.append(",\"gps_stale\":").append(s.gpsStale).append('}')
        }
        sb.append(']')
        return sb.toString()
    }
}

/** Pure GPS freshness + save-dedupe logic (JVM-testable). */
object GpsFresh {
    const val MAX_AGE_MS = 120_000L
    const val MAX_ACCURACY_M = 200f

    /** A fix is fresh only if <120 s old AND accuracy <= 200 m. */
    fun isStale(ageMs: Long?, accuracy: Float?): Boolean {
        if (ageMs == null || accuracy == null) return true
        return ageMs > MAX_AGE_MS || accuracy > MAX_ACCURACY_M
    }
}

data class GpsFix(val lat: Double, val lon: Double, val accuracy: Float, val atMs: Long)

/**
 * Live-OCR dedupe: the camera re-reads the same plate every ~1 s, so
 * saving every read would flood the DB with identical rows. A plate
 * is saved again only if it has not been saved in the last 10 s.
 * This is a dedupe of REPEATED READS, not of sightings — distinct
 * reads 10+ s apart are separate rows.
 */
class PlateSaver(private val windowMs: Long = 10_000L) {
    private val lastSaved = HashMap<String, Long>()
    fun shouldSave(plate: String, nowMs: Long): Boolean {
        val last = lastSaved[plate]
        if (last != null && nowMs - last < windowMs) return false
        lastSaved[plate] = nowMs
        return true
    }
}
