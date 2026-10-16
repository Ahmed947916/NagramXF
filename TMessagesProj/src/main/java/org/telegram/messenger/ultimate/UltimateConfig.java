package org.telegram.messenger.ultimate;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.text.TextUtils;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * UltimateConfig
 * --------------
 * المدير المركزي لجميع إعدادات التطبيق الخارق:
 * - وضع الشبح المخصص (Granular Ghost Mode) وتجميد آخر ظهور.
 * - كسر قيود الحفظ ولقطات الشاشة (Bypass Restrictions & FLAG_SECURE).
 * - وضع الطوارئ والتمويه (Decoy PIN, Panic Shake Detector, Calculator Disguise).
 * - وضع الأداء الخارق 120FPS (Potato / Ultra-Light Mode).
 * - تخصيص الواجهة والخطوط ومانع النقرات الخاطئة ودمج الرسائل المتتالية.
 * - النسخ الاحتياطي الشامل المشفر (.tgbackup Full Local Snapshot).
 */
public class UltimateConfig {

    private static final String PREF_NAME = "ultimate_telegram_master_config";
    private static SharedPreferences prefs;

    // كاش أنماط الـ Regex المحلية لفلترة الرسائل والإعلانات المزعجة
    private static final List<Pattern> compiledRegexFilters = new ArrayList<>();
    private static Typeface cachedCustomTypeface = null;

    static {
        init();
    }

    public static void init() {
        if (prefs == null && ApplicationLoader.applicationContext != null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            reloadRegexPatterns();
        }
    }

    // =========================================================================================
    // 1. إعدادات الشبح والخصوصية المطلقة (Granular Ghost Mode & Anti-System)
    // =========================================================================================

    public static boolean isAntiDeleteEnabled() {
        return prefs.getBoolean("anti_delete_enabled", true);
    }

    public static boolean isGhostReadEnabled() {
        return prefs.getBoolean("ghost_prevent_read_receipts", true);
    }

    public static boolean isMarkReadOnlyOnReply() {
        return prefs.getBoolean("ghost_read_only_on_reply", true);
    }

    public static boolean isGhostStoryViewEnabled() {
        return prefs.getBoolean("ghost_hide_story_views", true);
    }

    public static boolean isHideTypingAndActivityEnabled() {
        return prefs.getBoolean("ghost_hide_typing_recording", true);
    }

    public static boolean isFreezeLastSeenEnabled() {
        return prefs.getBoolean("ghost_freeze_last_seen", false);
    }

    public static int getFrozenLastSeenTimestamp() {
        return prefs.getInt("ghost_frozen_timestamp", (int) (System.currentTimeMillis() / 1000L) - 86400);
    }

    public static void setFrozenLastSeenNow() {
        prefs.edit()
                .putBoolean("ghost_freeze_last_seen", true)
                .putInt("ghost_frozen_timestamp", (int) (System.currentTimeMillis() / 1000L))
                .apply();
    }

    public static boolean isBypassRestrictionsEnabled() {
        return prefs.getBoolean("bypass_content_restrictions", true);
    }

    public static boolean isSelfDestructSaverEnabled() {
        return prefs.getBoolean("self_destruct_media_saver", true);
    }

