package com.sheng.floattranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TranslationDbHelper(context: Context) :
    SQLiteOpenHelper(context, "float_translate_v2.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cache (cache_key TEXT PRIMARY KEY, translated TEXT, created_at INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cache")
        onCreate(db)
    }

    fun get(key: String): String? {
        readableDatabase.rawQuery(
            "SELECT translated FROM cache WHERE cache_key = ? LIMIT 1",
            arrayOf(key)
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun put(key: String, translated: String) {
        val cv = ContentValues().apply {
            put("cache_key", key)
            put("translated", translated)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
