package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.BrowseCard
import com.example.model.ItemKind
import com.example.model.PlaylistTransitionSpec
import com.example.model.Track
import com.example.model.TransitionCurveType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val artworkUrl: String,
    val durationText: String,
    val durationMs: Long,
    val likedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val coverUrl: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "videoId"]
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val artworkUrl: String,
    val durationText: String,
    val durationMs: Long,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recent_tracks")
data class RecentTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val artworkUrl: String,
    val durationText: String,
    val durationMs: Long,
    val playedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1
)

@Entity(tableName = "saved_collections")
data class SavedCollectionEntity(
    @PrimaryKey val browseId: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val kind: String,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "account_session")
data class AccountSessionEntity(
    @PrimaryKey val id: Int = 1,
    val cookies: String,
    val accountName: String,
    val accountHandle: String = "",
    val avatarUrl: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val delegatedSessionId: String = "",
    val authUser: String = "0",
    val clientVersion: String = "",
    val connectedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloaded_tracks")
data class DownloadedTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val artworkUrl: String,
    val durationText: String,
    val durationMs: Long,
    val filePath: String,
    val fileSizeBytes: Long = 0L,
    val codecLabel: String = "Offline",
    val downloadedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_tracks")
data class LocalTrackEntity(
    @PrimaryKey val videoId: String,
    val contentUri: String,
    val folderTreeUri: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String,
    val lrcUri: String?,
    val durationText: String,
    val durationMs: Long,
    val mimeType: String,
    val bitrateBps: Int = 0,
    val sampleRateHz: Int = 0,
    val fileSizeBytes: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_transitions",
    primaryKeys = ["playlistId", "fromVideoId", "toVideoId"]
)
data class PlaylistTransitionEntity(
    val playlistId: Long,
    val fromVideoId: String,
    val toVideoId: String,
    val fromExitMs: Long = 0L,
    val toEntryMs: Long = 0L,
    val crossfadeMs: Int = 5000,
    val curveType: String = TransitionCurveType.SMOOTH_FADE.name,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "queue_seed_history",
    primaryKeys = ["seedVideoId", "suggestedVideoId"]
)
data class QueueSeedHistoryEntity(
    val seedVideoId: String,
    val suggestedVideoId: String,
    val generation: Int = 1,
    val shownAtMs: Long = System.currentTimeMillis()
)

fun LikedSongEntity.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    artworkUrl = artworkUrl,
    durationText = durationText,
    durationMs = durationMs
)

fun PlaylistTrackEntity.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    artworkUrl = artworkUrl,
    durationText = durationText,
    durationMs = durationMs
)

fun RecentTrackEntity.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    artworkUrl = artworkUrl,
    durationText = durationText,
    durationMs = durationMs
)

fun DownloadedTrackEntity.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    artworkUrl = artworkUrl,
    durationText = durationText,
    durationMs = durationMs
)

fun LocalTrackEntity.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    durationText = durationText,
    durationMs = durationMs,
    localUri = contentUri,
    localLrcUri = lrcUri,
    isLocal = true
)

fun SavedCollectionEntity.toBrowseCard() = BrowseCard(
    id = browseId,
    title = title,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    kind = runCatching { ItemKind.valueOf(kind) }.getOrDefault(ItemKind.ALBUM)
)

