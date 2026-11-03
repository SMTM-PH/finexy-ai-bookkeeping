package com.finexy.mobile.reportqa;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.Objects;

/** System DocumentsUI access only; this provider cannot establish receiver grant isolation. */
public final class ReportSafDocumentsProvider extends DocumentsProvider {
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
    };
    private static final String[] ROOT_COLUMNS = {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    @Override public boolean onCreate() {
        ReportSafOwnedStore.initialize(Objects.requireNonNull(getContext()));
        return true;
    }

    private static FileNotFoundException inaccessible(Exception cause) {
        FileNotFoundException error = new FileNotFoundException("Synthetic report document is unavailable");
        error.initCause(cause);
        return error;
    }

    @Override public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        String[] columns = projection == null ? ROOT_COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        try {
            for (JSONObject run : ReportSafOwnedStore.all()) {
                String id = run.getString("runId");
                MatrixCursor.RowBuilder row = cursor.newRow();
                for (String column : columns) {
                    Object value = null;
                    switch (column) {
                        case DocumentsContract.Root.COLUMN_ROOT_ID: value = id; break;
                        case DocumentsContract.Root.COLUMN_DOCUMENT_ID: value = "run:" + id; break;
                        case DocumentsContract.Root.COLUMN_TITLE: value = ReportSafQaContract.rootTitle(id); break;
                        case DocumentsContract.Root.COLUMN_SUMMARY: value = "Synthetic Finexy QA files only"; break;
                        case DocumentsContract.Root.COLUMN_FLAGS:
                            value = DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_LOCAL_ONLY
                                    | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD;
                            break;
                        case DocumentsContract.Root.COLUMN_MIME_TYPES: value = "text/csv"; break;
                        case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES: value = 512L * 1024; break;
                    }
                    row.add(column, value);
                }
            }
            return cursor;
        } catch (Exception error) {
            cursor.close();
            throw inaccessible(error);
        }
    }

    private static String rootRun(String id) throws FileNotFoundException {
        if (id == null || !id.matches("run:[a-f0-9]{32}")) throw new FileNotFoundException("Invalid synthetic root");
        return id.substring(4);
    }

    private static void addDocument(MatrixCursor cursor, String id) throws Exception {
        boolean root = id != null && id.startsWith("run:");
        String run;
        if (root) run = rootRun(id);
        else {
            ReportSafOwnedStore.require(id != null && id.matches("doc:[a-f0-9]{32}:[a-f0-9]{32}"), "Invalid synthetic document");
            run = id.split(":")[1];
        }
        ReportSafOwnedStore.snapshot(run);
        JSONObject document = null;
        if (!root) {
            for (JSONObject candidate : ReportSafOwnedStore.documents(run)) {
                if (id.equals(candidate.getString("id"))) document = candidate;
            }
            Objects.requireNonNull(document, "Unknown synthetic document");
        }
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: value = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    value = root ? ReportSafQaContract.rootTitle(run) : document.getString("name"); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    value = root ? DocumentsContract.Document.MIME_TYPE_DIR : "text/csv"; break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    value = root ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
                    break;
                case DocumentsContract.Document.COLUMN_SIZE: value = root ? 0L : ReportSafOwnedStore.length(id); break;
            }
            row.add(column, value);
        }
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        try { addDocument(cursor, documentId); return cursor; }
        catch (Exception error) { cursor.close(); throw inaccessible(error); }
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        try {
            for (JSONObject document : ReportSafOwnedStore.documents(rootRun(parentDocumentId))) {
                addDocument(cursor, document.getString("id"));
            }
            return cursor;
        } catch (Exception error) { cursor.close(); throw inaccessible(error); }
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        try { return ReportSafOwnedStore.create(rootRun(parentDocumentId), mimeType, displayName); }
        catch (Exception error) { throw inaccessible(error); }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        try { return ReportSafOwnedStore.open(documentId, mode, Binder.getCallingUid()); }
        catch (Exception error) { throw inaccessible(error); }
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String run = rootRun(parentDocumentId);
            if (documentId == null || !documentId.startsWith("doc:" + run + ":")) return false;
            for (JSONObject document : ReportSafOwnedStore.documents(run)) {
                if (documentId.equals(document.getString("id"))) return true;
            }
            return false;
        } catch (Exception error) { return false; }
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        try { ReportSafOwnedStore.delete(documentId); }
        catch (Exception error) { throw inaccessible(error); }
    }
}
