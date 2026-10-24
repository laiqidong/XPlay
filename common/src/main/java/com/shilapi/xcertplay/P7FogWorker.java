package com.shilapi.xcertplay;

import android.net.LocalServerSocket;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Shell-owned rear-fog lease. Loss of the app heartbeat restores the initial lamp state. */
@Keep
public final class P7FogWorker {
    public static void main(String[] args) {
        if (args.length != 0 || Process.myUid() != 2000 || Build.VERSION.SDK_INT != 28 ||
            !"E28".equals(Build.MODEL)) return;
        try (LocalServerSocket owner = new LocalServerSocket("xplay.p7.rear-fog.v1")) {
            Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "car_service");
            Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Object vb = Class.forName("android.car.ICar").getMethod("getCarService", String.class)
                .invoke(car, "xp_vehicle");
            Class<?> api = Class.forName("android.car.hardware.XpVehicle.IXpVehicle");
            Object vehicle = Class.forName(api.getName() + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, vb);
            Method get = api.getMethod("isBcmRearFogLampOn");
            Method set = api.getMethod("setBcmRearFogLampOn", int.class);
            int before = (Integer) get.invoke(vehicle);
            if (before != 0 && before != 1) throw new IllegalStateException();
            ArrayBlockingQueue<String> input = new ArrayBlockingQueue<>(4);
            Thread reader = new Thread(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.length() > 16 || !input.offer(line)) break;
                    }
                } catch (Exception ignored) { }
                input.offer("STOP");
            }, "fog-input");
            reader.setDaemon(true);
            reader.start();
            boolean restored = false;
            try {
                int next = 1;
                while (true) {
                    String command = input.poll(1500, TimeUnit.MILLISECONDS);
                    if (!"PULSE".equals(command)) break;
                    set.invoke(vehicle, next);
                    next = 1 - next;
                    reply("PULSE");
                }
            } finally {
                // Retry restoration before releasing ownership, including after a broken ADB stream.
                for (int attempt = 0; attempt < 5 && !restored; attempt++) {
                    try {
                        set.invoke(vehicle, before);
                        Thread.sleep(100);
                        restored = Integer.valueOf(before).equals(get.invoke(vehicle));
                    } catch (Exception ignored) { }
                }
                reply(restored ? "STOPPED" : "RESTORE_FAILED");
            }
        } catch (Throwable ignored) { reply("ERROR"); }
    }

    private static void reply(String status) {
        System.out.println(status);
        System.out.println("done");
        System.out.flush();
    }
}
