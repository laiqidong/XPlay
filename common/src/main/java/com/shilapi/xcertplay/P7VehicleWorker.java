package com.shilapi.xcertplay;

import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

/** A shell session reused for readback and explicit actions. No writes on startup or disconnect. */
@Keep
public final class P7VehicleWorker {
    private final Class<?> api;
    private final Object vehicle;
    private P7VehicleWorker() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "car_service");
        Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object vb = Class.forName("android.car.ICar").getMethod("getCarService", String.class).invoke(car, "xp_vehicle");
        api = Class.forName("android.car.hardware.XpVehicle.IXpVehicle");
        vehicle = Class.forName(api.getName() + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, vb);
    }
    public static void main(String[] args) {
        if (args.length != 0 || Process.myUid() != 2000 || Build.VERSION.SDK_INT != 28 || !"E28".equals(Build.MODEL)) return;
        try {
            P7VehicleWorker worker = new P7VehicleWorker();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            String command;
            while ((command = input.readLine()) != null && command.length() <= 32) {
                boolean ok = true;
                try { worker.apply(command); } catch (Exception ignored) { ok = false; }
                JSONObject state = worker.read(); state.put("ok", ok);
                System.out.println(state); System.out.println("done"); System.out.flush();
            }
        } catch (Throwable ignored) { }
    }
    private int get(String method) throws Exception {
        return ((Number) api.getMethod(method).invoke(vehicle)).intValue();
    }
    private int port(int side) throws Exception {
        return ((Number) api.getMethod("getBcmChargePortStatus", int.class).invoke(vehicle, side)).intValue();
    }
    private void set(String method, int value) throws Exception {
        api.getMethod(method, int.class).invoke(vehicle, value);
    }
    private void apply(String command) throws Exception {
        switch (command) {
            case "READ": return;
            case "FOG":
                int fog = get("isBcmRearFogLampOn");
                if (fog != 0 && fog != 1) throw new IllegalStateException();
                set("setBcmRearFogLampOn", 1 - fog); return;
            case "TRUNK":
                int trunk;
                try { trunk = get("getBcmTrunkOpennerStatus"); }
                catch (Exception unavailable) {
                    // E28 latch readback verified on this car: 0=closed, 2=open.
                    int latch = get("getBcmTrunkStatus");
                    if (latch != 0 && latch != 2) throw new IllegalStateException();
                    // Confirmed on this E28: the same single pulse opens and closes the tailgate.
                    requireStationary(); set("setBcmTrunkOpen", 1); return;
                }
                int value;
                switch (trunk) {
                    case 0: case 1: case 8: case 9: case 10: value = 1; break;
                    case 2: case 3: case 6: case 7: value = 2; break;
                    case 4: case 5: value = 3; break;
                    default: throw new IllegalStateException();
                }
                if (value != 2) requireStationary();
                set("setBcmTrunkOpen", value); return;
            case "CHARGE_FAST": case "CHARGE_SLOW":
                requireStationary();
                int side = "CHARGE_FAST".equals(command) ? 2 : 1;
                int status = port(side);
                // OEM reports 0=open, 2=closed. Transitional/invalid states are not toggled.
                if (status != 0 && status != 2) throw new IllegalStateException();
                api.getMethod("setBcmChargePortUnlock", int.class, int.class)
                        .invoke(vehicle, side, status == 2 ? 1 : 0);
                return;
            default: throw new IllegalArgumentException();
        }
    }
    private JSONObject read() {
        JSONObject state = new JSONObject();
        // One unavailable property must not hide the other controls.
        try { state.put("fog", get("isBcmRearFogLampOn")); } catch (Exception ignored) { }
        try {
            state.put("trunk", get("getBcmTrunkOpennerStatus"));
            state.put("trunkElectric", true);
        } catch (Exception unavailable) {
            try {
                int latch = get("getBcmTrunkStatus");
                if (latch == 0 || latch == 2) {
                    state.put("trunk", latch == 0 ? 0 : 5);
                    state.put("trunkElectric", false);
                }
            } catch (Exception ignored) { }
        }
        try { state.put("slow", port(1)); } catch (Exception ignored) { }
        try { state.put("fast", port(2)); } catch (Exception ignored) { }
        return state;
    }
    private void requireStationary() throws Exception {
        float speed = ((Number) api.getMethod("getVcuRawCarSpeed").invoke(vehicle)).floatValue();
        if (Float.isNaN(speed) || Float.isInfinite(speed) || speed != 0f) throw new IllegalStateException();
    }
}
