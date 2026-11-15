package com.example.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.R
import com.example.audio.NativeAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class LibraryRepository(
    private val context: Context,
    private val db: SonaDatabase
) {
    private val trackDao = db.trackDao()
    private val profileDao = db.hardwareProfileDao()

    val allTracks: Flow<List<TrackEntity>> = trackDao.getAllTracks()
    val trackCount: Flow<Int> = trackDao.getTrackCount()
    val allProfiles: Flow<List<HardwareProfileEntity>> = profileDao.getAllProfiles()

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.searchTracks(query)

    suspend fun ensureDefaultsSeeded() = withContext(Dispatchers.IO) {
        val defaultProfiles = listOf(
            HardwareProfileEntity.defaultFor("USB_DAC"),
            HardwareProfileEntity.defaultFor("BLUETOOTH"),
            HardwareProfileEntity.defaultFor("WIRED_AUX"),
            HardwareProfileEntity.defaultFor("SPEAKER")
        )
        profileDao.insertDefaults(defaultProfiles)

        if (trackDao.countOnce() == 0) {
            val pkg = context.packageName
            val referenceTracks = listOf(
                TrackEntity(
                    uri = "android.resource://$pkg/${R.raw.sona_ref_obsidian}",
                    filePath = "raw://sona_ref_obsidian.wav",
                    title = "Obsidian Resonance (A-Minor 48kHz Ref)",
                    artist = "Sona Reference Mastering",
                    album = "Acoustic Calibration Vol. I",
                    genre = "Audiophile Reference",
                    durationMs = 4000L,
                    sampleRateHz = 48000,
                    bitDepth = 24,
                    bitrateKbps = 2304,
                    codec = "WAV",
                    channelCount = 2,
                    isDsd = false,
                    artworkResName = "img_album_obsidian_waves"
                ),
                TrackEntity(
                    uri = "android.resource://$pkg/${R.raw.sona_ref_aurora}",
                    filePath = "raw://sona_ref_aurora.wav",
                    title = "Aurora Glass Harmonics (G-Maj7 Ref)",
                    artist = "Sona Reference Mastering",
                    album = "Acoustic Calibration Vol. II",
                    genre = "Spatial Test",
                    durationMs = 4000L,
                    sampleRateHz = 48000,
                    bitDepth = 24,
                    bitrateKbps = 2304,
                    codec = "WAV",
                    channelCount = 2,
                    isDsd = false,
                    artworkResName = "img_album_aurora_vinyl"
                )
            )
            trackDao.insertTracks(referenceTracks)
        }
    }

    suspend fun getOrCreateProfile(deviceKey: String): HardwareProfileEntity = withContext(Dispatchers.IO) {
        profileDao.getProfile(deviceKey) ?: HardwareProfileEntity.defaultFor(deviceKey).also {
            profileDao.upsertProfile(it)
        }
    }

    suspend fun saveProfile(profile: HardwareProfileEntity) = withContext(Dispatchers.IO) {
        profileDao.upsertProfile(profile)
    }

    /**
     * Deep library scan via MediaStore + TagLib JNI (when native .so loaded) / MediaMetadataRetriever.
     * Batches inserts for 20,000+ track performance.
     */
    suspend fun scanDeviceMediaStore(): Int = withContext(Dispatchers.IO) {
        val collected = mutableListOf<TrackEntity>()
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                val nativeTag = if (path.isNotBlank() && File(path).canRead()) {
                    NativeAudioEngine.scanTrackWithTagLib(path)
                } else {
                    null
                }

                val mime = cursor.getString(mimeCol) ?: "audio/*"
                val codec = nativeTag?.codec?.takeIf { it.isNotBlank() }
                    ?: inferCodec(mime, path)
                val isDsd = codec.equals("DSF", true) || codec.equals("DFF", true) || codec.equals("DSD", true)

                val retrieverMeta = if (nativeTag == null) inspectUriMetadata(contentUri) else null
                val title = nativeTag?.title?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(titleCol).orEmpty().ifBlank { "Untitled Track" }
                val artist = nativeTag?.artist?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(artistCol).orEmpty().ifBlank { "Unknown Artist" }
                val album = nativeTag?.album?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(albumCol).orEmpty().ifBlank { "Unknown Album" }

                collected += TrackEntity(
                    uri = contentUri.toString(),
                    filePath = path.ifBlank { contentUri.toString() },
                    title = title,
                    artist = artist,
                    album = album,
                    genre = nativeTag?.genre?.ifBlank { "Hi-Res Audio" } ?: "Hi-Res Audio",
                    durationMs = (nativeTag?.durationMs?.toLong()?.takeIf { it > 0 }
                        ?: cursor.getLong(durationCol)).coerceAtLeast(1000L),
                    sampleRateHz = nativeTag?.sampleRateHz ?: retrieverMeta?.sampleRateHz ?: 48000,
                    bitDepth = nativeTag?.bitDepth ?: retrieverMeta?.bitDepth ?: 16,
                    bitrateKbps = nativeTag?.bitrateKbps ?: retrieverMeta?.bitrateKbps ?: 320,
                    codec = codec,
                    channelCount = nativeTag?.channels ?: 2,
                    isDsd = isDsd
                )

                if (collected.size >= 500) {
                    trackDao.insertTracks(collected)
                    collected.clear()
                }
            }
        }

        if (collected.isNotEmpty()) {
            trackDao.insertTracks(collected)
        }
        return@withContext trackDao.countOnce()
    }

    /**
     * Zero-permission SAF import for FLAC, WAV, DSF, ISO, ALAC, MP3 files.
     */
    suspend fun importAudioUris(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val imported = mutableListOf<TrackEntity>()
        for (uri in uris) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val meta = inspectUriMetadata(uri)
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Imported Track"
            val cleanTitle = meta.title.ifBlank { displayName.substringBeforeLast('.') }
            val ext = displayName.substringAfterLast('.', "").uppercase()
            val codec = meta.codec.ifBlank { ext.ifBlank { "PCM" } }
            val isDsd = codec == "DSF" || codec == "DFF" || codec == "ISO"

            imported += TrackEntity(
                uri = uri.toString(),
                filePath = uri.toString(),
                title = cleanTitle,
                artist = meta.artist.ifBlank { "Imported Master" },
                album = meta.album.ifBlank { "Local Hi-Res" },
                genre = meta.genre.ifBlank { "Lossless" },
                durationMs = meta.durationMs.coerceAtLeast(1000L),
                sampleRateHz = meta.sampleRateHz,
                bitDepth = meta.bitDepth,
                bitrateKbps = meta.bitrateKbps,
                codec = codec,
                channelCount = 2,
                isDsd = isDsd
            )
        }
        if (imported.isNotEmpty()) {
            trackDao.insertTracks(imported)
        }
        imported.size
    }

    private data class InspectedMeta(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val genre: String = "",
        val codec: String = "",
        val durationMs: Long = 0L,
        val sampleRateHz: Int = 48000,
        val bitDepth: Int = 16,
        val bitrateKbps: Int = 320
    )

    private fun inspectUriMetadata(uri: Uri): InspectedMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 320000) / 1000
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 48000
            } else 48000
            val bits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 16
            } else 16
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            InspectedMeta(
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                codec = inferCodec(mime, uri.toString()),
                durationMs = duration,
                sampleRateHz = sampleRate,
                bitDepth = bits,
                bitrateKbps = bitrate
            )
        } catch (_: Exception) {
            InspectedMeta()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun inferCodec(mime: String, path: String): String {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.endsWith(".flac") || mime.contains("flac", true) -> "FLAC"
            lowerPath.endsWith(".wav") || mime.contains("wav", true) -> "WAV"
            lowerPath.endsWith(".dsf") -> "DSF"
            lowerPath.endsWith(".dff") -> "DFF"
            lowerPath.endsWith(".iso") -> "SACD ISO"
            lowerPath.endsWith(".alac") || lowerPath.endsWith(".m4a") -> "ALAC/AAC"
            lowerPath.endsWith(".opus") || mime.contains("opus", true) -> "OPUS"
            lowerPath.endsWith(".ogg") || mime.contains("ogg", true) -> "VORBIS"
            mime.contains("mpeg", true) || lowerPath.endsWith(".mp3") -> "MP3"
            else -> "PCM"
        }
    }
}
