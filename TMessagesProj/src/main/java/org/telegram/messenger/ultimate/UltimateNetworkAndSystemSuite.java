package org.telegram.messenger.ultimate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UltimateNetworkAndSystemSuite
 * -----------------------------
 * 1. مدير البروكسي الذكي (Smart MTProto Auto-Fetcher & Ping Switcher).
 * 2. مسرّع التحميل والرفع متعدد المسارات (Multi-Threaded Chunk & DC Booster).
 * 3. محلل التخزين الشجري (Visual Disk Analyzer & Selective Cleaner).
 * 4. مغير الصوت وقص الصمت (Real-Time PCM Voice Pitch/Filter & Silence Trimmer).
 * 5. واجهة الأوامر الخارجية لتطبيقات Termux & Tasker (Intent API Receiver).
 */
public class UltimateNetworkAndSystemSuite {

    private static final ExecutorService sysExecutor = Executors.newCachedThreadPool();

    // =========================================================================================
    // 1. مدير البروكسي الذكي والتبديل التلقائي لأسرع Ping
    // =========================================================================================

    private static final String PUBLIC_MTPROTO_LIST_URL = "https://raw.githubusercontent.com/hookzof/socks5_list/master/tg/mtproto.json";

    public static void autoFetchAndSwitchToFastestProxy() {
        sysExecutor.execute(() -> {
            try {
                URL url = new URL(PUBLIC_MTPROTO_LIST_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONArray arr = new JSONArray(sb.toString());
                SharedConfig.ProxyInfo bestProxy = null;
                long lowestPing = Long.MAX_VALUE;

                int limit = Math.min(arr.length(), 15);
                for (int i = 0; i < limit; i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String host = obj.optString("host");
                    int port = obj.optInt("port", 443);
                    String secret = obj.optString("secret");

                    long ping = measureTcpPingMillis(host, port);
                    if (ping > 0 && ping < lowestPing) {
                        lowestPing = ping;
                        bestProxy = new SharedConfig.ProxyInfo(host, port, "", "", secret);
                    }
                }

                if (bestProxy != null) {
                    final SharedConfig.ProxyInfo selected = bestProxy;
                    AndroidUtilities.runOnUIThread(() -> {
                        SharedConfig.addProxy(selected);
                        SharedConfig.setCurrentProxy(selected);
                    });
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static long measureTcpPingMillis(String host, int port) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================================================
    // 2. مسرّع التحميل والرفع متعدد المسارات (Multi-Threaded Download/Upload Boost)
    // =========================================================================================

    /**
     * مضاعفة حجم القطعة (Chunk Size) وعدد الاتصالات المتزامنة مع مراكز البيانات (DC1-DC5)
     * للوصول إلى أقصى سرعة تسمح بها الشبكة حتى للحسابات المجانية.
     */
    public static int getBoostedDownloadChunkSize() {
        return 1024 * 512; // 512KB لكل طلب بدلاً من 32KB/128KB الافتراضي
    }

    public static int getBoostedMaxParallelParts() {
        return 8; // 8 مسارات متزامنة في نفس اللحظة
    }

    // =========================================================================================
    // 3. محلل التخزين الشجري والتنظيف الانتقائي الذكي (Visual Disk Analyzer)
    // =========================================================================================

    public static class StorageNode implements Comparable<StorageNode> {
        public final String folderName;
        public final String absolutePath;
        public final long sizeInBytes;
        public final int fileCount;

        public StorageNode(String folderName, String absolutePath, long sizeInBytes, int fileCount) {
            this.folderName = folderName;
            this.absolutePath = absolutePath;
            this.sizeInBytes = sizeInBytes;
            this.fileCount = fileCount;
        }

        @Override
        public int compareTo(StorageNode other) {
            return Long.compare(other.sizeInBytes, this.sizeInBytes);
        }
    }

    public static List<StorageNode> analyzeTelegramStorageTree() {
        List<StorageNode> nodes = new ArrayList<>();
        File cacheRoot = ApplicationLoader.applicationContext.getExternalCacheDir();
        if (cacheRoot == null || !cacheRoot.exists()) return nodes;

        File[] subDirs = cacheRoot.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (dir.isDirectory()) {
                    long[] stats = calculateDirSizeAndCount(dir);
                    nodes.add(new StorageNode(dir.getName(), dir.getAbsolutePath(), stats[0], (int) stats[1]));
                }
            }
        }
        Collections.sort(nodes);
        return nodes;
    }

    private static long[] calculateDirSizeAndCount(File dir) {
        long size = 0;
        long count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    long[] sub = calculateDirSizeAndCount(f);
                    size += sub[0];
                    count += sub[1];
                } else {
                    size += f.length();
                    count++;
                }
            }
        }
        return new long[]{size, count};
    }

    /**
     * تنظيف انتقائي ذكي: يمسح الفيديوهات الضخمة فقط (أكبر من حد معين بالميجابايت) ويترك الصور والمستندات
     */
    public static long cleanLargeVideosSelectively(long minSizeMegabytes) {
        long minBytes = minSizeMegabytes * 1024L * 1024L;
        long freedBytes = 0;
        File cacheRoot = ApplicationLoader.applicationContext.getExternalCacheDir();
        if (cacheRoot == null) return 0;

        File[] allDirs = cacheRoot.listFiles();
        if (allDirs != null) {
            for (File d : allDirs) {
                File[] files = d.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.length() >= minBytes &&
                                (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".mov"))) {
                            long len = f.length();
                            if (f.delete()) freedBytes += len;
                        }
                    }
                }
            }
        }
        return freedBytes;
    }

    // =========================================================================================
    // 4. مغير الصوت وقص الأجزاء الصامتة (PCM Voice Changer & Silence Trimmer)
    // =========================================================================================

    public enum VoiceFilterPreset {
        NONE, DEEP_ANONYMOUS, ROBOTIC_METALLIC, HIGH_HELIUM
    }

    /**
     * معالجة عينات الصوت الخام (16-bit PCM) لتغيير نبرة الصوت وحذف الصمت قبل ضغطه إلى Opus
     */
    public static short[] processPcmSamplesWithVoiceFilter(short[] pcmSamples, VoiceFilterPreset preset, boolean trimSilence) {
        if (pcmSamples == null || pcmSamples.length == 0) return pcmSamples;

        // 1. قص الأجزاء الصامتة (Silence Trimming)
        List<Short> activeSamples = new ArrayList<>(pcmSamples.length);
        final short silenceThreshold = 350;
        for (short sample : pcmSamples) {
            if (!trimSilence || Math.abs(sample) > silenceThreshold) {
                activeSamples.add(sample);
            }
        }

        short[] output = new short[activeSamples.size()];
        for (int i = 0; i < activeSamples.size(); i++) {
            short val = activeSamples.get(i);
            // 2. تطبيق الفلتر الصوتي (DSP Ring Modulation / Pitch Shift Approximation)
            if (preset == VoiceFilterPreset.ROBOTIC_METALLIC) {
                double mod = Math.sin(2.0 * Math.PI * 45.0 * i / 48000.0);
                val = (short) (val * mod);
            } else if (preset == VoiceFilterPreset.DEEP_ANONYMOUS && i > 0) {
                val = (short) ((val + activeSamples.get(i - 1)) / 2); // Low-pass deep filter
            }
            output[i] = val;
        }
        return output;
    }

    // =========================================================================================
    // 5. واجهة التكامل مع Termux و Tasker (External Automation Intent API)
    // =========================================================================================

    public static class TermuxTaskerCommandReceiver extends BroadcastReceiver {
        public static final String ACTION_ULTIMATE_CMD = "org.telegram.messenger.ultimate.ACTION_COMMAND";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_ULTIMATE_CMD.equals(intent.getAction())) return;

            String cmdType = intent.getStringExtra("cmd");
            int account = UserConfig.selectedAccount;

            if ("TOGGLE_GHOST".equalsIgnoreCase(cmdType)) {
                boolean enable = intent.getBooleanExtra("enable", true);
                context.getSharedPreferences("ultimate_telegram_master_config", Context.MODE_PRIVATE)
                        .edit().putBoolean("ghost_prevent_read_receipts", enable).apply();
            } else if ("SEND_TEXT".equalsIgnoreCase(cmdType)) {
                long targetDialogId = intent.getLongExtra("dialog_id", 0);
                String text = intent.getStringExtra("text");
                if (targetDialogId != 0 && !TextUtils.isEmpty(text)) {
                    SendMessagesHelper.getInstance(account).sendMessage(
                            SendMessagesHelper.SendMessageParams.of(text, targetDialogId)
                    );
                }
            } else if ("PANIC_LOCK".equalsIgnoreCase(cmdType)) {
                UltimateConfig.triggerImmediatePanicMode(account);
            }
        }
    }
                  }
              
