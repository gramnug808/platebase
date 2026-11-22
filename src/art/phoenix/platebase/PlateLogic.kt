package art.phoenix.platebase

/**
 * License-plate extraction from OCR text — pure logic, JVM-tested.
 *
 * The two-pass core is the Phoenix PlateFinder rules (proven in
 * Wardrive Companion): fast-path Hawai'i shapes first (ABC123 and the
 * reversed-issue 123ABC, glued or split across tokens), then a custom
 * pass for vanity plates (4–8 chars, letters + digits mixed). Junk
 * stays out: bare years, speed badges (65MPH), road-paint words glued
 * to digits (EXIT9, GATE7, SPEED25), pure repeats.
 *
 * On top of that sit Ben's two Hawai'i normalizations from newtool
 * v3.x, reconstructed from the audit: [fixHawaii] repairs the classic
 * OCR confusions positionally once a plate shape is known (letter
 * slots get letters, digit slots get digits), and [fixReversed]
 * recovers a plate the camera/OCR read back-to-front by reversing
 * the string and re-checking the shape.
 */
object PlateLogic {

    private val HI_PLATE = Regex("[A-Z]{3}[0-9]{3}")
    private val REV_PLATE = Regex("[0-9]{3}[A-Z]{3}")
    private val YEAR = Regex("^(19|20)[0-9]{2}$")
    private val BADGE = Regex("^[0-9]+(MPH|MPG|KMH)$")

    /** Road-paint / signage words glued to digits are infrastructure. */
    private val WORD_BLOCKLIST = setOf("EXIT", "GATE", "LANE", "ZONE", "SPEED")

    /** OCR digit-slot confusions: what the glyph probably is as a digit. */
    private val TO_DIGIT = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1',
        'Z' to '2', 'S' to '5', 'G' to '6', 'T' to '7', 'B' to '8'
    )

    /** OCR letter-slot confusions: what the glyph probably is as a letter. */
    private val TO_LETTER = mapOf(
        '0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '6' to 'G',
        '7' to 'T', '8' to 'B'
    )

    /** Returns normalized plate strings (no spaces), strongest first. */
    fun find(text: String?): List<String> {
        if (text == null) return emptyList()
        val out = LinkedHashSet<String>()
        val norm = text.uppercase().replace(Regex("[^A-Z0-9]+"), " ")
        val toks = norm.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        // Pass 1a: fast-path shapes inside a single token (badges skipped
        // whole — their digits+letters would match the reversed shape).
        for (t in toks) {
            if (BADGE.matches(t)) continue
            for (m in HI_PLATE.findAll(t)) out.add(m.value)
            for (m in REV_PLATE.findAll(t)) out.add(m.value)
        }
        // Pass 1b: shapes split across two adjacent tokens ("ABC 123").
        for (i in 0 until toks.size - 1) {
            val merged = toks[i] + toks[i + 1]
            if (merged.length == 6 && !BADGE.matches(merged) &&
                (HI_PLATE.matches(merged) || REV_PLATE.matches(merged))
            ) out.add(merged)
        }
        // Pass 1c: Hawai'i positional repair on single 6-char tokens
        // that almost have a plate shape. (Reversal needs no pass
        // here: a back-to-front read of an ABC123 plate spells a
        // valid 123ABC plate and vice versa — both formats are
        // first-class fast paths, so reversed reads are already
        // caught. See fixReversed.)
        for (t in toks) {
            if (t.length == 6 && !BADGE.matches(t)) {
                fixHawaii(t)?.let { out.add(it) }
            }
        }
        // Pass 2: custom / vanity candidates — one mixed token.
        for (t in toks) {
            if (t.length in 4..8 && looksLikePlate(t)) out.add(t)
        }
        return ArrayList(out)
    }

    /** Custom-pass gate (same rules as the Wardrive build). */
    fun looksLikePlate(c: String): Boolean {
        var digits = 0
        var letters = 0
        for (ch in c) {
            when {
                ch in '0'..'9' -> digits++
                ch in 'A'..'Z' -> letters++
                else -> return false
            }
        }
        if (digits < 1 || letters < 1) return false
        if (YEAR.matches(c)) return false
        if (BADGE.matches(c)) return false
        if (c.all { it == c[0] }) return false // one repeated glyph is noise
        // Blocklisted signage word + digits only (EXIT9, SPEED25).
        val cut = c.indexOfFirst { it in '0'..'9' }
        if (cut > 0 && WORD_BLOCKLIST.contains(c.substring(0, cut))) return false
        return true
    }

    /**
     * Positional OCR repair for a 6-char token: if treating it as
     * ABC123 (or 123ABC) and fixing each slot with the confusion map
     * yields a valid plate that differs from the raw token, return the
     * repaired plate. Returns the token itself when it already matches
     * a shape exactly; null when no repair produces a plate shape.
     */
    fun fixHawaii(token: String): String? {
        if (token.length != 6) return null
        if (HI_PLATE.matches(token) || REV_PLATE.matches(token)) return token
        val asHi = repair(token, lettersFirst = true)
        if (asHi != null && HI_PLATE.matches(asHi)) return asHi
        val asRev = repair(token, lettersFirst = false)
        if (asRev != null && REV_PLATE.matches(asRev)) return asRev
        return null
    }

    private fun repair(token: String, lettersFirst: Boolean): String? {
        val sb = StringBuilder(6)
        for (i in 0 until 6) {
            val wantLetter = if (lettersFirst) i < 3 else i >= 3
            val ch = token[i]
            if (wantLetter) {
                if (ch in 'A'..'Z') sb.append(ch)
                else sb.append(TO_LETTER[ch] ?: return null)
            } else {
                if (ch in '0'..'9') sb.append(ch)
                else sb.append(TO_DIGIT[ch] ?: return null)
            }
        }
        return sb.toString()
    }

    /**
     * Ben's reversal recovery, as a caller-directed utility: when a
     * read is KNOWN to be back-to-front (mirrored frame, through-glass
     * shot), reverse the token and keep it if that forms a plate.
     * [find] deliberately does not apply this blindly — a reversed
     * ABC123 is itself a legitimate 123ABC plate, so only a caller
     * who knows the read was mirrored may flip it.
     */
    fun fixReversed(token: String): String? {
        if (token.length != 6) return null
        val rev = token.reversed()
        return if (HI_PLATE.matches(rev) || REV_PLATE.matches(rev)) rev else null
    }

    /** "ABC123" -> "ABC 123", "123ABC" -> "123 ABC" for display. */
    fun display(plate: String?): String {
        if (plate != null && plate.length == 6 &&
            (HI_PLATE.matches(plate) || REV_PLATE.matches(plate))
        ) {
            return plate.substring(0, 3) + " " + plate.substring(3)
        }
        return plate ?: ""
    }
}
