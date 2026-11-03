package com.finexy.mobile.reportqa;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Only synthetic, bounded files in the separate test APK's :report_support process. */
final class ReportSafOwnedStore {
    private static final Object MONITOR = new Object();
    private static final int MAX_BYTES = 512 * 1024;
    private static final Map<String, CountDownLatch> GATES = new HashMap<>();
    private static final Map<String, JSONObject> RUNS = new LinkedHashMap<>();
    private static Context app;

    private ReportSafOwnedStore() { }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static void initialize(Context context) {
        require(ReportSafQaContract.TEST_PACKAGE.equals(context.getPackageName()), "Test APK context required");
        synchronized (MONITOR) { app = context.getApplicationContext(); }
    }

    private static File base() throws Exception {
        File value = new File(app.getFilesDir(), "report-saf-qa").getCanonicalFile();
        require(value.getParentFile().equals(app.getFilesDir().getCanonicalFile()), "Invalid owned root");
        require(value.mkdirs() || value.isDirectory(), "Cannot create owned root");
        return value;
    }

    private static File directory(String run) throws Exception {
        require(ReportSafQaContract.validRun(run), "Invalid synthetic run");
        File value = new File(base(), run).getCanonicalFile();
        require(value.getParentFile().equals(base()), "Invalid owned run");
        return value;
    }

    private static File ownedFile(String run, String name) throws Exception {
        require(name.matches("[a-z0-9.-]+"), "Invalid owned filename");
        File value = new File(directory(run), name).getCanonicalFile();
        require(value.getParentFile().equals(directory(run)), "File escaped owned run");
        return value;
    }

    private static JSONObject state(String run) throws Exception {
        require(ReportSafQaContract.validRun(run), "Invalid synthetic run");
        JSONObject cached = RUNS.get(run);
        if (cached != null) return cached;
        File file = ownedFile(run, "state.json");
        require(file.isFile() && file.length() >= 2 && file.length() <= 1024 * 1024, "Unknown synthetic run");
        JSONObject loaded;
        try (InputStream input = new FileInputStream(file)) {
            loaded = new JSONObject(new String(readBounded(input, 1024 * 1024), StandardCharsets.UTF_8));
        }
        require(run.equals(loaded.getString("runId")), "Run metadata mismatch");
        RUNS.put(run, loaded);
        return loaded;
    }

