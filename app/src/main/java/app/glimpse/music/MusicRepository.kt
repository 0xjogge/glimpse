package app.glimpse.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.JsonReader
import app.glimpse.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Credentials stay in app-private storage, encrypted with a non-exportable Android Keystore key.
 */
class MusicCredentials(context: Context) {
    private val preferences =
        context.getSharedPreferences("music_credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("music_api", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "music_api",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }
                .generateKey()
    }

    fun read(): String {
        val value = preferences.getString("audd", null) ?: return ""
        return runCatching {
                val parts = value.split(":")
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
                    )
                    String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
                }
            }
            .getOrDefault("")
    }

    fun write(value: String) {
        if (value.isBlank()) {
            preferences.edit().remove("audd").apply()
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        preferences
            .edit()
            .putString(
                "audd",
                "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}",
            )
            .apply()
    }
}

class SongPackRepository(private val context: Context) {
    private val directory
        get() = File(context.filesDir, "song-packs").apply { mkdirs() }

    fun installed(): List<OfflineSongPack> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .sortedBy { it.name }
            .mapNotNull {
                runCatching {
                    require(it.length() <= MAX_BYTES)
                    it.reader().use(::decode)
                }
                    .getOrNull()
            }

    fun delete(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        File(directory, "$id.json").delete()
    }

    suspend fun download(url: String, sha256: String, wifiOnly: Boolean) =
        withContext(Dispatchers.IO) {
            require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                "Enter the pack's SHA-256 checksum"
            }
            requireSecureUrl(url)
            if (wifiOnly) {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                require(
                    manager
                        .getNetworkCapabilities(manager.activeNetwork)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                ) {
                    "Connect to Wi-Fi or disable Wi-Fi-only downloads"
                }
            }
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            val temporary = File.createTempFile("song-pack-", ".download", directory)
            try {
                connection.cancellable {
                    require(connection.responseCode == 200) {
                        "Pack download returned HTTP ${connection.responseCode}. Use a direct HTTPS URL."
                    }
                    require(connection.contentLengthLong <= MAX_BYTES) { "Song pack exceeds 64 MB" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val bytes = ByteArray(16384)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BYTES) { "Song pack exceeds 64 MB" }
                                digest.update(bytes, 0, count)
                                output.write(bytes, 0, count)
                            }
                        }
                    }
                    require(
                        digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, true),
                    ) {
                        "Song pack checksum does not match"
                    }
                    val pack = temporary.reader().use(::decode)
                    val other = installed().filter { it.id != pack.id }
                    require(
                        other.size < 3 &&
                            other.sumOf { item -> item.songs.sumOf { it.fingerprints.size } } +
                                pack.songs.sumOf { it.fingerprints.size } <= 1_000_000,
                    ) {
                        "Remove a song pack before installing another (one million fingerprint limit)"
                    }
                    require(temporary.renameTo(File(directory, "${pack.id}.json"))) {
                        "Could not install song pack"
                    }
                    pack
                }
            } finally {
                temporary.delete()
            }
        }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024

        fun decode(text: String): OfflineSongPack {
            require(text.length <= MAX_BYTES) { "Song pack is too large" }
            return decode(StringReader(text))
        }

        /** Stream validation avoids retaining a second, million-element JSON object graph. */
        private fun decode(input: Reader): OfflineSongPack {
            val json = JsonReader(input)
            var format = ""
            var id = ""
            var name = ""
            var version = 0
            var total = 0
            val songs = mutableListOf<FingerprintedSong>()
            json.beginObject()
            while (json.hasNext()) when (json.nextName()) {
                "format" -> format = json.nextString()
                "id" -> id = json.nextString()
                "name" -> name = json.nextString()
                "version" -> version = json.nextInt()
                "songs" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        require(songs.size < 10000) { "Too many songs" }
                        var songId = ""
                        var title = ""
                        var artist = ""
                        val values = mutableListOf<AudioFingerprint>()
                        json.beginObject()
                        while (json.hasNext()) when (json.nextName()) {
                            "id" -> songId = json.nextString()
                            "title" -> title = json.nextString()
                            "artist" -> artist = json.nextString()
                            "fingerprints" -> {
                                json.beginArray()
                                while (json.hasNext()) {
                                    total++
                                    require(total <= 1_000_000) { "Too many fingerprints" }
                                    json.beginArray()
                                    val hash = json.nextInt()
                                    val frame = json.nextInt()
                                    require(hash in 0..0xfffff && frame in 0..1_000_000)
                                    json.endArray()
                                    values.add(AudioFingerprint(hash, frame))
                                }
                                json.endArray()
                            }
                            else -> json.skipValue()
                        }
                        json.endObject()
                        require(
                            songId.isNotBlank() &&
                                songId.length <= 300 &&
                                title.isNotBlank() &&
                                title.length <= 300 &&
                                artist.isNotBlank() &&
                                artist.length <= 300,
                        )
                        require(values.size >= 24) { "Not enough reference fingerprints" }
                        songs.add(FingerprintedSong(Song(songId, title, artist), values))
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
            json.endObject()
            require(json.peek() == android.util.JsonToken.END_DOCUMENT)
            require(format == "glimpse-landmarks-v1") { "Unsupported fingerprint format" }
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
            require(name.length in 1..120 && version > 0 && songs.isNotEmpty())
            require(songs.map { it.song.id }.distinct().size == songs.size) {
                "Duplicate song identifiers"
            }
            return OfflineSongPack(id, name, version, songs)
        }
    }
}