    /**
     * إزالة حماية لقطات الشاشة (FLAG_SECURE) من جميع النوافذ والمحادثات السرية
     */
    public static void applyScreenshotBypass(Activity activity) {
        if (activity != null && isBypassRestrictionsEnabled()) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // =========================================================================================
    // 2. الحماية التمويهية وزر الطوارئ (Decoy PIN & Shake Panic Mode)
    // =========================================================================================

    public static boolean verifyPasscodeAndCheckDecoy(String inputPin, int currentAccount) {
        String realPin = prefs.getString("security_real_pin", "");
        String decoyPin = prefs.getString("security_decoy_pin", "");

        if (!TextUtils.isEmpty(decoyPin) && decoyPin.equals(inputPin)) {
            UltimateDatabase.getInstance(currentAccount).setDecoyModeActive(true);
            prefs.edit().putBoolean("decoy_session_active", true).apply();
            return true;
        }
        if (TextUtils.isEmpty(realPin) || realPin.equals(inputPin)) {
            UltimateDatabase.getInstance(currentAccount).setDecoyModeActive(false);
            prefs.edit().putBoolean("decoy_session_active", false).apply();
            return true;
        }
        return false;
    }

    public static void setSecurityPins(String realPin, String decoyPin) {
        prefs.edit()
                .putString("security_real_pin", realPin)
                .putString("security_decoy_pin", decoyPin)
                .apply();
    }

    public static boolean isCalculatorDisguiseActive() {
        return prefs.getBoolean("panic_calculator_disguise", false);
    }

    public static void triggerImmediatePanicMode(int currentAccount) {
        UltimateDatabase.getInstance(currentAccount).setDecoyModeActive(true);
        prefs.edit()
                .putBoolean("decoy_session_active", true)
                .putBoolean("panic_calculator_disguise", true)
                .apply();
    }

    /**
     * حساس هز الهاتف لتفعيل وضع الطوارئ فوراً وإخفاء المحادثات السرية
     */
    public static void registerShakePanicSensor(Context context, int currentAccount, Runnable onPanicTriggered) {
        if (!prefs.getBoolean("enable_shake_panic", true)) return;
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) return;

        sensorManager.registerListener(new SensorEventListener() {
            private long lastShakeTime = 0;

            @Override
            public void onSensorChanged(SensorEvent event) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                double gForce = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
                if (gForce > 2.7F) {
                    long now = System.currentTimeMillis();
                    if (now - lastShakeTime > 1500) {
                        lastShakeTime = now;
                        triggerImmediatePanicMode(currentAccount);
                        if (onPanicTriggered != null) {
                            onPanicTriggered.run();
                        }
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        }, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    // =========================================================================================
    // 3. وضع الأداء الخارق 120FPS والتخصيص البصري (Potato Mode & Deep UI)
    // =========================================================================================

    public static boolean isUltraLightPotatoMode() {
        return prefs.getBoolean("ui_ultra_light_potato_mode", false);
    }

    public static boolean shouldRenderBlurAndShaders() {
        return !isUltraLightPotatoMode() && prefs.getBoolean("ui_enable_blur", true);
    }

    public static boolean shouldAutoPlayAnimatedStickers() {
        return !isUltraLightPotatoMode() && prefs.getBoolean("ui_autoplay_stickers", true);
    }

    public static int getChatBubbleCornerRadius() {
        return prefs.getInt("ui_bubble_corner_radius", 17);
    }

    public static float getChatAvatarScale() {
        return prefs.getFloat("ui_avatar_scale", 1.0f);
    }

    public static boolean isHideCallButtonsEnabled() {
        return prefs.getBoolean("ui_hide_call_buttons", false);
    }

    public static boolean isHideCameraButtonEnabled() {
        return prefs.getBoolean("ui_hide_camera_button", false);
    }

    public static boolean isConfirmBeforeSendStickerOrGif() {
        return prefs.getBoolean("qol_confirm_sticker_gif", true);
    }

    public static boolean isConfirmBeforeVoiceOrCall() {
        return prefs.getBoolean("qol_confirm_voice_call", true);
    }

    public static boolean isMessageCombinerEnabled() {
        return prefs.getBoolean("qol_combine_consecutive_messages", false);
    }

    public static Typeface getCustomAppTypeface() {
        if (cachedCustomTypeface != null) return cachedCustomTypeface;
        String fontPath = prefs.getString("ui_custom_font_path", "");
        if (!TextUtils.isEmpty(fontPath)) {
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                try {
                    cachedCustomTypeface = Typeface.createFromFile(fontFile);
                    return cachedCustomTypeface;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        return null;
    }

    // =========================================================================================
    // 4. البريميوم الهجين، مفاتيح الذكاء الاصطناعي، وفلاتر الـ Regex
    // =========================================================================================

    public static boolean isHybridPremiumEnabled() {
        return prefs.getBoolean("hybrid_premium_enabled", true);
    }

    public static boolean isUnlimitedPinsEnabled() {
        return prefs.getBoolean("hybrid_unlimited_pins", true);
    }

    public static String getAiProvider() {
        return prefs.getString("ai_provider", "GEMINI"); // GEMINI, OPENAI, CLAUDE, OLLAMA
    }

    public static String getGeminiApiKey() {
        return prefs.getString("ai_gemini_api_key", "");
    }

    public static String getOpenAiApiKey() {
        return prefs.getString("ai_openai_api_key", "");
    }

    public static String getOllamaBaseUrl() {
        return prefs.getString("ai_ollama_url", "http://127.0.0.1:11434");
    }

    public static void setAiCredentials(String provider, String geminiKey, String openAiKey, String ollamaUrl) {
        prefs.edit()
                .putString("ai_provider", provider)
                .putString("ai_gemini_api_key", geminiKey)
                .putString("ai_openai_api_key", openAiKey)
                .putString("ai_ollama_url", ollamaUrl)
                .apply();
    }

    public static void reloadRegexPatterns() {
        compiledRegexFilters.clear();
        String rawJson = prefs.getString("local_regex_filters_json", "[]");
        try {
            JSONArray arr = new JSONArray(rawJson);
            for (int i = 0; i < arr.length(); i++) {
                String patternStr = arr.getString(i);
                if (!TextUtils.isEmpty(patternStr)) {
                    compiledRegexFilters.add(Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void addRegexFilter(String regex) {
        try {
            Pattern.compile(regex); // التحقق من صحة الـ Regex
            JSONArray arr = new JSONArray(prefs.getString("local_regex_filters_json", "[]"));
            arr.put(regex);
            prefs.edit().putString("local_regex_filters_json", arr.toString()).apply();
            reloadRegexPatterns();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean shouldBlockMessageByRegex(String messageText) {
        if (TextUtils.isEmpty(messageText) || compiledRegexFilters.isEmpty()) return false;
        for (Pattern pattern : compiledRegexFilters) {
            if (pattern.matcher(messageText).find()) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================================
    // 5. النسخ الاحتياطي الشامل المشفر (.tgbackup Full Local Snapshot)
    // =========================================================================================

    public static File exportFullBackupSnapshot(int currentAccount) {
        try {
            File backupFile = new File(ApplicationLoader.getFilesDirFixed(), "UltimateBackup_" + System.currentTimeMillis() + ".tgbackup");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
                // 1. تصدير جميع الإعدادات (JSON)
                JSONObject settingsJson = new JSONObject();
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    settingsJson.put(entry.getKey(), entry.getValue());
                }
                ZipEntry prefsEntry = new ZipEntry("ultimate_settings.json");
                zos.putNextEntry(prefsEntry);
                zos.write(settingsJson.toString(2).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // 2. تصدير قاعدة بيانات المحذوفات والتعديلات والتثبيت المحلي
                File dbFile = ApplicationLoader.applicationContext.getDatabasePath("ultimate_ghost_vault_acc_" + currentAccount + ".db");
                if (dbFile != null && dbFile.exists()) {
                    ZipEntry dbEntry = new ZipEntry("ultimate_ghost_vault.db");
                    zos.putNextEntry(dbEntry);
                    try (FileInputStream fis = new FileInputStream(dbFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = fis.read(buf)) != -1) {
                            zos.write(buf, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
            return backupFile;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static boolean importFullBackupSnapshot(File backupFile, int currentAccount) {
        if (backupFile == null || !backupFile.exists()) return false;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(backupFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("ultimate_settings.json".equals(entry.getName())) {
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) != -1) {
                        sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
                    }
                    JSONObject json = new JSONObject(sb.toString());
                    SharedPreferences.Editor editor = prefs.edit();
                    JSONArray names = json.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String key = names.getString(i);
                            Object val = json.get(key);
                            if (val instanceof Boolean) editor.putBoolean(key, (Boolean) val);
                            else if (val instanceof Integer) editor.putInt(key, (Integer) val);
                            else if (val instanceof Long) editor.putLong(key, (Long) val);
                            else if (val instanceof Double) editor.putFloat(key, ((Double) val).floatValue());
                            else editor.putString(key, String.valueOf(val));
                        }
                    }
                    editor.apply();
                    reloadRegexPatterns();
                } else if ("ultimate_ghost_vault.db".equals(entry.getName())) {
                    File dbFile = ApplicationLoader.applicationContext.getDatabasePath("ultimate_ghost_vault_acc_" + currentAccount + ".db");
                    try (FileOutputStream fos = new FileOutputStream(dbFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
            }
