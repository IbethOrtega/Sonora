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
import com.example.model.Track
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
    val connectedAt: Long = System.currentTimeMillis()
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

fun SavedCollectionEntity.toBrowseCard() = BrowseCard(
    id = browseId,
    title = title,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    kind = runCatching { ItemKind.valueOf(kind) }.getOrDefault(ItemKind.ALBUM)
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun observeLikedSongs(): Flow<List<LikedSongEntity>>

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

    @Query("SELECT playCount FROM recent_tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getRecentPlayCount(videoId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentTrack(track: RecentTrackEntity)

    @Query("DELETE FROM recent_tracks")
    suspend fun clearRecentTracks()

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
}

@Database(
    entities = [
        LikedSongEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        RecentTrackEntity::class,
        SavedCollectionEntity::class,
        SearchHistoryEntity::class,
        AccountSessionEntity::class
    ],
    version = 3,
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

        fun getInstance(context: Context): SonoraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SonoraDatabase::class.java,
                    "sonora_library.db"
                ).addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
