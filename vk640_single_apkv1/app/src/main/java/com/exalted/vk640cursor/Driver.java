package com.exalted.vk640cursor;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/** Uygulama sureci tarafi: Shizuku izni + kullanici servisine baglanma + surucuyu baslat/durdur. */
final class Driver {
    private static final int REQ = 640;
    private static final int SERVICE_VERSION = 2; /* VkUserService degisince arttir */

    private static Context app;
    private static IVkService svc;
    private static boolean binding;
    private static boolean listeners;
    private static boolean autoStart;
    private static boolean busy; /* surucu komutu calisirken ikincisi baslamasin */
    private static String pending; /* "start" | "stop" | null */
    private static volatile String status = "Hazir degil.";

    private Driver() { }

    static String status() { return status; }

    static boolean shizukuRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean shizukuGranted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** what: "start" ya da "stop". */
    static synchronized void request(Context ctx, String what) {
        app = ctx.getApplicationContext();
        pending = what;
        autoStart = "start".equals(what);
        initListeners();
        proceed();
    }

    /** Servis bagliysa surucunun guncel durumunu arka planda sorar. */
    static synchronized void refresh() {
        final IVkService s = svc;
        if (s == null || pending != null || busy) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try { status = s.status(); } catch (Throwable t) { status = "Servis yanit vermiyor: " + t; }
            }
        }, "vk640-status").start();
    }

    private static void initListeners() {
        if (listeners) return;
        listeners = true;
        Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
            @Override public void onBinderReceived() {
                synchronized (Driver.class) {
                    if (autoStart && pending == null) pending = "start";
                    proceed();
                }
            }
        });
        Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
            @Override public void onBinderDead() {
                synchronized (Driver.class) {
                    svc = null;
                    binding = false;
                    status = "Shizuku durdu. Yeniden baslatinca surucu kendiliginden devam eder.";
                }
            }
        });
        Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
            @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                synchronized (Driver.class) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) proceed();
                    else status = "Shizuku izni verilmedi. Shizuku > Yetkili uygulamalar'dan VK640'a izin ver.";
                }
            }
        });
    }

    private static void proceed() {
        if (pending == null || app == null) return;
        if (!shizukuRunning()) {
            status = "Shizuku calismiyor. Kablosuz hata ayiklamayi ac, Shizuku'yu baslat.";
            return;
        }
        try {
            if (Shizuku.isPreV11()) { status = "Shizuku surumu cok eski, guncelle."; return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    status = "Shizuku izni reddedilmis. Shizuku > Yetkili uygulamalar'dan VK640'a izin ver.";
                } else {
                    status = "Shizuku izni isteniyor...";
                    Shizuku.requestPermission(REQ);
                }
                return;
            }
        } catch (Throwable t) {
            status = "Shizuku hatasi: " + t;
            return;
        }
        if (svc == null) {
            if (!binding) {
                binding = true;
                status = "Shizuku servisine baglaniliyor...";
                try {
                    Shizuku.bindUserService(args(), conn);
                } catch (Throwable t) {
                    binding = false;
                    status = "Servis baglanamadi: " + t;
                }
            }
            return;
        }
        if (busy) return; /* bitince pending tekrar islenir */
        final String what = pending;
        pending = null;
        busy = true;
        final IVkService s = svc;
        final String rot = app.getSharedPreferences("p", Context.MODE_PRIVATE).getString("rot", "180");
        final String bin = app.getApplicationInfo().nativeLibraryDir + "/libvk640_uhid.so";
        status = "stop".equals(what) ? "Durduruluyor..." : "Surucu baslatiliyor (donus=" + rot + ")...";
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    status = "stop".equals(what) ? s.stop() : s.start(bin, rot);
                } catch (Throwable t) {
                    status = "HATA: " + t;
                    synchronized (Driver.class) { svc = null; binding = false; }
                }
                synchronized (Driver.class) {
                    busy = false;
                    if (pending != null) proceed();
                }
            }
        }, "vk640-" + what).start();
    }

    private static Shizuku.UserServiceArgs args() {
        return new Shizuku.UserServiceArgs(new ComponentName(app.getPackageName(), VkUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("vk")
                .debuggable(false)
                .version(SERVICE_VERSION);
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (Driver.class) {
                binding = false;
                if (binder == null || !binder.pingBinder()) { status = "Servis baglantisi gecersiz."; return; }
                svc = IVkService.Stub.asInterface(binder);
                proceed();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (Driver.class) { svc = null; binding = false; }
        }
    };
}
