package com.newspaper.helper

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SpeechMigrationTest {
    @Test fun versionOneDatabaseRetainsArticlesQueueAndReadingPosition() {
        val context=RuntimeEnvironment.getApplication()
        val name="speech-migration.db"
        context.deleteDatabase(name)
        val old=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object:SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE articles (url TEXT NOT NULL PRIMARY KEY,payload TEXT NOT NULL,fetched INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE positions (url TEXT NOT NULL PRIMARY KEY,block INTEGER NOT NULL,offset INTEGER NOT NULL,segment INTEGER NOT NULL,audioMs INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE playback_queue (id INTEGER NOT NULL PRIMARY KEY,payload TEXT NOT NULL)")
                    db.execSQL("INSERT INTO articles VALUES ('news','body',123)")
                    db.execSQL("INSERT INTO positions VALUES ('news',7,19,2,3456)")
                    db.execSQL("INSERT INTO playback_queue VALUES (1,'queue')")
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
            }).build())
        old.writableDatabase;old.close()
        val reader=Room.databaseBuilder(context,ReaderDatabase::class.java,name).addMigrations(ReaderDatabase.MIGRATION_1_2,ReaderDatabase.MIGRATION_2_3)
            .allowMainThreadQueries().build()
        try {
            val db=reader.openHelper.writableDatabase
            db.query("SELECT * FROM positions WHERE url='news'").use {
                assertTrue(it.moveToFirst());assertEquals(7,it.getInt(it.getColumnIndexOrThrow("block")))
                assertEquals(19,it.getInt(it.getColumnIndexOrThrow("offset")))
                assertEquals(3456L,it.getLong(it.getColumnIndexOrThrow("audioMs")))
                assertEquals("",it.getString(it.getColumnIndexOrThrow("audioFingerprint")))
            }
            db.query("SELECT payload FROM articles").use { assertTrue(it.moveToFirst());assertEquals("body",it.getString(0)) }
            db.query("SELECT payload FROM playback_queue").use { assertTrue(it.moveToFirst());assertEquals("queue",it.getString(0)) }
            db.query("SELECT count(*) FROM pasted_notes").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        } finally { reader.close();context.deleteDatabase(name) }
    }
    @Test fun notesRetainFullTextPinOrderAndDeleteOnlySelectedRows() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val database=Room.inMemoryDatabaseBuilder(context,ReaderDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=database.reader();val longText="完整正文😀\n".repeat(1000)
            dao.saveArticle(ArticleRecord("news","old article",1))
            dao.saveNote(PastedNote("first","标题",longText,1,2))
            dao.saveNote(PastedNote("second","另一条","原文",2,3))
            assertEquals(longText,dao.note("first")!!.content)
            assertTrue(dao.notes().first().first {it.id=="first"}.preview.codePointCount(0,dao.notes().first().first {it.id=="first"}.preview.length)<=240)
            dao.pinNotes(listOf("first"),true)
            assertEquals("first",dao.notes().first().first().id)
            dao.saveNote(dao.note("first")!!.copy(title="编辑后的标题",updated=4))
            assertEquals(2,dao.notes().first().size)
            dao.deleteNotes(listOf("second"))
            assertEquals("first",dao.notes().first().single().id)
            assertEquals("old article",dao.article("news")!!.payload)
        } finally {database.close()}
    }
}
