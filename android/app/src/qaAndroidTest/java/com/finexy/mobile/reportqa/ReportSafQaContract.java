package com.finexy.mobile.reportqa;

import android.net.Uri;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Framework/Java-only: the separate test-APK process has no target Kotlin classloader. */
public final class ReportSafQaContract {
    public static final String TEST_PACKAGE = "com.finexy.mobile.qa.test";
    public static final String TARGET_PACKAGE = "com.finexy.mobile.qa";
    public static final String PERMISSION = "com.finexy.mobile.qa.permission.REPORT_SAF_FIXTURE";
    public static final String DOCS_AUTHORITY = TEST_PACKAGE + ".reportdocs";
    public static final String CONTROL_AUTHORITY = TEST_PACKAGE + ".reportcontrol";
    public static final String RECEIVER_LABEL = "Finexy QA CSV probe";
    public static final Uri CONTROL_URI = Uri.parse("content://" + CONTROL_AUTHORITY);
    public static final Set<String> MODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "GOOD", "OPEN_WRITE_FAIL", "READBACK_FAIL", "CORRUPT_READBACK", "BLOCK_READBACK")));

    private ReportSafQaContract() { }

    public static boolean validRun(String run) { return run != null && run.matches("[a-f0-9]{32}"); }
    public static String rootTitle(String run) { return "Finexy QA CSV " + run.substring(0, 8); }
    public static String filename(String run) { return "finexy-qa-" + run + ".csv"; }
    public static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
