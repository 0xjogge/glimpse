package app.glimpse.core

/**
 * Repeated text or a settled frame reaches translation. Disappearing occurrences expire
 * immediately.
 */
class LiveTextStabilizer(private val repetitions: Int = 2) {
    private data class Match(val current: Int, val prior: Int, val distance: Float)

    private var previous = emptyList<Pair<TextLine, Int>>()

    @Synchronized
    fun update(lines: List<TextLine>, settled: Boolean = false): List<TextLine> {
        if (settled) {
            previous = lines.map { it to repetitions }
            return lines
        }
        val matches = mutableListOf<Match>()
        for ((current, line) in lines.withIndex()) {
            for ((prior, entry) in previous.withIndex()) {
                val old = entry.first
                if (old.text.trim() != line.text.trim()) continue
                val distances =
                    listOf(
                        kotlin.math.abs(old.bounds.left - line.bounds.left),
                        kotlin.math.abs(old.bounds.top - line.bounds.top),
                        kotlin.math.abs(old.bounds.right - line.bounds.right),
                        kotlin.math.abs(old.bounds.bottom - line.bounds.bottom),
                    )
                if (distances.all { it < .02f }) {
                    matches.add(Match(current, prior, distances.sum()))
                }
            }
        }
        val counts = IntArray(lines.size) { 1 }
        val matchedCurrent = BooleanArray(lines.size)
        val matchedPrior = BooleanArray(previous.size)
        // Prefer the closest geometry globally, so an earlier newly appearing duplicate cannot
        // steal the exact match of an existing occurrence just because OCR reordered its lines.
        for (match in matches.sortedBy { it.distance }) {
            if (matchedCurrent[match.current] || matchedPrior[match.prior]) continue
            matchedCurrent[match.current] = true
            matchedPrior[match.prior] = true
            counts[match.current] = minOf(previous[match.prior].second + 1, repetitions)
        }
        previous = lines.mapIndexed { index, line -> line to counts[index] }
        return previous.filter { it.second >= repetitions }.map { it.first }
    }

    @Synchronized
    fun reset() {
        previous = emptyList()
    }
}

/** Bounded session-only cache. Keys include the engine and both language tags. */
class TranslationCache(private val limit: Int = 128) {
    private data class Key(
        val engine: String,
        val source: String,
        val target: String,
        val text: String,
    )

    private val entries =
        object : LinkedHashMap<Key, String>(limit, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?) =
                size > limit
        }

    @Synchronized
    fun get(engine: String, source: String, target: String, text: String) =
        entries[Key(engine, source, target, text)]

    @Synchronized
    fun put(engine: String, source: String, target: String, text: String, value: String) {
        entries[Key(engine, source, target, text)] = value
    }

    @Synchronized fun clear() = entries.clear()
}
