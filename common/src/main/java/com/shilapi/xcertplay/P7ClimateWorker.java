package com.shilapi.xcertplay;

import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import androidx.annotation.Keep;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Explicit foreground climate commands only; a lost connection never replays a write. */
@Keep
public final class P7ClimateWorker {
    private static final String[] KEYS = {"driver", "passenger", "fan", "power", "ac", "auto", "sync", "cycle", "direction", "front", "rear", "eco", "aqs", "defog", "clean", "smart_passenger"};
    private static final String[] GETTERS = {"getHvacDrvSeatTempValue", "getHvacPsnSeatTempValue", "getHvacFanSpeedLevel", "getHvacPowerState", "getHvacAcState", "getHvacAutoModeState", "getHvacTempSyncMode", "getHvacAirCycleMode", "getHvacFanPosition", "getHvacDefrostMode", "getBcmBackDefrostMode", "getHvacEconState", "getHvacAqsSwitchState", "getHvacAutoDefogSwitchStatus", "getHvacSelfDrySwStatus", "getHvacEavPsnWindMode"};
    private int previousPsnWindMode = 3; // OEM Free mode
    private final Class<?> api;
    private final Object vehicle;
    private P7ClimateWorker() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "car_service");
        Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object vb = Class.forName("android.car.ICar").getMethod("getCarService", String.class).invoke(car, "xp_vehicle");
        api = Class.forName("android.car.hardware.XpVehicle.IXpVehicle");
        vehicle = Class.forName(api.getName() + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, vb);
    }
    private void call(String name) throws Exception { api.getMethod(name).invoke(vehicle); }
    private void set(String name, int value) throws Exception { api.getMethod(name, int.class).invoke(vehicle, value); }
    private void command(String cmd) throws Exception {
        // Half-degree integer protocol, bounded before invoking the OEM float setter.
        if (cmd.matches("(DRIVER|PASSENGER)_TEMP_(3[6-9]|[4-5][0-9]|6[0-4])")) {
            boolean driver = cmd.startsWith("DRIVER_");
            float target = Integer.parseInt(cmd.substring(cmd.lastIndexOf('_') + 1)) / 2f;
            api.getMethod(driver ? "setHvacDrvSeatTempValue" : "setHvacPsnSeatTempValue", float.class).invoke(vehicle, target);
            return;
        }
        if (cmd.matches("FAN_LEVEL_(10|[1-9])")) {
            set("setHvacFanSpeedLevel", Integer.parseInt(cmd.substring("FAN_LEVEL_".length())));
            return;
        }
        switch (cmd) {
            case "READ": return;
            // OEM self-dry setter/getter use 2=on and 1=off.
            case "CLEAN_ON": set("setHvacSelfDrySwStatus", 2); return;
            case "CLEAN_OFF": set("setHvacSelfDrySwStatus", 1); return;
            // OEM EAV enum: Single=1, Mirror=2, Free=3, Avoid=4, Intelligence=5.
            case "SMART_PASSENGER_ON": {
                int mode = ((Number) api.getMethod("getHvacEavPsnWindMode").invoke(vehicle)).intValue();
                if (mode >= 1 && mode <= 4) previousPsnWindMode = mode;
                set("setHvacEavPsnWindMode", 5); return;
            }
            case "SMART_PASSENGER_OFF": set("setHvacEavPsnWindMode", previousPsnWindMode); return;
            case "DRIVER_UP": call("setHvacDrvSeatTempInc"); return;
            case "DRIVER_DOWN": call("setHvacDrvSeatTempDec"); return;
            case "PASSENGER_UP": call("setHvacPsnSeatTempInc"); return;
            case "PASSENGER_DOWN": call("setHvacPsnSeatTempDec"); return;
            case "FAN_UP": call("setHvacFanSpeedInc"); return;
            case "FAN_DOWN": call("setHvacFanSpeedDec"); return;
            // CarHvacManager uses the second argument to request power-state memory.
            case "POWER_ON": case "POWER_OFF":
                api.getMethod("setHvacPowerEnabled", int.class, int.class).invoke(vehicle, cmd.endsWith("_ON") ? 1 : 0, 1); return;
            // OEM setAcHeatNatureButtonSt modes, distinct from the 0..7 readback states.
            case "AC_COOL": set("setHvacAcEnabled", 1); return;
            case "AC_HEAT": set("setHvacAcEnabled", 2); return;
            case "AC_NATURAL": set("setHvacAcEnabled", 3); return;
            case "AC_AUTO": set("setHvacAcEnabled", 4); return;
            case "AUTO_ON": set("setHvacAutoModeEnabled", 1); return;
            case "AUTO_OFF": set("setHvacAutoModeEnabled", 0); return;
            // OEM HvacController.setHvacDriverSyncMode passes 0/1, not the getter's 1/2/3 enum.
            case "SYNC_ON": set("setHvacTempLeftSyncEnabled", 1); return;
            case "SYNC_OFF": set("setHvacTempLeftSyncEnabled", 0); return;
            case "CYCLE_INNER": set("setHvacAirCycleMode", 1); return;
            case "CYCLE_OUTSIDE": set("setHvacAirCycleMode", 2); return;
            case "CYCLE_AUTO": set("setHvacAirCycleMode", 6); return;
            // OEM air-distribution commands toggle each outlet; they are not getter combination values.
            case "DIRECTION_WINDOW": set("setHvacAirDistributionMode", 1); return;
            case "DIRECTION_FACE": set("setHvacAirDistributionMode", 2); return;
            case "DIRECTION_FEET": set("setHvacAirDistributionMode", 3); return;
            case "FRONT_ON": set("setHvacDefrostMode", 1); return;
            case "FRONT_OFF": set("setHvacDefrostMode", 0); return;
            case "REAR_ON": set("setBcmBackDefrostMode", 1); return;
            case "REAR_OFF": set("setBcmBackDefrostMode", 0); return;
            case "ECO_ON": set("setHvacEconEnabled", 1); return;
            case "ECO_OFF": set("setHvacEconEnabled", 0); return;
            case "AQS_ON": set("setHvacAqsEnabled", 1); return;
            case "AQS_OFF": set("setHvacAqsEnabled", 0); return;
            case "DEFOG_ON": set("setHvacAutoDefogSwitch", 1); return;
            case "DEFOG_OFF": set("setHvacAutoDefogSwitch", 0); return;
            default: throw new IllegalArgumentException("unknown command");
        }
    }
    private JSONObject snapshot() throws Exception {
        JSONObject data = new JSONObject();
        for (int i = 0; i < KEYS.length; i++) {
            try {
                Object value = api.getMethod(GETTERS[i]).invoke(vehicle);
                if ("clean".equals(KEYS[i])) {
                    int mode = ((Number) value).intValue();
                    value = mode == 2 ? 1 : mode == 1 ? 0 : JSONObject.NULL;
                } else if ("smart_passenger".equals(KEYS[i])) {
                    int mode = ((Number) value).intValue();
                    value = mode == 5 ? 1 : mode >= 1 && mode <= 4 ? 0 : JSONObject.NULL;
                }
                data.put(KEYS[i], value);
            }
            catch (Exception ignored) { data.put(KEYS[i], JSONObject.NULL); }
        }
        return data;
    }
    public static void main(String[] args) {
        if (args.length != 0 || Process.myUid() != 2000 || Build.VERSION.SDK_INT != 28 || !"E28".equals(Build.MODEL)) return;
        try {
            P7ClimateWorker worker = new P7ClimateWorker();
            ArrayBlockingQueue<String> input = new ArrayBlockingQueue<>(2);
            Thread reader = new Thread(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = in.readLine()) != null) if (line.length() > 32 || !input.offer(line)) break;
                } catch (Exception ignored) { }
                input.offer("STOP");
            }, "climate-input");
            reader.setDaemon(true); reader.start();
            while (true) {
                String cmd = input.poll(5, TimeUnit.SECONDS);
                if (cmd == null || "STOP".equals(cmd)) return;
                boolean ok = true;
                try { worker.command(cmd); } catch (Exception ignored) { ok = false; }
                // A successful Binder return is not a confirmed physical change; publish actual reads.
                if (!"READ".equals(cmd)) Thread.sleep(150);
                JSONObject result = worker.snapshot();
                result.put("ok", ok);
                System.out.println(result.toString()); System.out.println("done"); System.out.flush();
            }
        } catch (Throwable ignored) { System.out.println("ERROR\ndone"); System.out.flush(); }
    }
}
