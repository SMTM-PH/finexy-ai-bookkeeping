package com.finexy.mobile.reportqa;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Separate-UID consumer of an explicitly armed synthetic Downloads CSV. */
public final class ReportSafCsvReceiverActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        accept(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        accept(intent);
    }

    private void accept(Intent intent) {
        ReportSafOwnedStore.initialize(getApplicationContext());
        TextView label = new TextView(this);
        label.setText(ReportSafQaContract.RECEIVER_LABEL);
        setContentView(label);
        try { ReportSafOwnedStore.receive(intent, this); }
        catch (Exception error) { ReportSafOwnedStore.receiverError(error); }
        finally { finish(); }
    }
}
