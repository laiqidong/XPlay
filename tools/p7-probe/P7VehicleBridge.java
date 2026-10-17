package com.laiqidong.xplay.tools;

import android.content.Context;
import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only public Car API bridge, running under the user's approved ADB shell identity. */
public final class P7VehicleBridge {
    static final int[] IDS = {557847045,559944229,557847064,557847063,557847042,559944314,559944315,557847080,557915161,557849607,557849612,557849623,557849624,289410561,557849626,559948806,291505923,559946285,559947266,559947267,559947268,559947269,557915663,557851651,557847079};
    static int[] activeIds = IDS;
    // Standard Car properties use Wh; vendor BATTCAP counters are intentionally not used.
    static final int[] BATTERY_IDS = {557847042,559944315,291504390,291504905,557847080,557847081};
    static final int[] MOTION_IDS = {557847045,559944229};
    static boolean carPlay;
    static boolean batteryProfile, motionProfile;
    static volatile Object car, manager, listener;
    static volatile int subscribed;
    static volatile long lastSpeedValueAt;
    static final long SPEED_REFRESH_MS = 250L;
    static volatile boolean watching, initialized;
    static final AtomicBoolean closing = new AtomicBoolean();
    static synchronized void emit(String line) {System.out.println(line);System.out.flush();}
    static String reason(Throwable e) {
        while(e instanceof InvocationTargetException && e.getCause()!=null)e=e.getCause();
        return e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage()).replace('\n',' ').replace('\r',' ');
    }
    static void cleanup() {
        if(!closing.compareAndSet(false,true))return;
        try {if(manager!=null && listener!=null)for(Method m:manager.getClass().getMethods())
            if(m.getName().equals("unregisterCallback")&&m.getParameterTypes().length==1){m.invoke(manager,listener);break;}
        }catch(Throwable ignored){}
        try{if(car!=null)car.getClass().getMethod("disconnect").invoke(car);}catch(Throwable ignored){}
    }
    static void exit(int code){
        Thread deadline=new Thread(()->{try{Thread.sleep(1500);}catch(Exception ignored){}System.exit(code);},"bridge-exit-deadline");
        deadline.setDaemon(true);deadline.start();cleanup();System.exit(code);
    }
    public static void main(String[] args) {
        watching=args.length>0&&args[0].equals("subscribe");
        carPlay=args.length>1&&args[1].equals("carplay");
        if(carPlay) {
            String profile=args.length>2?args[2]:"both";
            batteryProfile=!profile.equals("motion");motionProfile=!profile.equals("battery");
            activeIds=batteryProfile&&motionProfile?new int[]{557847045,559944229,557847042,559944315,291504390,291504905,557847080,557847081}:
                batteryProfile?BATTERY_IDS:MOTION_IDS;
        }
        new Thread(()->{try{Thread.sleep(12000);}catch(Exception ignored){}
            if(!initialized){emit("ERROR,start-timeout");exit(2);}},"bridge-start-deadline").start();
        try {
            Looper.prepareMainLooper();
            Class<?> at=Class.forName("android.app.ActivityThread");
            Object thread=at.getMethod("systemMain").invoke(null);
            Context context=(Context)at.getMethod("getSystemContext").invoke(thread);
            Class<?> cc=Class.forName("android.car.Car");
            Class<?> lc=Class.forName("android.car.Car$CarServiceLifecycleListener");
            Object ready=Proxy.newProxyInstance(lc.getClassLoader(),new Class<?>[]{lc},(proxy,method,params)->{
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==params[0];
                if(method.getName().equals("toString"))return "XPlayVehicleLifecycle";
                if(method.getName().equals("onLifecycleChanged")&&Boolean.TRUE.equals(params[1])&&manager==null){
                    car=params[0];
                    manager=cc.getMethod("getCarManager",String.class).invoke(car,"property");
                    initialize();
                } else if(method.getName().equals("onLifecycleChanged")&&Boolean.FALSE.equals(params[1])&&initialized) {
                    emit("ERROR,vehicle-service-disconnected");exit(3);
                }
                return null;
            });
            try {
                car=cc.getMethod("createCar",Context.class,Handler.class,long.class,lc)
                    .invoke(null,context,new Handler(Looper.getMainLooper()),0L,ready);
            }catch(Throwable e){
                // A shell process has no ActivityManager app record for the ancillary bind.
                // Continue only when the PUBLIC API has already granted real property subscriptions.
                emit("INFO,connection:"+reason(e));
                if(!watching||manager==null||subscribed==0){exit(2);return;}
            }
            if(!watching){exit(0);return;}
            Thread eof=new Thread(()->{try{while(System.in.read()!=-1){}}catch(Exception ignored){}exit(0);},"bridge-eof");
            eof.setDaemon(true);eof.start();
            Looper.loop();
        }catch(Throwable e){emit("ERROR,"+reason(e));exit(2);}
    }
    static void initialize() throws Exception {
        for(int id:activeIds)readProperty(id);
        if(!watching){initialized=true;emit("DONE");exit(0);return;}
        Class<?> cb=Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
        listener=Proxy.newProxyInstance(cb.getClassLoader(),new Class<?>[]{cb},(proxy,method,params)->{
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==params[0];
            if(method.getName().equals("toString"))return "XPlayVehicleSubscriber";
            if(method.getName().equals("onChangeEvent")){
                Object p=params[0];int id=(Integer)p.getClass().getMethod("getPropertyId").invoke(p);if(!carPlay)emit("INFO,event-"+id);publish(id,p);
            }
            if(method.getName().equals("onErrorEvent"))emit("UNAVAILABLE,"+params[0]);
            return null;
        });
        Method register=manager.getClass().getMethod("registerCallback",cb,int.class,float.class);
        for(int id:activeIds)try{
            if(id==291504390)continue; // Static pack capacity has no event subscription.
            if(Boolean.TRUE.equals(register.invoke(manager,listener,id,carPlay&&id==559944229?4f:0f)))subscribed++;
        } catch(Throwable e){emit("INFO,subscribe-"+id+":"+reason(e));}
        if(subscribed==0){emit("ERROR,no-subscriptions");exit(2);return;}
        initialized=true;emit("READY,"+subscribed);
        if(carPlay){
            Handler h=new Handler(Looper.getMainLooper());
            // On-change battery/gear properties may stay unchanged for minutes. Re-query their
            // authoritative state; never relabel a locally retained value as a new reading.
            if(batteryProfile)h.postDelayed(new Runnable(){public void run(){
                if(closing.get())return;
                for(int id:BATTERY_IDS)if(id!=291504390)readProperty(id);
                h.postDelayed(this,30000);
            }},30000);
            if(motionProfile)h.postDelayed(new Runnable(){public void run(){
                if(closing.get())return;
                readProperty(557847045);
                h.postDelayed(this,1000);
            }},1000);
            // E28 reports speed as ON_CHANGE (min/max rate 0). A 4 Hz subscription
            // does not produce heartbeats at constant speed. Re-read the actual
            // property when callbacks are quiet; never repeat a locally cached sample.
            if(motionProfile)h.postDelayed(new Runnable(){public void run(){
                if(closing.get())return;
                if(SystemClock.elapsedRealtime()-lastSpeedValueAt>=SPEED_REFRESH_MS)readProperty(559944229);
                h.postDelayed(this,SPEED_REFRESH_MS);
            }},SPEED_REFRESH_MS);
        }
    }
    static void readProperty(int id) {try {
            if(id==557847045||id==559944229||id==557847064||id==557847063||id==557847042) {
                String getter=id==559944229?"getFloatProperty":"getIntProperty";
                Object value=manager.getClass().getMethod(getter,int.class,int.class).invoke(manager,id,0);
                String text=numeric(value);
                if(id==559944229&&text!=null)lastSpeedValueAt=SystemClock.elapsedRealtime();
                emit(text==null?"UNAVAILABLE,"+id:"VALUE,"+id+","+text);
            } else {
                Object p=manager.getClass().getMethod("getProperty",int.class,int.class).invoke(manager,id,0);
                publish(id,p);
            }
        }catch(Throwable e){emit("UNAVAILABLE,"+id);emit("INFO,query-"+id+":"+reason(e));}
    }
    static void publish(int id,Object property)throws Exception {
        boolean allowed=false;for(int known:activeIds)if(known==id)allowed=true;if(!allowed)return;
        if(property==null){emit("UNAVAILABLE,"+id);return;}
        Object value=property.getClass().getMethod("getValue").invoke(property);
        int status=(Integer)property.getClass().getMethod("getStatus").invoke(property);
        if((id==291504390||id==291504905)&&status!=0){emit("UNAVAILABLE,"+id);return;}
        if(status!=0&&!carPlay)emit("INFO,status-"+id+":"+status);
        // This firmware sometimes returns nonstandard status codes; preserve numeric raw values.
        String text=numeric(value);
        if(id==559944229&&text!=null)lastSpeedValueAt=SystemClock.elapsedRealtime();
        emit(text==null?"UNAVAILABLE,"+id:"VALUE,"+id+","+text);
    }
    static String numeric(Object value) {
        if(value instanceof Number)return Double.isNaN(((Number)value).doubleValue())||Double.isInfinite(((Number)value).doubleValue())?null:value.toString();
        if(value!=null&&value.getClass().isArray()){
            StringBuilder out=new StringBuilder();int count=Math.min(Array.getLength(value),8);
            for(int i=0;i<count;i++){String n=numeric(Array.get(value,i));if(n==null)return null;if(i>0)out.append(" / ");out.append(n);}
            return out.length()==0?null:out.toString();
        }
        return null;
    }
}
