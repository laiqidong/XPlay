package com.shilapi.xcertplay;

import android.app.AlertDialog;
import android.app.Dialog;
import org.robolectric.shadows.ShadowDialog;

/** Tracks application-owned AlertDialog subclasses as well as stock Android dialogs. */
public final class TestDialogs {
    private TestDialogs() {}
    public static Dialog latestRaw() { return ShadowDialog.getLatestDialog(); }
    public static AlertDialog latest() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        return dialog instanceof AlertDialog ? (AlertDialog) dialog : null;
    }
}
