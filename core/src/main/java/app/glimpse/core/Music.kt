package app.glimpse.core

import kotlin.math.*

enum class AudioSource(val title: String) {
    MICROPHONE("Microphone"),
    PHONE("Phone audio"),
}

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val url: String? = null,
    val isrc: String? = null,
) {
    val identity: String
        get() =
            isrc?.takeIf { it.isNotBlank() }
                ?: "${artist.trim().lowercase()}\u0000${title.trim().lowercase()}"
}

data class MusicEvidence(
    val source: AudioSource,
    val backend: String,
    val playingApp: String? = null,
    val verifiedApp: Boolean = false,
)

data class SongMatch(val song: Song, val evidence: Set<MusicEvidence>)

data class SongRecognition(val song: Song, val backend: String)

interface MusicRecognitionEngine {
    val info: EngineInfo

    suspend fun recognize(samples: ShortArray): SongRecognition?
}

class OfflinePackRecognition(packs: List<OfflineSongPack>) : MusicRecognitionEngine {
    override val info =
        EngineInfo(
            "offline-packs",
            "Offline song packs",
            "Matches only songs in installed reference catalogues",
            true,
        )
    private val index = OfflineMusicIndex(packs)

    override suspend fun recognize(samples: ShortArray): SongRecognition? =
        index.recognize(samples)?.let { SongRecognition(it.first, "Offline · ${it.second}") }
}

/** Callers choose the allowed engines; an offline-only session has no network engine at all. */
class MusicRecognizer(private val engines: List<MusicRecognitionEngine>) {
    suspend fun recognize(samples: ShortArray): SongRecognition? {
        for (engine in engines) engine.recognize(samples)?.let {
            return it
        }
        return null
    }
}

/** Merge only identical tracks; independently recognized room/headphone songs stay separate. */
fun mergeMusicMatches(matches: List<SongMatch>): List<SongMatch> =
    matches
        .groupBy { it.song.identity }
        .values
        .map {
            SongMatch(it.first().song, it.flatMap { match -> match.evidence }.toSet())
        }

data class AudioFingerprint(val hash: Int, val frame: Int)

data class FingerprintedSong(val song: Song, val fingerprints: List<AudioFingerprint>)

data class OfflineSongPack(
    val id: String,
    val name: String,
    val version: Int,
    val songs: List<FingerprintedSong>,
)

/**
 * Version 1: 8 kHz mono, 512-sample Hann FFT, 128-sample hop, paired spectral peaks. Reference
 * extraction uses stride 4; queries use every anchor to tolerate arbitrary offsets. This is a local
 * catalogue matcher, not a model with knowledge of songs outside its index.
 */
object MusicFingerprints {
    const val SAMPLE_RATE = 8000
    const val WINDOW = 512
    const val HOP = 128

    fun extract(samples: ShortArray, stride: Int = 4): List<AudioFingerprint> {
        require(stride > 0)
        if (samples.size < WINDOW + HOP * 12) return emptyList()
        val peaks = ArrayList<IntArray>()
        val window = DoubleArray(WINDOW) { .5 - .5 * cos(2 * PI * it / (WINDOW - 1)) }
        for (start in 0..samples.size - WINDOW step HOP) {
            val real = DoubleArray(WINDOW) { samples[start + it] / 32768.0 * window[it] }
            val imaginary = DoubleArray(WINDOW)
            fft(real, imaginary)
            val power =
                DoubleArray(WINDOW / 2) { real[it] * real[it] + imaginary[it] * imaginary[it] }
            val candidates =
                (10 until 240)
                    .filter {
                        power[it] > .0001 && power[it] > power[it - 1] && power[it] >= power[it + 1]
                    }
                    .sortedByDescending { power[it] }
            val chosen = mutableListOf<Int>()
            for (bin in candidates) {
                if (
                    chosen.all { abs(it - bin) >= 5 } &&
                        power[bin] >= power[candidates.first()] * .15
                )
                    chosen.add(bin)
                if (chosen.size == 2) break
            }
            peaks.add(chosen.map { it / 2 }.toIntArray())
        }
        return buildList {
            for (frame in peaks.indices step stride) for (delta in intArrayOf(4, 8, 12)) {
                if (frame + delta >= peaks.size) continue
                for (first in peaks[frame]) for (second in peaks[frame + delta]) add(
                    AudioFingerprint((first shl 12) or (second shl 4) or delta, frame),
                )
            }
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val value = real[i]
                real[i] = real[j]
                real[j] = value
            }
        }
        var length = 2
        while (length <= real.size) {
            val angle = -2 * PI / length
            for (start in real.indices step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val tr = real[odd] * wr - imaginary[odd] * wi
                    val ti = real[odd] * wi + imaginary[odd] * wr
                    real[odd] = real[even] - tr
                    imaginary[odd] = imaginary[even] - ti
                    real[even] += tr
                    imaginary[even] += ti
                    val next = wr * cos(angle) - wi * sin(angle)
                    wi = wr * sin(angle) + wi * cos(angle)
                    wr = next
                }
            }
            length *= 2
        }
    }
}

/** Offset-consistent landmark voting with a negative/ambiguous-match rejection threshold. */
class OfflineMusicIndex(packs: List<OfflineSongPack>) {
    private data class Reference(val track: Int, val frame: Int)

    private val songs = packs.flatMap { pack -> pack.songs.map { it.song to pack.name } }
    private val index = HashMap<Int, MutableList<Reference>>()

    init {
        var track = 0
        packs.forEach { pack ->
            pack.songs.forEach { song ->
                song.fingerprints.forEach { fp ->
                    index.getOrPut(fp.hash) { mutableListOf() }.add(Reference(track, fp.frame))
                }
                track++
            }
        }
    }

    fun recognize(samples: ShortArray): Pair<Song, String>? {
        val query = MusicFingerprints.extract(samples, stride = 1)
        if (query.size < 24) return null
        val votes = HashMap<Long, MutableSet<Int>>()
        for (fp in query) {
            val references = index[fp.hash].orEmpty()
            // Very common tonal landmarks are uninformative. Skipping them avoids both
            // unbounded work and bias toward whichever tracks happened to be indexed first.
            if (references.size > 512) continue
            for (reference in references) {
                val offset = floor((reference.frame - fp.frame) / 3.0).toInt()
                val key = (reference.track.toLong() shl 32) or (offset.toLong() and 0xffffffffL)
                votes.getOrPut(key) { mutableSetOf() }.add(fp.frame)
            }
        }
        val bestByTrack =
            votes.entries
                .groupBy { (it.key ushr 32).toInt() }
                .mapValues { (_, offsets) -> offsets.maxBy { it.value.size }.value }
        val ranked = bestByTrack.entries.sortedByDescending { it.value.size }
        val winner = ranked.firstOrNull() ?: return null
        val frames = winner.value
        val runnerUp = ranked.getOrNull(1)?.value?.size ?: 0
        if (frames.size < 18 || frames.max() - frames.min() < 94 || frames.size < runnerUp * 1.4)
            return null
        return songs[winner.key]
    }
}
