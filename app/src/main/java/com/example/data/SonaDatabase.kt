package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["uri"], unique = true)
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val codec: String,
    val channelCount: Int,
    val isDsd: Boolean = false,
    val artworkResName: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
) {
    val isHiRes: Boolean
        get() = sampleRateHz > 48000 || bitDepth >= 24 || isDsd || codec.equals("FLAC", true) || codec.equals("WAV", true) || codec.equals("DSD", true)
}

@Entity(tableName = "hardware_profiles")
data class HardwareProfileEntity(
    @PrimaryKey val deviceKey: String,
    val displayName: String,
    val preampDb: Float = 0f,
    val eqBandsCsv: String = "0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0",
    val crossfeedLevel: Float = 0.0f,
    val dvcEnabled: Boolean = true,
    val dvcAttenuationDb: Float = -6.0f,
    val crossfadeMs: Int = 0,
    val dsdOversamplingFactor: Int = 0, // 0 = PCM Native, 64 = DSD64, 128 = DSD128, 256 = DSD256
    val bitPerfectEnabled: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f
) {
    fun eqBandsList(): List<Float> {
        val parsed = eqBandsCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (parsed.size == 10) parsed else List(10) { 0f }
    }

    companion object {
        val EQ_FREQUENCIES_HZ = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        fun defaultFor(deviceKey: String): HardwareProfileEntity = when (deviceKey) {
            "USB_DAC" -> HardwareProfileEntity(
                deviceKey = "USB_DAC",
                displayName = "USB Hi-Fi DAC",
                preampDb = 0.0f,
                eqBandsCsv = "0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0",
                crossfeedLevel = 0.15f,
                dvcEnabled = true,
                dvcAttenuationDb = -3.0f,
                crossfadeMs = 0,
                dsdOversamplingFactor = 128,
                bitPerfectEnabled = true
            )
            "BLUETOOTH" -> HardwareProfileEntity(
                deviceKey = "BLUETOOTH",
                displayName = "Bluetooth LDAC/aptX",
                preampDb = -1.5f,
                eqBandsCsv = "2.5,2.0,1.0,0.0,-0.5,0.0,1.0,2.0,2.5,1.5",
                crossfeedLevel = 0.25f,
                dvcEnabled = true,
                dvcAttenuationDb = -6.0f,
                crossfadeMs = 1500,
                dsdOversamplingFactor = 0,
                bitPerfectEnabled = false
            )
            "WIRED_AUX" -> HardwareProfileEntity(
                deviceKey = "WIRED_AUX",
                displayName = "3.5mm Wired / AUX",
                preampDb = -0.5f,
                eqBandsCsv = "1.5,1.0,0.5,0.0,0.0,0.5,1.0,1.5,1.5,1.0",
                crossfeedLevel = 0.35f,
                dvcEnabled = true,
                dvcAttenuationDb = -4.0f,
                crossfadeMs = 0,
                dsdOversamplingFactor = 64,
                bitPerfectEnabled = false
            )
            else -> HardwareProfileEntity(
                deviceKey = "SPEAKER",
                displayName = "Device Speaker",
                preampDb = 1.0f,
                eqBandsCsv = "-2.0,0.0,1.5,2.0,1.0,0.5,1.5,2.5,2.0,1.0",
                crossfeedLevel = 0.0f,
                dvcEnabled = false,
                dvcAttenuationDb = 0.0f,
                crossfadeMs = 2000,
                dsdOversamplingFactor = 0,
                bitPerfectEnabled = false
            )
        }
    }
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    fun getTrackCount(): Flow<Int>

    @Query(
        "SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' " +
            "OR artist LIKE '%' || :query || '%' " +
            "OR album LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC"
    )
    fun searchTracks(query: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countOnce(): Int

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)
}

@Dao
interface HardwareProfileDao {
    @Query("SELECT * FROM hardware_profiles")
    fun getAllProfiles(): Flow<List<HardwareProfileEntity>>

    @Query("SELECT * FROM hardware_profiles WHERE deviceKey = :deviceKey LIMIT 1")
    suspend fun getProfile(deviceKey: String): HardwareProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: HardwareProfileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaults(profiles: List<HardwareProfileEntity>)
}

@Database(
    entities = [TrackEntity::class, HardwareProfileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SonaDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun hardwareProfileDao(): HardwareProfileDao

    companion object {
        @Volatile
        private var INSTANCE: SonaDatabase? = null

        fun getInstance(context: Context): SonaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SonaDatabase::class.java,
                    "sona_audiophile_library.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
