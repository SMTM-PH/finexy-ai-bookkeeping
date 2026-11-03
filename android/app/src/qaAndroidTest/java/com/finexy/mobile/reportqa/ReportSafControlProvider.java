package com.finexy.mobile.reportqa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Objects;

/** Signature-protected control channel for bounded synthetic files only. */
public final class ReportSafControlProvider extends ContentProvider {
    @Override public boolean onCreate() {
        ReportSafOwnedStore.initialize(Objects.requireNonNull(getContext()));
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Objects.requireNonNull(getContext()).enforceCallingOrSelfPermission(
                ReportSafQaContract.PERMISSION, "Synthetic report control only");
        String run = Objects.requireNonNull(arg);
        ReportSafOwnedStore.require(ReportSafQaContract.validRun(run), "Invalid synthetic run");
        try {
            Bundle result = new Bundle();
            switch (method) {
                case "begin":
                    result.putString("json", ReportSafOwnedStore.begin(run,
                            Objects.requireNonNull(Objects.requireNonNull(extras).getString("mode"))).toString());
                    break;
                case "state":
                    result.putString("json", ReportSafOwnedStore.snapshot(run).toString());
                    break;
                case "actual":
                    String id = Objects.requireNonNull(Objects.requireNonNull(extras).getString("documentId"));
                    ReportSafOwnedStore.require(id.startsWith("doc:" + run + ":"), "Document is outside this run");
                    result.putByteArray("bytes", ReportSafOwnedStore.physicalBytes(id));
                    break;
                case "expect":
                    Objects.requireNonNull(extras);
                    ReportSafOwnedStore.expect(run, Uri.parse(Objects.requireNonNull(extras.getString("uri"))),
                            Objects.requireNonNull(extras.getString("sha256")), extras.getInt("bytes"));
                    result.putBoolean("ok", true);
                    break;
                case "release":
                    ReportSafOwnedStore.release(run);
                    result.putBoolean("ok", true);
                    break;
                case "end":
                    ReportSafOwnedStore.end(run);
                    result.putBoolean("ok", true);
                    break;
                default: throw new IllegalArgumentException("Unsupported synthetic report method");
            }
            return result;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Synthetic report control failed", error);
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Unsupported"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Unsupported"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Unsupported"); }
}
