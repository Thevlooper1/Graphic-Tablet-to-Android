package com.exalted.vk640cursor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku "kullanici servisi": shell (uid 2000) yetkisiyle ayri bir surecte calisir.
 * Surucuyu (vk640_uhid) /data/local/tmp altina BAYT olarak yazar, setsid ile baslatir
 * ve Android 12+ hayalet surec katilini (Phantom Process Killer) devre disi birakarak korur.
 */
public class VkUserService extends IVkService.Stub {
    private static final String L = "/data/local/tmp";

    public VkUserService() { }

    @Override
    public void destroy() { System.exit(0); }

    private static String sh(String cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Thread rd = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] b = new byte[4096];
                    int n;
                    try { while ((n = in.read(b)) > 0) bo.write(b, 0, n); } catch (Throwable ignored) { }
                }
            });
            rd.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return "[zaman asimi]";
            }
            rd.join(1500);
            return bo.toString();
        } catch (Throwable t) {
            return "[hata: " + t + "]";
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static byte[] readFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFile(String path, byte[] data) throws Exception {
        FileOutputStream o = new FileOutputStream(path);
        o.write(data);
        o.getFD().sync();
        o.close();
    }

    private static boolean alive() {
        String res = sh("pgrep -f vk640_uhid", 2000).trim();
        return !res.isEmpty();
    }

    @Override
    public String start(byte[] bin, String rot) {
        if (rot == null || !rot.matches("auto|0|90|180|270")) rot = "auto";
        if (bin == null || bin.length < 1000) return "HATA: surucu baytlari bos/eksik (" + (bin == null ? 0 : bin.length) + ")";

        String oldRot = sh("cat " + L + "/vk640.rot 2>/dev/null", 2000).trim();
        byte[] cur = readFile(L + "/vk640_uhid");
        if (cur != null && Arrays.equals(cur, bin) && oldRot.equals(rot) && alive()) {
            return status();
        }

        sh("pkill -f '[v]k640_run'; pkill -f '[v]k640_uhid'", 5000);
        sleep(700);

        try {
            writeFile(L + "/vk640_uhid.new", bin);
            String mv = sh("chmod 755 " + L + "/vk640_uhid.new && mv -f " + L + "/vk640_uhid.new "
                    + L + "/vk640_uhid && echo OK", 6000);
            if (!mv.contains("OK")) return "HATA: surucu yazilamadi: " + mv.trim() + "\n" + diag();
        } catch (Throwable t) {
            return "HATA: surucu yazilamadi: " + t + "\n" + diag();
        }

        try {
            String script = "#!/system/bin/sh\nwhile :; do " + L + "/vk640_uhid -r " + rot
                    + " >>" + L + "/vk640.log 2>&1; sleep 2; done\n";
            writeFile(L + "/vk640_run.sh", script.getBytes());
            writeFile(L + "/vk640.rot", rot.getBytes());
        } catch (Throwable t) {
            return "HATA: dongu betigi yazilamadi: " + t + "\n" + diag();
        }

        // Android 12+ Phantom Process Killer'i devre disi birak (arka plan surucusunun oldurulmesini onler)
        sh("device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null", 2000);

        sh("chmod 755 " + L + "/vk640_run.sh; : > " + L + "/vk640.log", 4000);
        sh("setsid " + L + "/vk640_run.sh >/dev/null 2>&1 </dev/null &", 5000);

        // Surucunun baslayip baslamadigini dogrulamak icin 3 saniye bekle
        sleep(3000);
        if (!alive()) {
            return "UYARI: surucu baslamadi ya da hemen cikti.\n" + status();
        }
        return status();
    }

    private static String diag() {
        return "id: " + sh("id", 2000).trim()
                + "\nSELinux: " + sh("getenforce", 2000).trim()
                + "\n" + sh("ls -l /dev/uhid /dev/input/event* 2>&1 | head -20", 3000).trim();
    }

    @Override
    public String status() {
        boolean run = !sh("pgrep -f vk640_run", 2000).trim().isEmpty();
        boolean uhid = alive();
        String log = sh("tail -n 15 " + L + "/vk640.log 2>&1", 3000).trim();
        String bin = sh("ls -l " + L + "/vk640_uhid 2>&1", 2000).trim();
        return "dongu: " + (run ? "var" : "YOK") + ", surucu: " + (uhid ? "calisiyor" : "YOK")
                + "\nikili: " + bin
                + "\n" + diag()
                + (log.isEmpty() ? "\nLog: (bos)" : "\nLog:\n" + log);
    }

    @Override
    public String stop() {
        sh("pkill -f '[v]k640_run'; pkill -f '[v]k640_uhid'", 5000);
        sleep(500);
        return "durduruldu.\n" + status();
    }
}