fun PlaylistTransitionEntity.toSpec() = PlaylistTransitionSpec(
    playlistId = playlistId,
    fromVideoId = fromVideoId,
    toVideoId = toVideoId,
    fromExitMs = fromExitMs,
    toEntryMs = toEntryMs,
    crossfadeMs = crossfadeMs,
    curveType = runCatching { TransitionCurveType.valueOf(curveType) }.getOrDefault(TransitionCurveType.SMOOTH_FADE)
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun observeLikedSongs(): Flow<List<LikedSongEntity>>

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getAllLikedSongsOnce(): List<LikedSongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :videoId)")
    fun observeIsLiked(videoId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :videoId)")
    suspend fun isLiked(videoId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSong(song: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE videoId = :videoId")
    suspend fun deleteLikedSong(videoId: String)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("UPDATE playlists SET coverUrl = :coverUrl WHERE id = :playlistId AND coverUrl = ''")
    suspend fun updatePlaylistCoverIfEmpty(playlistId: Long, coverUrl: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    fun observePlaylistTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    suspend fun getPlaylistTracksOnce(playlistId: Long): List<PlaylistTrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    fun observePlaylistTrackCount(playlistId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, videoId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Query("SELECT * FROM recent_tracks ORDER BY playedAt DESC LIMIT 30")
    fun observeRecentTracks(): Flow<List<RecentTrackEntity>>

    @Query("SELECT * FROM recent_tracks ORDER BY playCount DESC, playedAt DESC LIMIT 20")
    fun observeMostPlayedTracks(): Flow<List<RecentTrackEntity>>

    @Query("SELECT * FROM recent_tracks ORDER BY playCount DESC, playedAt DESC")
    fun observeAllRecentTracksForStats(): Flow<List<RecentTrackEntity>>

    @Query("SELECT playCount FROM recent_tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getRecentPlayCount(videoId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentTrack(track: RecentTrackEntity)

    @Query("DELETE FROM recent_tracks")
    suspend fun clearRecentTracks()

    @Query("UPDATE liked_songs SET artistId = COALESCE(NULLIF(:artistId, ''), artistId), album = COALESCE(NULLIF(:album, ''), album), albumId = COALESCE(NULLIF(:albumId, ''), albumId) WHERE videoId = :videoId")
    suspend fun updateLikedSongMetadata(videoId: String, artistId: String?, album: String?, albumId: String?)

    @Query("UPDATE playlist_tracks SET artistId = COALESCE(NULLIF(:artistId, ''), artistId), album = COALESCE(NULLIF(:album, ''), album), albumId = COALESCE(NULLIF(:albumId, ''), albumId) WHERE videoId = :videoId")
    suspend fun updatePlaylistTrackMetadata(videoId: String, artistId: String?, album: String?, albumId: String?)

    @Query("UPDATE recent_tracks SET artistId = COALESCE(NULLIF(:artistId, ''), artistId), album = COALESCE(NULLIF(:album, ''), album), albumId = COALESCE(NULLIF(:albumId, ''), albumId) WHERE videoId = :videoId")
    suspend fun updateRecentTrackMetadata(videoId: String, artistId: String?, album: String?, albumId: String?)

    @Query("SELECT * FROM saved_collections ORDER BY savedAt DESC")
    fun observeSavedCollections(): Flow<List<SavedCollectionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_collections WHERE browseId = :browseId)")
    fun observeIsCollectionSaved(browseId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedCollection(collection: SavedCollectionEntity)

    @Query("DELETE FROM saved_collections WHERE browseId = :browseId")
    suspend fun deleteSavedCollection(browseId: String)

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 10")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchHistory(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    @Query("SELECT * FROM account_session WHERE id = 1")
    fun observeAccountSession(): Flow<AccountSessionEntity?>

    @Query("SELECT * FROM account_session WHERE id = 1")
    suspend fun getAccountSession(): AccountSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAccountSession(session: AccountSessionEntity)

    @Query("DELETE FROM account_session")
    suspend fun clearAccountSession()

    // --- Offline Downloaded Tracks ---
    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun observeDownloadedTracks(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getDownloadedTrack(videoId: String): DownloadedTrackEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_tracks WHERE videoId = :videoId)")
    fun observeIsDownloaded(videoId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedTrack(track: DownloadedTrackEntity)

    @Query("DELETE FROM downloaded_tracks WHERE videoId = :videoId")
    suspend fun deleteDownloadedTrack(videoId: String)

    @Query("DELETE FROM downloaded_tracks")
    suspend fun clearDownloadedTracks()

    // --- Local Device Audio Tracks (SAF) ---
    @Query("SELECT * FROM local_tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeLocalTracks(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getLocalTrackById(videoId: String): LocalTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalTracks(tracks: List<LocalTrackEntity>)

    @Query("DELETE FROM local_tracks WHERE folderTreeUri = :folderTreeUri")
    suspend fun deleteLocalTracksByFolder(folderTreeUri: String)

    @Query("DELETE FROM local_tracks")
    suspend fun clearAllLocalTracks()

    // --- Playlist Fragment Transitions ---
    @Query("SELECT * FROM playlist_transitions WHERE playlistId = :playlistId")
    fun observePlaylistTransitions(playlistId: Long): Flow<List<PlaylistTransitionEntity>>

    @Query("SELECT * FROM playlist_transitions WHERE playlistId = :playlistId")
    suspend fun getPlaylistTransitionsOnce(playlistId: Long): List<PlaylistTransitionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistTransition(entity: PlaylistTransitionEntity)

    @Query("DELETE FROM playlist_transitions WHERE playlistId = :playlistId AND fromVideoId = :fromVideoId AND toVideoId = :toVideoId")
    suspend fun deletePlaylistTransition(playlistId: Long, fromVideoId: String, toVideoId: String)

    // --- Smart Queue Seed Rotation Memory ---
    @Query("SELECT * FROM queue_seed_history WHERE seedVideoId = :seedVideoId ORDER BY shownAtMs DESC")
    suspend fun getQueueSeedHistory(seedVideoId: String): List<QueueSeedHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueueSeedHistory(entries: List<QueueSeedHistoryEntity>)

    @Query(
        """
        DELETE FROM queue_seed_history
        WHERE seedVideoId = :seedVideoId
          AND suggestedVideoId NOT IN (
            SELECT suggestedVideoId FROM queue_seed_history
            WHERE seedVideoId = :seedVideoId
            ORDER BY shownAtMs DESC
            LIMIT :keepCount
          )
        """
    )
    suspend fun trimQueueSeedHistory(seedVideoId: String, keepCount: Int = 64)
}

@Database(
    entities = [
        LikedSongEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        RecentTrackEntity::class,
        SavedCollectionEntity::class,
        SearchHistoryEntity::class,
        AccountSessionEntity::class,
        DownloadedTrackEntity::class,
        LocalTrackEntity::class,
        PlaylistTransitionEntity::class,
        QueueSeedHistoryEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class SonoraDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var INSTANCE: SonoraDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recent_tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_tracks (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        artistId TEXT,
                        album TEXT,
                        albumId TEXT,
                        artworkUrl TEXT NOT NULL,
                        durationText TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        filePath TEXT NOT NULL,
                        fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                        codecLabel TEXT NOT NULL DEFAULT 'Offline',
                        downloadedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_tracks (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        contentUri TEXT NOT NULL,
                        folderTreeUri TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT,
                        artworkUrl TEXT NOT NULL,
                        lrcUri TEXT,
                        durationText TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        mimeType TEXT NOT NULL,
                        bitrateBps INTEGER NOT NULL DEFAULT 0,
                        sampleRateHz INTEGER NOT NULL DEFAULT 0,
                        fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_transitions (
                        playlistId INTEGER NOT NULL,
                        fromVideoId TEXT NOT NULL,
                        toVideoId TEXT NOT NULL,
                        fromExitMs INTEGER NOT NULL DEFAULT 0,
                        toEntryMs INTEGER NOT NULL DEFAULT 0,
                        crossfadeMs INTEGER NOT NULL DEFAULT 5000,
                        curveType TEXT NOT NULL DEFAULT 'SMOOTH_FADE',
                        updatedAtMs INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(playlistId, fromVideoId, toVideoId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queue_seed_history (
                        seedVideoId TEXT NOT NULL,
                        suggestedVideoId TEXT NOT NULL,
                        generation INTEGER NOT NULL DEFAULT 1,
                        shownAtMs INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(seedVideoId, suggestedVideoId)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account_session ADD COLUMN visitorData TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE account_session ADD COLUMN dataSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE account_session ADD COLUMN delegatedSessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE account_session ADD COLUMN authUser TEXT NOT NULL DEFAULT '0'")
                db.execSQL("ALTER TABLE account_session ADD COLUMN clientVersion TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): SonoraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SonoraDatabase::class.java,
                    "sonora_library.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
