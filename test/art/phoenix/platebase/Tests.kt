package art.phoenix.platebase

/** Plain JVM test harness (same pattern as ALPR Signal): no JUnit needed. */
object PlateBaseTests {
    private var passed = 0
    private var failed = 0
    private fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // --- PlateLogic: Hawaii shapes ---
        check("plate ABC123", PlateLogic.find("PBA436").contains("PBA436"))
        check("plate display", PlateLogic.display("PBA436") == "PBA 436")
        check("plate reversed 123ABC", PlateLogic.find("123ABC").contains("123ABC"))
        check("plate split tokens", PlateLogic.find("Honolulu PBA 436 Hawaii").contains("PBA436"))
        check("plate split reversed", PlateLogic.find("plate 123 ABC spotted").contains("123ABC"))
        check("plate lowercase/punct", PlateLogic.find("abc-123").contains("ABC123"))
        check("plate custom ALOHA1", PlateLogic.find("ALOHA1").contains("ALOHA1"))
        check("plate custom AB12", PlateLogic.find("AB12").contains("AB12"))
        check("plate junk year", PlateLogic.find("2024").isEmpty())
        check("plate junk MPH", PlateLogic.find("65MPH").isEmpty())
        check("plate junk EXIT9", PlateLogic.find("EXIT9").isEmpty())
        check("plate junk GATE7", PlateLogic.find("GATE7").isEmpty())
        check("plate junk SPEED25", PlateLogic.find("SPEED25").isEmpty())
        check("plate junk repeat", PlateLogic.find("AAAA").isEmpty())
        check("plate junk words", PlateLogic.find("HELLO WORLD").isEmpty())
        check("plate junk digits", PlateLogic.find("123456").isEmpty())
        check("plate dedupe", PlateLogic.find("ABC123 ABC123").size == 1)
        check("plate fix O->0", PlateLogic.fixHawaii("PBA43O") == "PBA430")
        check("plate fix S->5", PlateLogic.fixHawaii("ABC12S") == "ABC125")
        check("plate fix 8->B never C", PlateLogic.fixHawaii("A8C123") == "ABC123" && PlateLogic.fixHawaii("A8C123") != "ACC123")
        check("plate fix passthrough", PlateLogic.fixHawaii("PBA436") == "PBA436")
        check("plate fix null", PlateLogic.fixHawaii("!!!!!!") == null)
        check("plate fix reversed", PlateLogic.fixReversed("CBA321") == "123ABC")
        check("plate null safe", PlateLogic.find(null).isEmpty() && PlateLogic.find("") .isEmpty())
        check("plate display null", PlateLogic.display(null) == "")

        // --- OcrPreprocess ---
        val black = IntArray(100) { 0 }
        val white = IntArray(100) { 255 }
        check("otsu uniform black", OcrPreprocess.otsuThreshold(black, 10, 10) in 0..255)
        check("otsu half black/white", OcrPreprocess.otsuThreshold(IntArray(100) { if (it < 50) 0 else 255 }, 10, 10) in 0..255)
        val g = IntArray(100) { if (it < 50) 0 else 255 }
        val t = OcrPreprocess.binarize(g, 10, 10)
        check("binarize only 0/255", g.all { it == 0 || it == 255 } && t in 0..255)
        check("binarize keeps dark dark", g[0] == 0 && g[99] == 255)
        val (rot, rw, rh) = OcrPreprocess.rotate(intArrayOf(1, 2, 3, 4), 2, 2, 90)
        check("rotate 90 swaps dims", rw == 2 && rh == 2 && rot.contentEquals(intArrayOf(3, 1, 4, 2)))
        val (rot180, _, _) = OcrPreprocess.rotate(intArrayOf(1, 2, 3, 4), 2, 2, 180)
        check("rotate 180 reverses", rot180.contentEquals(intArrayOf(4, 3, 2, 1)))
        val (rot0, _, _) = OcrPreprocess.rotate(intArrayOf(1, 2), 2, 1, 0)
        check("rotate 0 identity", rot0.contentEquals(intArrayOf(1, 2)))
        val (up, uw, uh) = OcrPreprocess.upscale2x(intArrayOf(5, 6, 7, 8), 2, 2)
        check("upscale2x dims", uw == 4 && uh == 4 && up[0] == 5 && up[1] == 5 && up[4] == 5 && up[5] == 5)
        val (crop, cw, ch) = OcrPreprocess.centerCrop(IntArray(100) { it }, 10, 10, 0.8, 0.5)
        check("centerCrop dims", cw == 8 && ch == 5 && crop.size == 40)
        check("centerCrop centred", crop[0] == 21) // row 2 (y0=2), col 1 (x0=1): 2*10+1

        // --- Export ---
        val rows = listOf(
            Sighting(1, "ABC123", 1000L, 21.33484, -157.91346, 5f, 1000L, false),
            Sighting(2, "123ABC", 2000L, null, null, null, null, true)
        )
        val csv = Export.toCsv(rows)
        check("csv header", csv.startsWith("plate,time,lat,lon,accuracy\n"))
        check("csv row with gps", csv.contains("ABC123,1000,21.33484,-157.91346,5.0"))
        check("csv row no gps empty", csv.contains("123ABC,2000,,,\n"))
        check("csv line count", csv.trim().lines().size == 3)
        val json = Export.toJson(rows)
        check("json plates", json.contains("\"plate\":\"ABC123\"") && json.contains("\"plate\":\"123ABC\""))
        check("json null lat", json.contains("\"lat\":null"))
        check("json stale flag", json.contains("\"gps_stale\":true"))
        check("json empty", Export.toJson(emptyList()) == "[]")
        check("csv empty", Export.toCsv(emptyList()) == "plate,time,lat,lon,accuracy\n")

        // --- GPS freshness ---
        check("gps fresh", !GpsFresh.isStale(30_000L, 10f))
        check("gps stale old", GpsFresh.isStale(121_000L, 10f))
        check("gps stale accuracy", GpsFresh.isStale(10_000L, 201f))
        check("gps stale boundary age", !GpsFresh.isStale(120_000L, 200f))
        check("gps stale null", GpsFresh.isStale(null, null) && GpsFresh.isStale(1000L, null))

        // --- PlateSaver dedupe ---
        val saver = PlateSaver()
        check("saver first save", saver.shouldSave("ABC123", 1000L))
        check("saver dedupe same second", !saver.shouldSave("ABC123", 2000L))
        check("saver other plate", saver.shouldSave("123ABC", 2000L))
        check("saver after window", saver.shouldSave("ABC123", 11_001L))
        check("saver dedupe again", !saver.shouldSave("ABC123", 11_500L))

        println("PlateBaseTests: $passed passed, $failed failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }
}
