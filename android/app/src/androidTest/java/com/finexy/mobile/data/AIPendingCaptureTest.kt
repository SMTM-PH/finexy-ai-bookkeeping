package com.finexy.mobile.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class AIPendingCaptureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun partition() = "ai-pending-test-${UUID.randomUUID()}"
    private fun draft(ledger: Long = 9) = AIReviewDraftEntity(ledger, 0, UUID.randomUUID().toString(), 1, AISourceForm().json())
    private fun bytes(): ByteArray {
        val image = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888); image.eraseColor(0xff778833.toInt())
        return ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); image.recycle(); it.toByteArray() }
    }
    private fun session(store: AIReviewPersistence) = AIReviewSourceSession(store, 9, { true }, { error("must not send") },
        { error("must not send") }, { _, _ -> error("must not send") }, { error("must not enqueue") })

    @Test fun reopenPreservesExactOperationAndSeparatesIdentityAndLedger() {
        val partition = partition(); val store = AIPendingCaptureStore(context, partition, 9)
        val source = draft(); val capture = store.begin(source); val content = bytes()
        try {
            store.file(capture).writeBytes(content)
            val reopened = AIPendingCaptureStore(context, partition, 9)
            assertEquals(capture, reopened.read()); assertArrayEquals(content, reopened.file(capture).readBytes())
            assertTrue(capture.matches(source)); assertFalse(capture.matches(source.copy(revision = 2)))
            assertFalse(capture.matches(source.copy(formJson = "{}")))
            assertNull(AIPendingCaptureStore(context, partition(), 9).read())
            assertThrows(IllegalArgumentException::class.java) { AIPendingCaptureStore(context, partition(), 9).file(capture) }
            assertNull(AIPendingCaptureStore(context, partition, 10).read())
            assertThrows(IllegalArgumentException::class.java) { AIPendingCaptureStore(context, partition, 10).file(capture) }
            assertThrows(IllegalArgumentException::class.java) { store.file(capture.copy(operationId = "../outside")) }
            val journal = java.io.File(store.file(capture).parentFile, "pending.json"); val metadata = journal.readText()
            try {
                journal.writeText("{broken metadata")
                assertThrows(org.json.JSONException::class.java) { reopened.read() }
                assertThrows(org.json.JSONException::class.java) { reopened.begin(source) }
                assertArrayEquals(content, store.file(capture).readBytes())
            } finally { journal.writeText(metadata) }
        } finally { store.discard(capture) }
    }

    @Test fun duplicateBeginAndOldDiscardCannotReplaceOrDeleteNewerCapture() {
        val store = AIPendingCaptureStore(context, partition(), 9); val source = draft()
        val first = store.begin(source)
        assertThrows(IllegalStateException::class.java) { store.begin(source) }
        store.discard(first)
        val second = store.begin(source)
        try {
            val content = bytes(); store.file(second).writeBytes(content)
            assertThrows(IllegalArgumentException::class.java) { store.discard(first) }
            assertEquals(second, store.read()); assertArrayEquals(content, store.file(second).readBytes())
        } finally { store.discard(second) }
    }

    @android.annotation.TargetApi(35)
    @Test fun discardingOneOperationRevokesItsCameraGrantWithoutRevokingAnotherOperation() {
        check(android.os.Build.VERSION.SDK_INT >= 35) { "The camera URI permission fixture requires API 35" }
        check(context.packageName == "com.finexy.mobile.qa") { "The camera URI permission fixture requires the isolated QA application" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val cameraPackage = "com.android.camera2"
        val packages = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("cmd package list packages -U $cameraPackage")
        ).bufferedReader().use { it.readText().trim() }
        val shellCameraUid = requireNotNull(Regex("^package:com\\.android\\.camera2 uid:(\\d+)$").matchEntire(packages)).groupValues[1].toInt()
        // The target app must see the consumer: shell-only discovery cannot prove a grant occurred.
        val cameraUid = context.packageManager.getApplicationInfo(cameraPackage, 0).uid
        assertEquals(shellCameraUid, cameraUid)
        val authority = "${context.packageName}.fileprovider"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertFalse(provider.exported)
        assertEquals(android.os.Process.myUid(), provider.applicationInfo.uid)
        assertNotEquals(provider.applicationInfo.uid, cameraUid)
        val firstStore = AIPendingCaptureStore(context, partition(), 9)
        val secondStore = AIPendingCaptureStore(context, partition(), 10)
        val first = firstStore.begin(draft(9)); val second = secondStore.begin(draft(10))
        val firstUri = androidx.core.content.FileProvider.getUriForFile(context, authority, firstStore.file(first))
        val secondUri = androidx.core.content.FileProvider.getUriForFile(context, authority, secondStore.file(second))
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Unlike checkUriPermission, this API explicitly permits an unknown PID of -1.
        // A non-exported provider and distinct consumer UID exclude provider-wide access.
        fun permission(uri: android.net.Uri) = context.checkContentUriPermissionFull(uri, -1, cameraUid, flags)
        try {
            firstStore.file(first).writeBytes(bytes()); secondStore.file(second).writeBytes(bytes())
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, permission(firstUri))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, permission(secondUri))
            context.grantUriPermission(cameraPackage, firstUri, flags)
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, permission(firstUri))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, permission(secondUri))
            context.grantUriPermission(cameraPackage, secondUri, flags)
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, permission(firstUri))
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, permission(secondUri))
            firstStore.discard(first)
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, permission(firstUri))
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, permission(secondUri))
            assertThrows(IllegalArgumentException::class.java) { firstStore.discard(first) }
            assertEquals(second, secondStore.read()); assertArrayEquals(bytes(), secondStore.file(second).readBytes())
            secondStore.discard(second)
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, permission(firstUri))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, permission(secondUri))
            assertNull(firstStore.read()); assertNull(secondStore.read())
        } finally {
            if (firstStore.read() == first) firstStore.discard(first)
            if (secondStore.read() == second) secondStore.discard(second)
        }
    }

    @Test fun confirmedLocalClearDeletesOnlyValidatedCapturesInTheSelectedIdentity() {
        val identity = partition(); val otherIdentity = partition()
        val firstStore = AIPendingCaptureStore(context, identity, 9); val secondStore = AIPendingCaptureStore(context, identity, 10)
        val otherStore = AIPendingCaptureStore(context, otherIdentity, 9)
        val first = firstStore.begin(draft(9)); val second = secondStore.begin(draft(10)); val other = otherStore.begin(draft(9))
        try {
            listOf(firstStore to first, secondStore to second, otherStore to other).forEach { (store, capture) -> store.file(capture).writeBytes(bytes()) }
            val journal = java.io.File(secondStore.file(second).parentFile, "pending.json"); val metadata = journal.readText()
            try {
                journal.writeText("{damaged ownership")
                assertThrows(org.json.JSONException::class.java) { AIPendingCaptureStore.clearPartition(context, identity) }
                assertEquals(first, firstStore.read()); assertTrue(firstStore.file(first).exists())
            } finally { journal.writeText(metadata) }
            assertEquals(2, AIPendingCaptureStore.clearPartition(context, identity))
            assertNull(firstStore.read()); assertNull(secondStore.read())
            assertFalse(firstStore.file(first).exists()); assertFalse(secondStore.file(second).exists())
            assertEquals(other, otherStore.read()); assertArrayEquals(bytes(), otherStore.file(other).readBytes())
        } finally {
            listOf(firstStore to first, secondStore to second, otherStore to other).forEach { (store, capture) -> if (store.read() == capture) store.discard(capture) }
        }
    }

    @Test fun missingAndInvalidCameraBytesKeepDraftUntilExplicitDiscard() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val persistence = AIReviewPersistence(db)
        val store = AIPendingCaptureStore(context, persistence.storagePartition, 9); val source = session(persistence)
        source.load(); val saved = source.prepareCapture(); val capture = store.begin(saved)
        try {
            assertEquals(0, store.file(capture).length())
            assertThrows(RuntimeException::class.java) { runBlocking { source.selectCapture(byteArrayOf(1, 2, 3), capture) } }
            assertEquals(saved, AIReviewPersistence(db).draft(9, 0)); assertEquals(capture, store.read())
            assertTrue(store.file(capture).delete())
            store.discard(capture); assertNull(store.read())
            assertEquals(saved, AIReviewPersistence(db).draft(9, 0)); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { store.read()?.let(store::discard); db.close() }
    }

    @Test fun staleCaptureCannotOverwriteNewerSourceOrCreateFinancialWrites() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val persistence = AIReviewPersistence(db); val source = session(persistence)
        val store = AIPendingCaptureStore(context, persistence.storagePartition, 9)
        source.load(); val capture = store.begin(source.prepareCapture())
        try {
            assertThrows(RuntimeException::class.java) { runBlocking { source.selectCapture(bytes(), capture.copy(partitionKey = pendingCapturePartition(partition(), 9))) } }
            assertTrue(capture.matches(persistence.draft(9, 0))); assertTrue(db.dao().aiReviewImages(9).isEmpty())
            source.edit(mapOf("sourceText" to "newer synthetic input")); val newer = source.persist()
            assertThrows(RuntimeException::class.java) { runBlocking { source.selectCapture(bytes(), capture) } }
            assertEquals(newer, persistence.draft(9, 0)); assertEquals(capture, store.read())
            assertTrue(persistence.requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { store.discard(capture); db.close() }
    }

    @Test fun encryptedCommitMarkerSurvivesReopenAndDoesNotReplayBeforeCleanup() = runBlocking<Unit> {
        val name = "ai-pending-reopen-${UUID.randomUUID()}.db"; val store = AIPendingCaptureStore(context, name, 9)
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        var capture: AIPendingCapture? = null
        try {
            val source = session(AIReviewPersistence(db)); source.load()
            val pending = store.begin(source.prepareCapture()); capture = pending
            val content = bytes(); store.file(pending).writeBytes(content)
            source.selectCapture(content, pending)
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            val persistence = AIReviewPersistence(db); val saved = requireNotNull(persistence.draft(9, 0))
            assertEquals(pending.operationId, AISourceForm.read(saved).fields["captureOperationId"])
            assertFalse(pending.matches(saved)); assertArrayEquals(content, persistence.sourceImage(saved))
            assertEquals(pending, store.read()); store.discard(pending)
            assertEquals(saved, persistence.draft(9, 0)); assertTrue(persistence.requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { capture?.let { if (store.read() == it) store.discard(it) }; db.close(); context.deleteDatabase(name) }
    }

    @Test fun portableBackupKeepsEncryptedOriginalButExcludesPendingPlaintextAndPaths() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val partition = partition(); val store = AIPendingCaptureStore(context, partition, 9)
        val persistence = AIReviewPersistence(db)
        val saved = persistence.saveSourceImage(9, AISourceForm().json(), bytes()); val capture = store.begin(saved)
        try {
            store.file(capture).writeBytes(bytes())
            val backup = LedgerBackup(db, "synthetic-capture-scope"); val password = "synthetic-pending-password"
            val document = backup.preview(backup.export(password), password)
            assertEquals(20, document.getInt("schema")); assertFalse(document.toString().contains(capture.operationId))
            assertFalse(document.toString().contains(store.file(capture).path)); assertFalse(document.toString().contains(partition))
            assertEquals(1, document.getJSONObject("tables").getJSONArray("ai_review_images").length())
            // Existing schema 19 remains readable and must not manufacture a device capture.
            val old = JSONObject(document.toString()).put("schema", 19); val tables = old.getJSONObject("tables")
            tables.remove("ai_review_images"); tables.remove("ai_review_image_chunks")
            tables.put("ai_review_drafts", org.json.JSONArray()); tables.put("ai_review_requests", org.json.JSONArray())
            backup.preview(PrivacyCrypto.encrypt(old.toString(), password), password)
            assertEquals(capture, store.read())
        } finally { store.discard(capture); db.close() }
    }
}
