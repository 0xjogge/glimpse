package app.glimpse.core

import kotlin.math.*
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    @Test
    fun pythonPackBuilderAndAndroidExtractorUseTheSamePortableLandmarkFormat() {
        val tone = ShortArray(16000) { (12000 * sin(2 * PI * 440 * it / 8000)).toInt().toShort() }
        assertTrue(
            MusicFingerprints.extract(tone)
                .contains(AudioFingerprint((14 shl 12) or (14 shl 4) or 8, 0)),
        )
    }

    @Test
    fun aSuccessfulLocalMatchNeverCallsTheOnlineFallback() = runBlocking {
        val online =
            object : MusicRecognitionEngine {
                override val info = EngineInfo("online", "Online", "", false)

                override suspend fun recognize(samples: ShortArray): SongRecognition? =
                    error("A local hit must not upload audio")
            }
        val recognizer = MusicRecognizer(listOf(OfflinePackRecognition(listOf(pack(1))), online))
        assertEquals("song-1", recognizer.recognize(recording(1, 6))?.song?.id)
    }

    private fun recording(seed: Int, seconds: Int = 18): ShortArray {
        val notes =
            Random(seed).let { random ->
                DoubleArray(seconds * 3 + 1) { 180.0 * 2.0.pow(random.nextInt(24) / 12.0) }
            }
        return ShortArray(seconds * 8000) { sample ->
            val time = sample / 8000.0
            val frequency = notes[(time * 3).toInt()]
            ((sin(2 * PI * frequency * time) * .4 + sin(2 * PI * frequency * 1.5 * time) * .18) *
                    32767)
                .toInt()
                .toShort()
        }
    }

    private fun pack(seed: Int) =
        OfflineSongPack(
            "pack-$seed",
            "Pack $seed",
            1,
            listOf(
                FingerprintedSong(
                    Song("song-$seed", "Song $seed", "Artist"),
                    MusicFingerprints.extract(recording(seed)),
                ),
            ),
        )

    @Test
    fun matchesAnUnalignedExcerptWithAmbientNoiseAmongDifferentSongs() {
        val index = OfflineMusicIndex(listOf(pack(1), pack(2), pack(3)))
        val reference = recording(2)
        val random = Random(42)
        val excerpt =
            reference
                .copyOfRange(27113, 27113 + 8000 * 6)
                .map {
                    (it * .6 + random.nextInt(-1000, 1000))
                        .toInt()
                        .coerceIn(-32768, 32767)
                        .toShort()
                }
                .toShortArray()
        val match = index.recognize(excerpt)
        assertNotNull("A noisy arbitrary offset should match the indexed full track", match)
        assertEquals("song-2", match!!.first.id)
        assertEquals("Pack 2", match.second)
    }

    @Test
    fun silenceUnknownMusicAndVeryShortClipsDoNotProduceMatches() {
        val index = OfflineMusicIndex(listOf(pack(1), pack(2)))
        assertNull(index.recognize(ShortArray(48000)))
        assertNull(index.recognize(recording(77, 6)))
        assertNull(index.recognize(recording(1).copyOfRange(0, 3000)))
    }

    @Test
    fun identicalCatalogueEntriesAreAmbiguousRatherThanAnArbitraryWinner() {
        val first = pack(1)
        val duplicate =
            first.copy(
                id = "duplicate",
                songs =
                    first.songs.map {
                        it.copy(song = it.song.copy(id = "different", title = "Different"))
                    },
            )
        assertNull(OfflineMusicIndex(listOf(first, duplicate)).recognize(recording(1, 6)))
    }

    @Test
    fun mergesTheSameTrackButPreservesIndependentSourcesAndDifferentSongs() {
        val song = Song("local", "A song", "Artist")
        val mic = MusicEvidence(AudioSource.MICROPHONE, "Offline · Pack")
        val phone = MusicEvidence(AudioSource.PHONE, "Online · AudD", "Player")
        val other = Song("other", "Another song", "Artist")
        val matches =
            mergeMusicMatches(
                listOf(
                    SongMatch(song, setOf(mic)),
                    SongMatch(song.copy(id = "online"), setOf(phone)),
                    SongMatch(other, setOf(mic)),
                ),
            )
        assertEquals(2, matches.size)
        assertEquals(setOf(mic, phone), matches.first().evidence)
        assertEquals("Another song", matches.last().song.title)
    }
}
