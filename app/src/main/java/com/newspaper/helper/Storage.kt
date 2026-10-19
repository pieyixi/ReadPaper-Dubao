package com.newspaper.helper

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.ColumnInfo
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
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

val jsonCodec = Gson()
@Entity(tableName = "articles")
data class ArticleRecord(@PrimaryKey val url: String, val payload: String, val fetched: Long)
@Entity(tableName = "positions")
data class ReadingPosition(@PrimaryKey val url: String, val block: Int = 0, val offset: Int = 0, val segment: Int = 0, val audioMs: Long = 0,
    @ColumnInfo(defaultValue="''") val audioBlock:String="",
    @ColumnInfo(defaultValue="0") val audioOffset:Int=0,
    @ColumnInfo(defaultValue="''") val audioFingerprint:String="")
@Entity(tableName = "playback_queue")
data class QueueRecord(@PrimaryKey val id: Int = 1, val payload: String)
@Entity(tableName = "pasted_notes")
data class PastedNote(@PrimaryKey val id:String,val title:String="",val content:String="",val created:Long=System.currentTimeMillis(),val updated:Long=created,val pinned:Boolean=false)
data class NotePreview(val id:String,val title:String,val preview:String,val updated:Long,val pinned:Boolean)
@Dao
interface ReaderDao {
    @Query("SELECT id,title,substr(content,1,240) AS preview,updated,pinned FROM pasted_notes ORDER BY pinned DESC,updated DESC,id ASC")
    fun notes():Flow<List<NotePreview>>
    @Query("SELECT * FROM pasted_notes WHERE id=:id") suspend fun note(id:String):PastedNote?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveNote(note:PastedNote)
    @Query("UPDATE pasted_notes SET pinned=:pinned WHERE id IN (:ids)") suspend fun pinNotes(ids:List<String>,pinned:Boolean)
    @Query("DELETE FROM pasted_notes WHERE id IN (:ids)") suspend fun deleteNotes(ids:List<String>)
    @Query("SELECT * FROM articles WHERE url=:url") suspend fun article(url: String): ArticleRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveArticle(value: ArticleRecord)
    @Query("SELECT * FROM positions WHERE url=:url") suspend fun position(url: String): ReadingPosition?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePosition(value: ReadingPosition)
    @Query("UPDATE positions SET segment=:segment, audioMs=:ms, audioBlock=:blockId, audioOffset=:sourceOffset, audioFingerprint=:fingerprint WHERE url=:url")
    suspend fun audioPosition(url: String, segment: Int, ms: Long, blockId:String="", sourceOffset:Int=0, fingerprint:String="")
    @Query("UPDATE positions SET block=:block, offset=:offset WHERE url=:url") suspend fun readPosition(url: String, block: Int, offset: Int)
    @Query("SELECT * FROM playback_queue WHERE id=1") suspend fun queue(): QueueRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveQueue(queue: QueueRecord)
}
@Database(entities = [ArticleRecord::class, ReadingPosition::class, QueueRecord::class,PastedNote::class], version = 3, exportSchema = false)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun reader(): ReaderDao
    companion object {
        // 便签独立保存，不改写文章缓存、阅读位置和原有队列。
        val MIGRATION_2_3=object:Migration(2,3) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pasted_notes (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,content TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,pinned INTEGER NOT NULL)")
            }
        }
        // 只增加朗读锚点；保留原有文章、队列和阅读位置，禁止破坏性迁移。
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE positions ADD COLUMN audioBlock TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE positions ADD COLUMN audioOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE positions ADD COLUMN audioFingerprint TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var value: ReaderDatabase? = null
        fun get(context: Context): ReaderDatabase = value ?: synchronized(this) {
            value ?: Room.databaseBuilder(context.applicationContext, ReaderDatabase::class.java, "native-reader.db")
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also { value = it }
        }
    }
}
class ArticleRepository(context: Context, val sources: NewsSources = NewsSources()) {
    val dao = ReaderDatabase.get(context).reader()
    suspend fun article(story: Story, refresh: Boolean = false): Article = withContext(Dispatchers.IO) {
        val record = dao.article(story.url)
        if(!refresh && record!=null && System.currentTimeMillis()-record.fetched<30*60*1000) {
            val cached=jsonCodec.fromJson(record.payload,Article::class.java)
            // 升级后仅让旧求是解析缓存重新获取，不能继续展示旧页尾，也不清除便签、位置或音频缓存。
            if(story.paper!=Paper.QIUSHI || cached.parserVersion==ContentParser.VERSION)return@withContext cached
        }
        val article = sources.article(story)
        dao.saveArticle(ArticleRecord(story.url, jsonCodec.toJson(article), System.currentTimeMillis()))
        if (dao.position(story.url) == null) dao.savePosition(ReadingPosition(story.url))
        article
    }
}
private val Context.readerSettings by preferencesDataStore("native-reader-settings")
data class ReaderSettings(val theme: Int = 0, val font: Int = 2, val line: Int = 2, val route: String = "")
class SettingsStore(private val context: Context) {
    suspend fun playbackMode():PlaybackMode = PlaybackMode.restore(context.readerSettings.data.first()[stringPreferencesKey("playback-mode")])
    suspend fun playbackMode(mode:PlaybackMode) { context.readerSettings.edit { it[stringPreferencesKey("playback-mode")]=mode.name } }
    val flow = context.readerSettings.data.map { ReaderSettings(it[intPreferencesKey("theme")] ?: 0, it[intPreferencesKey("font")] ?: 2, it[intPreferencesKey("line")] ?: 2, it[stringPreferencesKey("route")].orEmpty()) }
    suspend fun appearance(theme: Int, font: Int, line: Int) { context.readerSettings.edit { it[intPreferencesKey("theme")] = theme.coerceIn(0, 2); it[intPreferencesKey("font")] = font.coerceIn(0, 3); it[intPreferencesKey("line")] = line.coerceIn(0, 3) } }
    suspend fun route(value: String) { context.readerSettings.edit { it[stringPreferencesKey("route")] = value } }
}
