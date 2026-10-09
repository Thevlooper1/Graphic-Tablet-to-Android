package com.exalted.vk640cursor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

public class CursorService extends Service {
    static final int PORT = 47650;
    private WindowManager wm;
    private CursorView view;
    private volatile boolean run;
    private Thread rx;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("cursor", "VK640 imleci",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "cursor")
                .setContentTitle("VK640 imleci calisiyor")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .build();
        startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        SharedPreferences p = getSharedPreferences("p", MODE_PRIVATE);
        float dp = p.getInt("size_dp", 28);
        float px = dp * getResources().getDisplayMetrics().density;
        if (view == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            view = new CursorView(this);
            view.setSizePx(px);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    2038, /* TYPE_APPLICATION_OVERLAY */
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            /* Android 12+: 0.8 ustu opaklikta katman alttaki uygulamaya gecen dokunuslari engeller */
            lp.alpha = 0.79f;
            wm.addView(view, lp);
        } else {
            view.setSizePx(px);
        }
        startReceiver();
        /* Surucuyu da (Shizuku hazirsa) ayaga kaldir; Shizuku sonradan acilirsa kendiliginden devam eder. */
        Driver.request(this, "start");
        return START_STICKY;
    }

    private void startReceiver() {
        if (rx != null && rx.isAlive()) return;
        run = true;
        rx = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "vk640-udp");
        rx.start();
    }

    private void loop() {
        DatagramSocket s = null;
        byte[] buf = new byte[128];
        long last = 0;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), PORT));
            s.setSoTimeout(1000);
            while (run) {
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(pk);
                } catch (SocketTimeoutException e) {
                    /* kopru sustu: imleci gizle */
                    if (view != null && view.show && System.currentTimeMillis() - last > 1500) {
                        view.show = false;
                        view.postInvalidateOnAnimation();
                    }
                    continue;
                }
                last = System.currentTimeMillis();
                String[] t = new String(pk.getData(), 0, pk.getLength()).trim().split(" ");
                if (t.length < 4 || view == null) continue;
                try {
                    view.u = Float.parseFloat(t[0]);
                    view.v = Float.parseFloat(t[1]);
                    view.show = t[2].equals("1");
                    view.tip = t[3].equals("1");
                    view.postInvalidateOnAnimation();
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) {
            /* port mesgul vs.: servis yeniden baslatilinca tekrar denenir */
        } finally {
            if (s != null) s.close();
        }
    }

    @Override
    public void onDestroy() {
        run = false;
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
            view = null;
        }
        super.onDestroy();
    }
}
