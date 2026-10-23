package app.glimpse.core

/** Only stable, repeated text reaches translation. A disappearing line expires immediately. */
class LiveTextStabilizer(private val repetitions: Int = 2) {
    private var previous = emptyList<Pair<TextLine, Int>>()

    @Synchronized
    fun update(lines: List<TextLine>): List<TextLine> {
        val counts = lines.map { line ->
            val prior = previous.firstOrNull { (old, _) ->
                old.text.trim() == line.text.trim() &&
                    kotlin.math.abs(old.bounds.left - line.bounds.left) < .02f &&
                    kotlin.math.abs(old.bounds.top - line.bounds.top) < .02f &&
                    kotlin.math.abs(old.bounds.right - line.bounds.right) < .02f &&
                    kotlin.math.abs(old.bounds.bottom - line.bounds.bottom) < .02f
            }
            line to ((prior?.second ?: 0) + 1)
        }
        previous = counts
        return counts.filter { it.second >= repetitions }.map { it.first }
    }

    @Synchronized
    fun reset() {
        previous = emptyList()
    }
}

/** Bounded session-only cache. Keys include the engine and both language tags. */
class TranslationCache(private val limit: Int = 128) {
    private val entries =
        object : LinkedHashMap<String, String>(limit, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
                size > limit
        }

    private fun key(engine: String, source: String, target: String, text: String) =
        listOf(engine, source, target, text).joinToString("\u0000")

    @Synchronized
    fun get(engine: String, source: String, target: String, text: String) =
        entries[key(engine, source, target, text)]

    @Synchronized
    fun put(engine: String, source: String, target: String, text: String, value: String) {
        entries[key(engine, source, target, text)] = value
    }

    @Synchronized fun clear() = entries.clear()
}
