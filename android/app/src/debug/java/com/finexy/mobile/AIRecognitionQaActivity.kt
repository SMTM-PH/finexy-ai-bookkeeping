package com.finexy.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.LocalOCRResult
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TransactionRepository

/** Non-exported D5 recognition fixture; it never contacts a server. */
class AIRecognitionQaActivity : PrivacyActivity() {
    var lightMode by mutableStateOf(true)
    var showOCRPreview by mutableStateOf(false)
    var showImageEditor by mutableStateOf(false)
    var submittedCount by mutableIntStateOf(0)
    private lateinit var database: FinexyDatabase
    private lateinit var repository: TransactionRepository
    private val store by lazy { SecureStore(applicationContext, "ai-recognition-ui-fixture") }
    override fun privacyStore() = store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = Room.inMemoryDatabaseBuilder(applicationContext, FinexyDatabase::class.java).build()
        repository = TransactionRepository(applicationContext, database, importLegacy = false)
        setContent {
            FinexyTheme(lightMode) {
                AIRecognitionPage(store, repository, localMode = false, onBack = {}, onOpenReviews = {})
                if (showOCRPreview) OCRPreviewDialog(
                    LocalOCRResult("FINEXY TEST RECEIPT\nTOTAL CNY 36.50", 0.963),
                    busy = false,
                    onDismiss = { showOCRPreview = false },
                    onSubmit = { submittedCount++; showOCRPreview = false }
                )
                if (showImageEditor) ReceiptImageEditor(
                    source = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.rgb(244, 239, 225))
                    },
                    onCancel = { showImageEditor = false },
                    onConfirm = { _, _ -> submittedCount++; showImageEditor = false }
                )
            }
        }
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }
}
