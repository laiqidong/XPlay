package com.shilapi.xcertplay;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

/** Shell-UID bridge to the OEM application workflow, not the EAV direction enum. */
final class P7ClimateSmartPassenger {
    private static final String DESCRIPTOR = "com.xiaopeng.carhvac.HvacSpeechObserver";
    private final String packageName;
    private final String token;
    private IBinder service;
    private Context context;
    private String stage = "not-started";
    private String status = "not-started";
    private String writeStatus = "not-requested";
    String status() { return status; }
    String writeStatus() { return writeStatus; }

    P7ClimateSmartPassenger(String packageName, String token) {
        this.packageName = packageName;
        this.token = token;
    }

    private IBinder service() throws Exception {
        if (service != null && service.isBinderAlive()) return service;
        if (context == null) {
            stage = "context";
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object instance = thread.getMethod("systemMain").invoke(null);
            Context system = (Context) thread.getMethod("getSystemContext").invoke(instance);
            context = system.createPackageContext("com.android.shell", 0);
        }
        stage = "provider";
        Bundle result = context.getContentResolver().call(
            Uri.parse("content://com.xiaopeng.carhvac.api.publisher"), "HvacSpeechObserver", null, null);
        service = result == null ? null : result.getBinder("binder");
        if (service == null) throw new IllegalStateException("OEM climate workflow unavailable");
        return service;
    }

    private void transact(int code, Uri uri) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            uri.writeToParcel(request, 0);
            IBinder target = service();
            stage = code == 0 ? "command" : "query";
            if (!target.transact(code, request, reply, 0)) throw new IllegalStateException("OEM climate call rejected");
            reply.readException();
            status = "query-sent";
        } catch (Exception error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            status = stage + ":" + cause.getClass().getSimpleName();
            if (code == 0) writeStatus = status;
            throw error;
        } finally { reply.recycle(); request.recycle(); }
    }

    void set(boolean enabled) throws Exception {
        // One foreground action only; never retry a write after a Binder failure.
        transact(0, Uri.parse("api://" + DESCRIPTOR + "/onEvent").buildUpon()
            .appendQueryParameter("event", "command://ac.intelligent.psn." + (enabled ? "on" : "off"))
            .appendQueryParameter("data", "{}").build());
        writeStatus = "acknowledged";
    }

    void query() throws Exception {
        String callback = Uri.parse("api://" + packageName + ".ClimateCallback/onResult").buildUpon()
            .appendQueryParameter("token", token)
            .appendQueryParameter("requestedAt", Long.toString(SystemClock.elapsedRealtime())).build().toString();
        transact(1, Uri.parse("api://" + DESCRIPTOR + "/onQuery").buildUpon()
            .appendQueryParameter("event", "ac.intelligent.passenger.status")
            .appendQueryParameter("data", "{}")
            .appendQueryParameter("callback", callback).build());
    }
}
