package com.exalted.vk640cursor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String[] ROTS = {"180", "0", "90", "270", "auto"};

    private SharedPreferences prefs;
    private TextView status;
    private TextView sizeLabel;
    private Button rotBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int tick;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (++tick % 3 == 0) Driver.refresh();
            refresh();
            h.postDelayed(this, 1500);
        }
    };

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(15);
        l.addView(status);

        l.addView(btn("Shizuku'yu ac", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i != null) startActivity(i);
                else Toast.makeText(MainActivity.this, "Shizuku kurulu degil.", Toast.LENGTH_LONG).show();
            }
        }));
        l.addView(btn("Ekran ustu cizim izni ver", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        }));

        rotBtn = btn("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String cur = prefs.getString("rot", "180");
                int i = 0;
                for (int k = 0; k < ROTS.length; k++) if (ROTS[k].equals(cur)) i = k;
                prefs.edit().putString("rot", ROTS[(i + 1) % ROTS.length]).apply();
                updateRot();
                startAll(); /* yeni donusle surucuyu yeniden baslatir */
            }
        });
        l.addView(rotBtn);
        updateRot();

        sizeLabel = new TextView(this);
        l.addView(sizeLabel);
        SeekBar sb = new SeekBar(this);
        sb.setMax(80);
        sb.setProgress(prefs.getInt("size_dp", 28) - 12);
        updateSizeLabel(prefs.getInt("size_dp", 28));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                updateSizeLabel(v + 12);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("size_dp", s.getProgress() + 12).apply();
                startSvc();
            }
        });
        l.addView(sb);

        l.addView(btn("BASLAT (surucu + imlec)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("auto", true).apply();
                startAll();
            }
        }));
        l.addView(btn("Durdur", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("auto", false).apply();
                stopService(new Intent(MainActivity.this, CursorService.class));
                Driver.request(MainActivity.this, "stop");
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private void updateRot() {
        rotBtn.setText("Ekran donusu: " + prefs.getString("rot", "180") + "  (degistirmek icin dokun)");
    }

    private void updateSizeLabel(int dp) { sizeLabel.setText("Imlec boyutu: " + dp + " dp"); }

    private void startSvc() {
        if (Settings.canDrawOverlays(this))
            startForegroundService(new Intent(this, CursorService.class));
    }

    /** Imleci ve surucuyu birlikte baslatir. */
    private void startAll() {
        startSvc();
        Driver.request(this, "start"); /* overlay izni olmasa da surucu calissin */
    }

    private void refresh() {
        boolean run = Driver.shizukuRunning();
        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku: ").append(run ? "calisiyor" : "CALISMIYOR").append('\n');
        sb.append("Shizuku izni: ").append(Driver.shizukuGranted() ? "verildi" : "yok").append('\n');
        sb.append("Ekran ustu cizim: ").append(Settings.canDrawOverlays(this) ? "tamam" : "IZIN YOK").append('\n');
        sb.append("Surucu: ").append(Driver.status());
        status.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean("auto", true)) startAll();
        refresh();
        h.post(poll);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(poll);
        super.onPause();
    }
}
