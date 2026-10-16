package com.laiqidong.xplay.tools;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone shell-UID read-only probe. No Binder transactions, permission changes or writes. */
public final class P7Probe {
    static final int[] IDS = {557847042,557847045,559944229,557847064,557847063};
    static final String[] NAMES = {"battery","gear","speed","accelerator","brake"};
    static final AtomicBoolean done = new AtomicBoolean();
    static volatile Object car, manager, listener;
    static volatile String stage = "boot";
    static volatile int events;
    static void out(String s) { System.out.println(s); System.out.flush(); }
    static String failure(Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause()!=null) e=e.getCause();
        String msg=e.getMessage();
        String clean=msg==null?"":msg.replace('\n',' ').replace('\r',' ');
        return e.getClass().getSimpleName()+": "+clean.substring(0,Math.min(clean.length(),240));
    }
    static void finish(int code) {
        if(!done.compareAndSet(false,true)) return;
        // Process exit also lets the vehicle service release all listeners if cleanup blocks.
        new Thread(() -> { try {Thread.sleep(1000);}catch(Exception ignored){} System.exit(code); },"probe-exit").start();
        try { if(manager!=null && listener!=null) for(Method m:manager.getClass().getMethods())
            if(m.getName().equals("unregisterCallback")&&m.getParameterTypes().length==1) {m.invoke(manager,listener);break;}
        }catch(Throwable e){out("CLEANUP "+failure(e));}
        try { if(car!=null) car.getClass().getMethod("disconnect").invoke(car); }catch(Throwable ignored){}
        System.exit(code);
    }
    public static void main(String[] args) {
        out("BOOT java-only probe uid="+android.os.Process.myUid());
        new Thread(() -> {try{Thread.sleep(15000);}catch(Exception ignored){} out("TIMEOUT stage="+stage);finish(3);},"probe-deadline").start();
        try {
            String mode=args.length>0?args[0]:"surface";
            stage="framework";
            Class<?> cc=Class.forName("android.car.Car");
            Class<?> pm=Class.forName("android.car.hardware.property.CarPropertyManager");
            out("FRAMEWORK available");
            if(mode.equals("surface")) {
                for(Method m:cc.getMethods()) if(Arrays.asList("createCar","connect","getCarManager","disconnect").contains(m.getName()))out("API "+m.toGenericString());
                for(Method m:pm.getMethods()) if(Arrays.asList("getIntProperty","getFloatProperty","getProperty","getReadPermission","getPropertyList","registerCallback","unregisterCallback").contains(m.getName()))out("API "+m.toGenericString());
                finish(0);return;
            }
            stage="context";
            Looper.prepareMainLooper();
            Class<?> at=Class.forName("android.app.ActivityThread");
            Object thread=at.getMethod("systemMain").invoke(null);
            Context ctx=(Context)at.getMethod("getSystemContext").invoke(thread);
            if(args.length>1 && args[1].equals("package"))ctx=ctx.createPackageContext("com.laiqidong.xplay",0);
            out("CONTEXT "+ctx.getPackageName()+" uid="+android.os.Process.myUid());
            Handler handler=new Handler(Looper.getMainLooper());
            ServiceConnection connection=new ServiceConnection(){
                public void onServiceConnected(ComponentName n,IBinder service){out("CONNECTED "+n.getPackageName());inspect(cc);}
                public void onServiceDisconnected(ComponentName n){out("DISCONNECTED");finish(4);}
            };
            stage="createCar:"+mode;
            if(mode.equals("async3")) car=cc.getMethod("createCar",Context.class,ServiceConnection.class,Handler.class).invoke(null,ctx,connection,handler);
            else if(mode.equals("async2"))car=cc.getMethod("createCar",Context.class,ServiceConnection.class).invoke(null,ctx,connection);
            else if(mode.equals("syncHandler"))car=cc.getMethod("createCar",Context.class,Handler.class).invoke(null,ctx,handler);
            else if(mode.equals("sync"))car=cc.getMethod("createCar",Context.class).invoke(null,ctx);
            else if(mode.startsWith("lifecycle")) {
                Class<?> lifecycle=Class.forName("android.car.Car$CarServiceLifecycleListener");
                Object callback=Proxy.newProxyInstance(lifecycle.getClassLoader(),new Class<?>[]{lifecycle},(proxy,method,parameters)->{
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==parameters[0];
                    if(method.getName().equals("toString"))return "P7ProbeLifecycle";
                    if(method.getName().equals("onLifecycleChanged")) {
                        out("LIFECYCLE ready="+parameters[1]);
                        if(Boolean.TRUE.equals(parameters[1])) {car=parameters[0];inspect(cc);}
                    }
                    return null;
                });
                car=cc.getMethod("createCar",Context.class,Handler.class,long.class,lifecycle)
                    .invoke(null,ctx,handler,mode.equals("lifecycleWait")?1000L:0L,callback);
            }
            else throw new IllegalArgumentException("Unknown mode");
            out("FACTORY "+(car==null?"null":"ok"));
            if(car==null){finish(2);return;}
            if(mode.startsWith("async")){stage="connect";cc.getMethod("connect").invoke(car);out("CONNECT requested");}
            else if(!mode.startsWith("lifecycle"))inspect(cc);
            Looper.loop();
        }catch(Throwable e){out("FAIL stage="+stage+" "+failure(e));finish(2);}
    }
    static void inspect(Class<?> cc) {
        try {
            stage="manager";
            manager=cc.getMethod("getCarManager",String.class).invoke(car,"property");
            if(manager==null){out("MANAGER null");finish(2);return;}
            out("MANAGER "+manager.getClass().getName());
            try {
                Object list=manager.getClass().getMethod("getPropertyList").invoke(manager);
                Set<Integer> supported=new HashSet<>();
                if(list instanceof Iterable)for(Object cfg:(Iterable<?>)list)supported.add((Integer)cfg.getClass().getMethod("getPropertyId").invoke(cfg));
                for(int i=0;i<IDS.length;i++)out("VISIBLE "+NAMES[i]+"="+supported.contains(IDS[i]));
            }catch(Throwable e){out("VISIBLE "+failure(e));}
            for(int i=0;i<IDS.length;i++){
                final int id=IDS[i]; final String name=NAMES[i]; stage="query:"+name;
                try{out("PERMISSION "+name+"="+manager.getClass().getMethod("getReadPermission",int.class).invoke(manager,id));}catch(Throwable e){out("PERMISSION "+name+" "+failure(e));}
                query(name,"scalar",i==2?"getFloatProperty":"getIntProperty",new Class<?>[]{int.class,int.class},new Object[]{id,0});
                query(name,"generic","getProperty",new Class<?>[]{int.class,int.class},new Object[]{id,0});
                query(name,"typed","getProperty",new Class<?>[]{Class.class,int.class,int.class},new Object[]{i==2?Float.class:Integer.class,id,0});
            }
            stage="subscribe";
            Class<?> cb=Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
            listener=Proxy.newProxyInstance(cb.getClassLoader(),new Class<?>[]{cb},(proxy,method,args)->{
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                if(method.getName().equals("toString"))return "P7ReadOnlyProbe";
                if(method.getName().equals("onChangeEvent") && events++<20){Object p=args[0];int id=(Integer)p.getClass().getMethod("getPropertyId").invoke(p);out("EVENT "+id+" "+value(p));}
                if(method.getName().equals("onErrorEvent"))out("EVENT_ERROR "+args[0]);
                return null;
            });
            for(int i=0;i<IDS.length;i++)try{Object ok=manager.getClass().getMethod("registerCallback",cb,int.class,float.class).invoke(manager,listener,IDS[i],0f);out("SUBSCRIBE "+NAMES[i]+"="+ok);}catch(Throwable e){out("SUBSCRIBE "+NAMES[i]+" "+failure(e));}
            stage="event-window";
            new Handler(Looper.getMainLooper()).postDelayed(()->{out("DONE event-count="+events);finish(0);},2000);
        }catch(Throwable e){out("FAIL stage="+stage+" "+failure(e));finish(2);}
    }
    static void query(String name,String kind,String method,Class<?>[] types,Object[] args){
        try{Object p=manager.getClass().getMethod(method,types).invoke(manager,args);out("READ "+name+" "+kind+" "+value(p));}
        catch(Throwable e){out("READ "+name+" "+kind+" "+failure(e));}
    }
    static String value(Object p)throws Exception {
        if(p==null)return "null";
        if(p instanceof Number || p instanceof Boolean)return "value="+p;
        Object status=p.getClass().getMethod("getStatus").invoke(p);
        Object timestamp=p.getClass().getMethod("getTimestamp").invoke(p);
        Object v=p.getClass().getMethod("getValue").invoke(p);
        return "status="+status+" timestamp="+timestamp+" value="+(v instanceof Number?v:"not-numeric");
    }
}
