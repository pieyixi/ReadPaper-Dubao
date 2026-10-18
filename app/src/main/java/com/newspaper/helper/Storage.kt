package com.newspaper.helper

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val jsonCodec = Gson()
@Entity(tableName = "articles")
data class ArticleRecord(@PrimaryKey val url: String, val payload: String, val fetched: Long)
@Entity(tableName = "positions")
data class ReadingPosition(@PrimaryKey val url: String, val block: Int = 0, val offset: Int = 0, val segment: Int = 0, val audioMs: Long = 0)
@Entity(tableName = "playback_queue")
data class QueueRecord(@PrimaryKey val id: Int = 1, val payload: String)
@Dao
interface ReaderDao {
    @Query("SELECT * FROM articles WHERE url=:url") suspend fun article(url: String): ArticleRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveArticle(value: ArticleRecord)
    @Query("SELECT * FROM positions WHERE url=:url") suspend fun position(url: String): ReadingPosition?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePosition(value: ReadingPosition)
    @Query("UPDATE positions SET segment=:segment, audioMs=:ms WHERE url=:url") suspend fun audioPosition(url: String, segment: Int, ms: Long)
    @Query("UPDATE positions SET block=:block, offset=:offset WHERE url=:url") suspend fun readPosition(url: String, block: Int, offset: Int)
    @Query("SELECT * FROM playback_queue WHERE id=1") suspend fun queue(): QueueRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveQueue(queue: QueueRecord)
}
@Database(entities = [ArticleRecord::class, ReadingPosition::class, QueueRecord::class], version = 1, exportSchema = false)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun reader(): ReaderDao
    companion object {
        @Volatile private var value: ReaderDatabase? = null
        fun get(context: Context): ReaderDatabase = value ?: synchronized(this) {
            value ?: Room.databaseBuilder(context.applicationContext, ReaderDatabase::class.java, "native-reader.db").build().also { value = it }
        }
    }
}
class ArticleRepository(context: Context, val sources: NewsSources = NewsSources()) {
    val dao = ReaderDatabase.get(context).reader()
    suspend fun article(story: Story, refresh: Boolean = false): Article = withContext(Dispatchers.IO) {
        val record = dao.article(story.url)
        if (!refresh && record != null && System.currentTimeMillis() - record.fetched < 30 * 60 * 1000) return@withContext jsonCodec.fromJson(record.payload, Article::class.java)
        val article = sources.article(story)
        dao.saveArticle(ArticleRecord(story.url, jsonCodec.toJson(article), System.currentTimeMillis()))
        if (dao.position(story.url) == null) dao.savePosition(ReadingPosition(story.url))
        article
    }
}
private val Context.readerSettings by preferencesDataStore("native-reader-settings")
data class ReaderSettings(val theme: Int = 0, val font: Int = 2, val line: Int = 2, val route: String = "")
class SettingsStore(private val context: Context) {
    val flow = context.readerSettings.data.map { ReaderSettings(it[intPreferencesKey("theme")] ?: 0, it[intPreferencesKey("font")] ?: 2, it[intPreferencesKey("line")] ?: 2, it[stringPreferencesKey("route")].orEmpty()) }
    suspend fun appearance(theme: Int, font: Int, line: Int) { context.readerSettings.edit { it[intPreferencesKey("theme")] = theme.coerceIn(0, 2); it[intPreferencesKey("font")] = font.coerceIn(0, 3); it[intPreferencesKey("line")] = line.coerceIn(0, 3) } }
    suspend fun route(value: String) { context.readerSettings.edit { it[stringPreferencesKey("route")] = value } }
}
