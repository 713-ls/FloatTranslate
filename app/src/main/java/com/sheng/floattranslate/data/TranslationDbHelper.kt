package com.sheng.floattranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sheng.floattranslate.model.TranslationResult

class TranslationDbHelper(context: Context) :
    SQLiteOpenHelper(context, "float_translate.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE translations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query_key TEXT UNIQUE,
                cleaned_query TEXT,
                translated_text TEXT,
                phonetic TEXT,
                source_label TEXT,
                created_at INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS translations")
        onCreate(db)
    }

    fun getCached(key: String): TranslationResult? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT cleaned_query, translated_text, phonetic, source_label FROM translations WHERE query_key = ? LIMIT 1",
            arrayOf(key)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return TranslationResult(
                    query = key,
                    cleanedQuery = cursor.getString(0),
                    translatedText = cursor.getString(1),
                    phonetic = cursor.getString(2),
                    sourceLabel = "本地缓存 (${cursor.getString(3)})"
                )
            }
        }
        return null
    }

    fun save(key: String, result: TranslationResult) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("query_key", key)
            put("cleaned_query", result.cleanedQuery)
            put("translated_text", result.translatedText)
            put("phonetic", result.phonetic)
            put("source_label", result.sourceLabel)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("translations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getRecentHistory(limit: Int = 10): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT cleaned_query, translated_text FROM translations ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0) to cursor.getString(1))
            }
        }
        return list
    }
}
