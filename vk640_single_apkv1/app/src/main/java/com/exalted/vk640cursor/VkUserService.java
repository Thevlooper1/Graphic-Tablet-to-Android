package com.exalted.vk640cursor;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku "kullanici servisi": shell (uid 2000) yetkisiyle ayri bir surecte calisir.
 * Surucuyu (vk640_uhid) /data/local/tmp altina koyup setsid ile ayri oturumda baslatir.
 * PC'den elle yaptigin adimlarin ayni mantigidir.
 */
public class VkUserService extends IVkService.Stub {
    private static final String L = "/data/local/tmp";
    private static final String PS = "ps -A -o PID,ARGS | grep '[v]k640'";

    public VkUserService() { }

    @Override
    public void destroy() { System.exit(0); }

    private static String sh(String cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return "[zaman asimi]";
            }
            InputStream in = p.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] b = new byte[4096];
            int n;
            while (in.available() > 0 && (n = in.read(b)) > 0) sb.append(new String(b, 0, n));
            return sb.toString();
        } catch (Throwable t) {
            return "[hata: " + t + "]";
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @Override
    public String start(String srcBin, String rot) {
        if (rot == null || !rot.matches("auto|0|90|180|270")) rot = "180";

        // Ayni ayarla zaten calisiyorsa dokunma (uygulamaya her donuste yeniden baslatmasin).
        String same = sh("cmp -s '" + srcBin + "' " + L + "/vk640_uhid && echo SAME", 4000);
        String oldRot = sh("cat " + L + "/vk640.rot 2>/dev/null", 2000).trim();
        String ps = sh(PS, 4000);
        if (same.contains("SAME") && oldRot.equals(rot)
                && ps.contains("vk640_run") && ps.contains("vk640_uhid")) {
            return status();
        }

        sh("pkill -f '[v]k640_run'; pkill -f '[v]k640_uhid'", 5000);
        sleep(700);

        String cp = sh("cp '" + srcBin + "' " + L + "/vk640_uhid && chmod 755 " + L
                + "/vk640_uhid && echo OK", 10000);
        if (!cp.contains("OK")) return "HATA: surucu kopyalanamadi (" + srcBin + "): " + cp.trim();

        try {
            String script = "#!/system/bin/sh\nwhile :; do " + L + "/vk640_uhid -r " + rot
                    + " >>" + L + "/vk640.log 2>&1; sleep 2; done\n";
            FileOutputStream f = new FileOutputStream(L + "/vk640_run.sh");
            f.write(script.getBytes());
            f.close();
            FileOutputStream r = new FileOutputStream(L + "/vk640.rot");
            r.write(rot.getBytes());
            r.close();
        } catch (Throwable t) {
            return "HATA: dongu betigi yazilamadi: " + t;
        }
        sh("chmod 755 " + L + "/vk640_run.sh; : > " + L + "/vk640.log", 4000);
        sh("setsid " + L + "/vk640_run.sh >/dev/null 2>&1 </dev/null &", 5000);
        sleep(2500);
        return status();
    }

    @Override
    public String status() {
        String ps = sh(PS, 4000);
        boolean run = ps.contains("vk640_run");
        boolean uhid = ps.contains("vk640_uhid");
        String log = sh("tail -n 6 " + L + "/vk640.log 2>&1", 3000).trim();
        return "dongu: " + (run ? "var" : "YOK") + ", surucu: " + (uhid ? "calisiyor" : "YOK")
                + (log.isEmpty() ? "" : "\nLog: " + log);
    }

    @Override
    public String stop() {
        sh("pkill -f '[v]k640_run'; pkill -f '[v]k640_uhid'", 5000);
        sleep(500);
        return "durduruldu. " + status();
    }
}