/** A bounded clip is sent only after the user starts recognition in an online-enabled mode. */
class AudDRecognition(private val apiKey: String) : MusicRecognitionEngine {
    override val info =
        EngineInfo("audd", "AudD", "Online recognition using your own API key", false)

    override suspend fun recognize(samples: ShortArray): SongRecognition? =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) {
                "Add an AudD API key in Music settings for online recognition"
            }
            val boundary = "glimpse-${java.util.UUID.randomUUID()}"
            val body = ByteArrayOutputStream()
            body.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"api_token\"\r\n\r\n$apiKey\r\n"
                    .toByteArray(),
            )
            body.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
                    .toByteArray(),
            )
            body.write(wav(samples))
            body.write("\r\n--$boundary--\r\n".toByteArray())
            val connection =
                URI("https://api.audd.io/").toURL().openConnection() as HttpURLConnection
            connection.cancellable {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary",
                )
                connection.setFixedLengthStreamingMode(body.size())
                connection.outputStream.use { it.write(body.toByteArray()) }
                require(connection.responseCode == 200) {
                    "AudD returned HTTP ${connection.responseCode}"
                }
                val response =
                    connection.inputStream.use { input ->
                        val bytes = input.readNBytes(1_048_577)
                        require(bytes.size <= 1_048_576) { "Recognition response is too large" }
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }
                require(response.optString("status") == "success") {
                    "AudD could not recognize this clip. Check your API key and quota."
                }
                val result = response.optJSONObject("result") ?: return@cancellable null
                val title = result.getString("title")
                val artist = result.getString("artist")
                SongRecognition(
                    Song(
                        "audd:$artist:$title",
                        title,
                        artist,
                        result.optString("song_link").takeIf { it.startsWith("https://") },
                    ),
                    "Online · AudD",
                )
            }
        }

    private fun wav(samples: ShortArray): ByteArray {
        val buffer =
            java.nio.ByteBuffer.allocate(44 + samples.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer
            .put("RIFF".toByteArray())
            .putInt(36 + samples.size * 2)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
        buffer.putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }
}

/** Disconnect the blocking socket as soon as its awaiting session is cancelled. */
private suspend fun <T> HttpURLConnection.cancellable(request: suspend () -> T): T =
    coroutineScope {
        val operation = async(Dispatchers.IO) { request() }
        try {
            operation.await()
        } finally {
            disconnect()
        }
    }
