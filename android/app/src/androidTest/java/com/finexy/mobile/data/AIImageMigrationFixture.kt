package com.finexy.mobile.data

/** These fixtures start from today's schema; remove the image additions before simulating an older DB. */
internal fun androidx.sqlite.db.SupportSQLiteDatabase.removeAIImageSchemaForTest() {
    execSQL("DROP TABLE IF EXISTS ai_review_image_chunks")
    execSQL("DROP TABLE IF EXISTS ai_review_images")
    execSQL("ALTER TABLE ai_review_requests DROP COLUMN sourceImageId")
}
internal fun android.database.sqlite.SQLiteDatabase.removeAIImageSchemaForTest() {
    execSQL("DROP TABLE IF EXISTS ai_review_image_chunks")
    execSQL("DROP TABLE IF EXISTS ai_review_images")
    execSQL("ALTER TABLE ai_review_requests DROP COLUMN sourceImageId")
}