    private static void persist(String run) throws Exception {
        try (OutputStream output = new FileOutputStream(ownedFile(run, "state.json"))) {
            output.write(state(run).toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (;;) {
            int count = input.read(buffer);
            if (count < 0) break;
            require(output.size() + count <= maximum, "Synthetic file exceeds test bound");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void notifyRoots() {
        app.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(ReportSafQaContract.DOCS_AUTHORITY), null);
    }

    static JSONObject begin(String run, String mode) throws Exception {
        synchronized (MONITOR) {
            require(ReportSafQaContract.validRun(run) && ReportSafQaContract.MODES.contains(mode), "Invalid synthetic configuration");
            File dir = directory(run);
            require(!dir.exists() && dir.mkdir(), "Use a fresh synthetic run");
            JSONObject value = new JSONObject().put("runId", run).put("mode", mode).put("providerUid", Process.myUid())
                    .put("documents", new JSONArray()).put("received", new JSONArray()).put("createCount", 0)
                    .put("writeOpenCount", 0).put("readOpenCount", 0).put("writeCloseCount", 0).put("readBlocked", false);
            RUNS.put(run, value);
            GATES.put(run, new CountDownLatch(1));
            persist(run);
            notifyRoots();
            return new JSONObject(value.toString());
        }
    }

    static List<JSONObject> all() throws Exception {
        synchronized (MONITOR) {
            List<JSONObject> result = new ArrayList<>();
            for (JSONObject value : RUNS.values()) result.add(new JSONObject(value.toString()));
            return result;
        }
    }

    static JSONObject snapshot(String run) throws Exception {
        synchronized (MONITOR) { return new JSONObject(state(run).toString()); }
    }

    static List<JSONObject> documents(String run) throws Exception {
        synchronized (MONITOR) {
            JSONArray values = state(run).getJSONArray("documents");
            List<JSONObject> result = new ArrayList<>();
            for (int index = 0; index < values.length(); index++) result.add(new JSONObject(values.getJSONObject(index).toString()));
            return result;
        }
    }

    private static String[] parseDocument(String id) throws Exception {
        require(id.matches("doc:[a-f0-9]{32}:[a-f0-9]{32}"), "Invalid synthetic document");
        String[] parts = id.split(":");
        boolean known = false;
        for (JSONObject document : documents(parts[1])) if (id.equals(document.getString("id"))) known = true;
        require(known, "Unknown synthetic document");
        return new String[]{parts[1], parts[2] + ".csv"};
    }

    static String create(String run, String mime, String name) throws Exception {
        synchronized (MONITOR) {
            require("text/csv".equals(mime) && ReportSafQaContract.filename(run).equals(name), "Only this synthetic CSV may be created");
            String fileId = UUID.randomUUID().toString().replace("-", "");
            String id = "doc:" + run + ":" + fileId;
            require(ownedFile(run, fileId + ".csv").createNewFile(), "Synthetic file already exists");
            JSONObject document = new JSONObject().put("id", id).put("name", name).put("mime", mime)
                    .put("uri", DocumentsContract.buildDocumentUri(ReportSafQaContract.DOCS_AUTHORITY, id).toString());
            JSONObject value = state(run);
            value.getJSONArray("documents").put(document);
            value.put("createCount", value.getInt("createCount") + 1);
            persist(run);
            return id;
        }
    }

    static byte[] physicalBytes(String id) throws Exception {
        synchronized (MONITOR) {
            String[] parts = parseDocument(id);
            File file = ownedFile(parts[0], parts[1]);
            require(file.isFile() && file.length() <= MAX_BYTES, "Invalid synthetic file");
            try (InputStream input = new FileInputStream(file)) { return readBounded(input, MAX_BYTES); }
        }
    }

    static long length(String id) throws Exception {
        synchronized (MONITOR) { String[] parts = parseDocument(id); return ownedFile(parts[0], parts[1]).length(); }
    }

    static void delete(String id) throws Exception {
        synchronized (MONITOR) {
            String[] parts = parseDocument(id);
            JSONObject value = state(parts[0]);
            JSONArray previous = value.getJSONArray("documents");
            JSONArray retained = new JSONArray();
            for (int index = 0; index < previous.length(); index++) {
                JSONObject document = previous.getJSONObject(index);
                if (!id.equals(document.getString("id"))) retained.put(document);
            }
            File file = ownedFile(parts[0], parts[1]);
            require(!file.exists() || file.isFile() && file.delete(), "Cannot delete owned document");
            value.put("documents", retained);
            persist(parts[0]);
        }
    }

    static ParcelFileDescriptor open(String id, String mode, int callerUid) throws Exception {
        final String[] parts;
        final String fixtureMode;
        final boolean write = mode.contains("w");
        synchronized (MONITOR) {
            parts = parseDocument(id);
            JSONObject value = state(parts[0]);
            String key = write ? "writeOpenCount" : "readOpenCount";
            value.put(key, value.getInt(key) + 1).put(write ? "lastWriteCallerUid" : "lastReadCallerUid", callerUid);
            fixtureMode = value.getString("mode");
            persist(parts[0]);
        }
        String run = parts[0];
        if (write && "OPEN_WRITE_FAIL".equals(fixtureMode)) throw new FileNotFoundException("Synthetic provider refuses output");
        if (!write && "READBACK_FAIL".equals(fixtureMode)) throw new FileNotFoundException("Synthetic provider refuses re-read");
        File file = ownedFile(run, parts[1]);
        if (!write && "CORRUPT_READBACK".equals(fixtureMode)) {
            byte[] bytes = physicalBytes(id);
            require(bytes.length > 0, "No synthetic content to corrupt");
            bytes[0] ^= 1;
            File corrupt = ownedFile(run, "corrupt-" + id.substring(id.lastIndexOf(':') + 1) + ".csv");
            try (OutputStream output = new FileOutputStream(corrupt)) { output.write(bytes); }
            return ParcelFileDescriptor.open(corrupt, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!write && "BLOCK_READBACK".equals(fixtureMode)) {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            final CountDownLatch gate;
            synchronized (MONITOR) {
                gate = Objects.requireNonNull(GATES.get(run));
                state(run).put("readBlocked", true);
                persist(run);
            }
            Thread worker = new Thread(() -> {
                try {
                    require(gate.await(90, TimeUnit.SECONDS), "Synthetic read gate timed out");
                    try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { output.write(physicalBytes(id)); }
                } catch (Exception error) {
                    try { pipe[1].closeWithError("Synthetic read gate failed"); } catch (Exception ignored) { }
                }
            }, "finexy-report-saf-reader");
            worker.setDaemon(true);
            worker.start();
            return pipe[0];
        }
        if (!write) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), new Handler(Looper.getMainLooper()), error -> {
            synchronized (MONITOR) {
                if (!RUNS.containsKey(run)) return;
                try {
                    JSONObject value = state(run);
                    value.put("writeCloseCount", value.getInt("writeCloseCount") + 1).put("lastCloseHadError", error != null);
                    persist(run);
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not record synthetic close", exception);
                }
            }
        });
    }

    static void release(String run) throws Exception {
        synchronized (MONITOR) { state(run); CountDownLatch gate = GATES.get(run); if (gate != null) gate.countDown(); }
    }

    static void expect(String run, Uri uri, String hash, int bytes) throws Exception {
        synchronized (MONITOR) {
            require("content".equals(uri.getScheme()) && "com.android.providers.downloads.documents".equals(uri.getAuthority()), "Only system Downloads is a grant oracle");
            require(hash.matches("[a-f0-9]{64}") && bytes > 0 && bytes <= MAX_BYTES, "Invalid expected synthetic file");
            state(run).put("expectedUri", uri.toString()).put("expectedHash", hash).put("expectedBytes", bytes);
            persist(run);
        }
    }

    @SuppressWarnings("deprecation")
    static void receive(Intent intent, Context context) throws Exception {
        require(Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_SEND.equals(intent.getAction()), "Unsupported synthetic handoff");
        Uri uri = Intent.ACTION_SEND.equals(intent.getAction())
                ? (Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : intent.getParcelableExtra(Intent.EXTRA_STREAM))
                : intent.getData();
        Objects.requireNonNull(uri);
        require("com.android.providers.downloads.documents".equals(uri.getAuthority()), "A provider-owning receiver cannot prove a read-only grant");
        final String run;
        synchronized (MONITOR) {
            String found = null;
            for (String candidate : RUNS.keySet()) {
                if (uri.toString().equals(state(candidate).optString("expectedUri"))) {
                    require(found == null, "Ambiguous synthetic receiver URI");
                    found = candidate;
                }
            }
            run = Objects.requireNonNull(found, "No explicitly armed synthetic URI");
        }
        int targetUid = context.getPackageManager().getApplicationInfo(ReportSafQaContract.TARGET_PACKAGE, 0).uid;
        int providerUid = Objects.requireNonNull(context.getPackageManager().resolveContentProvider(Objects.requireNonNull(uri.getAuthority()), 0)).applicationInfo.uid;
        int receiverUid = Process.myUid();
        require(receiverUid != targetUid && receiverUid != providerUid, "Receiver must be a third UID");
        int read = Build.VERSION.SDK_INT >= 35
                ? context.checkContentUriPermissionFull(uri, Process.myPid(), receiverUid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                : context.checkUriPermission(uri, Process.myPid(), receiverUid, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int write = Build.VERSION.SDK_INT >= 35
                ? context.checkContentUriPermissionFull(uri, Process.myPid(), receiverUid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                : context.checkUriPermission(uri, Process.myPid(), receiverUid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        boolean denied = false;
        boolean unexpectedlyOpened = false;
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wa")) {
            unexpectedlyOpened = output != null;
        } catch (SecurityException error) { denied = true; }
        byte[] actual;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            require(input != null, "Granted CSV could not be opened");
            actual = readBounded(input, MAX_BYTES);
        }
        JSONArray clip = new JSONArray();
        if (intent.getClipData() != null) {
            for (int index = 0; index < intent.getClipData().getItemCount(); index++) {
                Uri item = intent.getClipData().getItemAt(index).getUri();
                clip.put(item == null ? JSONObject.NULL : item.toString());
            }
        }
        JSONObject result = new JSONObject().put("action", intent.getAction()).put("uri", uri.toString()).put("mime", intent.getType())
                .put("stream", Intent.ACTION_SEND.equals(intent.getAction()) ? uri.toString() : JSONObject.NULL).put("clipUris", clip)
                .put("flags", intent.getFlags()).put("receiverUid", receiverUid).put("targetUid", targetUid).put("providerUid", providerUid)
                .put("readPermission", read).put("writePermission", write).put("writeAttemptDenied", denied)
                .put("writeAttemptUnexpectedlyOpened", unexpectedlyOpened).put("bytes", actual.length).put("sha256", ReportSafQaContract.hash(actual));
        synchronized (MONITOR) { state(run).getJSONArray("received").put(result); persist(run); }
    }

    static void receiverError(Throwable error) {
        synchronized (MONITOR) {
            try {
                for (String run : RUNS.keySet()) { state(run).put("receiverErrorClass", error.getClass().getName()); persist(run); }
            } catch (Exception exception) { throw new IllegalStateException("Could not record synthetic receiver error", exception); }
        }
    }

    static void end(String run) throws Exception {
        synchronized (MONITOR) {
            require(ReportSafQaContract.validRun(run), "Invalid synthetic run");
            CountDownLatch gate = GATES.remove(run);
            if (gate != null) gate.countDown();
            state(run);
            File dir = directory(run);
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) {
                require(file.getCanonicalFile().getParentFile().equals(dir) && file.isFile(), "Unexpected object in owned run");
                require(file.delete(), "Cannot delete owned synthetic file");
            }
            require(dir.delete(), "Cannot delete owned synthetic run");
            RUNS.remove(run);
            notifyRoots();
        }
    }
}
