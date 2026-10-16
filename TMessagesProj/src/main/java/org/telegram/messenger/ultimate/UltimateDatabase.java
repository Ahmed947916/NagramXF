package org.telegram.messenger.ultimate;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * UltimateDatabase
 * ----------------
 * المحرك المحلي المشفر لحفظ:
 * 1. الرسائل المحذوفة (Anti-Delete) مع الكائن الكامل (TLRPC.Message Blob) وتوقيت الحذف بالثانية.
 * 2. سجل تعديلات الرسائل (Edit History) مع خوارزمية مقارنة النصوص (LCS Word-Level Diff View).
 * 3. صائد الوسائط ذات التدمير الذاتي (Self-Destruct Media Vault) قبل فتحها ودون إشعار الطرف الآخر.
 * 4. المحادثات المثبتة اللانهائية محلياً (Unlimited Local Pins) لدعم البريميوم الهجين.
 */
public class UltimateDatabase extends SQLiteOpenHelper {

    private static final String DB_PREFIX = "ultimate_ghost_vault_acc_";
    private static final int DB_VERSION = 2;

    // ألوان مقارنة النصوص (Diff View Colors)
    private static final int COLOR_DELETED_TEXT = 0xFFFF4B4B;       // أحمر واضح للنص المحذوف
    private static final int COLOR_DELETED_BG   = 0x28FF4B4B;       // خلفية حمراء شفافة
    private static final int COLOR_ADDED_TEXT   = 0xFF2ECC71;       // أخضر واضح للنص المضاف
    private static final int COLOR_ADDED_BG     = 0x282ECC71;       // خلفية خضراء شفافة

    private static final SparseArray<UltimateDatabase> instances = new SparseArray<>();
    private final int currentAccount;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    // كاش سريع في الرام لمعرفة حالة الرسائل المحذوفة والمعدلة فوراً أثناء التمرير السريع (120FPS)
    private final ConcurrentHashMap<String, Long> deletedMessagesCache = new ConcurrentHashMap<>();
    private final Set<String> editedMessagesCache = Collections.synchronizedSet(new HashSet<>());
    private final Set<Long> localPinnedDialogsCache = Collections.synchronizedSet(new HashSet<>());

    // مفتاح التشفير المحلي لوضع التمويه والطوارئ (Decoy & Panic Mode)
    private SecretKeySpec aesKeySpec;
    private volatile boolean isDecoyModeActive = false;

    public static UltimateDatabase getInstance(int account) {
        UltimateDatabase localInstance = instances.get(account);
        if (localInstance == null) {
            synchronized (UltimateDatabase.class) {
                localInstance = instances.get(account);
                if (localInstance == null) {
                    localInstance = new UltimateDatabase(
                            ApplicationLoader.applicationContext,
                            account
                    );
                    instances.put(account, localInstance);
                }
            }
        }
        return localInstance;
    }

    private UltimateDatabase(Context context, int account) {
        super(context, DB_PREFIX + account + ".db", null, DB_VERSION);
        this.currentAccount = account;
        initDefaultEncryptionKey("UltimateGhostDefaultSecretKey_2026_" + account);
        preloadCachesAsync();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 1. جدول الرسائل المحذوفة (Anti-Delete Table)
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_messages (" +
                "dialog_id INTEGER NOT NULL, " +
                "message_id INTEGER NOT NULL, " +
                "sender_id INTEGER, " +
                "original_date INTEGER, " +
                "deleted_at INTEGER NOT NULL, " +
                "encrypted_text TEXT, " +
                "media_vault_path TEXT, " +
                "raw_tl_bytes BLOB, " +
                "PRIMARY KEY (dialog_id, message_id)" +
                ");");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_deleted_dialog ON deleted_messages(dialog_id);");

        // 2. جدول سجل التعديلات (Edit History Table)
        db.execSQL("CREATE TABLE IF NOT EXISTS edit_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "dialog_id INTEGER NOT NULL, " +
                "message_id INTEGER NOT NULL, " +
                "version_number INTEGER NOT NULL, " +
                "old_text TEXT NOT NULL, " +
                "new_text TEXT NOT NULL, " +
                "edited_at INTEGER NOT NULL" +
                ");");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edit_lookup ON edit_history(dialog_id, message_id);");

        // 3. جدول خزنة الوسائط ذات التدمير الذاتي (Self-Destruct Media Vault)
        db.execSQL("CREATE TABLE IF NOT EXISTS self_destruct_vault (" +
                "dialog_id INTEGER NOT NULL, " +
                "message_id INTEGER NOT NULL, " +
                "sender_id INTEGER, " +
                "ttl_seconds INTEGER, " +
                "saved_path TEXT NOT NULL, " +
                "media_type TEXT, " +
                "captured_at INTEGER NOT NULL, " +
                "PRIMARY KEY (dialog_id, message_id)" +
                ");");

        // 4. جدول التثبيت اللانهائي المحلي (Local Unlimited Pins)
        db.execSQL("CREATE TABLE IF NOT EXISTS local_pins (" +
                "dialog_id INTEGER PRIMARY KEY, " +
                "pin_order INTEGER NOT NULL, " +
                "pinned_at INTEGER NOT NULL" +
                ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            onCreate(db);
        }
    }

    private String makeKey(long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    private void preloadCachesAsync() {
        dbExecutor.execute(() -> {
            try {
                SQLiteDatabase db = getReadableDatabase();
                // تحميل مفاتيح الرسائل المحذوفة في الذاكرة السريعة
                try (Cursor c = db.rawQuery("SELECT dialog_id, message_id, deleted_at FROM deleted_messages", null)) {
                    while (c.moveToNext()) {
                        deletedMessagesCache.put(makeKey(c.getLong(0), c.getInt(1)), c.getLong(2));
                    }
                }
                // تحميل مفاتيح الرسائل المعدلة
                try (Cursor c = db.rawQuery("SELECT DISTINCT dialog_id, message_id FROM edit_history", null)) {
                    while (c.moveToNext()) {
                        editedMessagesCache.add(makeKey(c.getLong(0), c.getInt(1)));
                    }
                }
                // تحميل المحادثات المثبتة محلياً
                try (Cursor c = db.rawQuery("SELECT dialog_id FROM local_pins", null)) {
                    while (c.moveToNext()) {
                        localPinnedDialogsCache.add(c.getLong(0));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    // =========================================================================================
    // القسم الأول: نظام منع الحذف (Advanced Anti-Delete System)
    // =========================================================================================

    /**
     * يتم استدعاؤها عند اعتراض TL_updateDeleteMessages أو TL_updateDeleteChannelMessages
     * تقوم بحفظ الرسالة كاملة مع الكائن الثنائي (TLRPC.Message) وتوقيت الحذف بالمللي ثانية.
     */
    public void markMessagesAsDeletedLocally(long dialogId, List<Integer> messageIds, List<TLRPC.Message> cachedMessages, long deletedTimestamp) {
        if (messageIds == null || messageIds.isEmpty()) return;

        for (int msgId : messageIds) {
            deletedMessagesCache.put(makeKey(dialogId, msgId), deletedTimestamp);
        }

        dbExecutor.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < messageIds.size(); i++) {
                    int msgId = messageIds.get(i);
                    TLRPC.Message msgObj = (cachedMessages != null && i < cachedMessages.size()) ? cachedMessages.get(i) : null;

                    ContentValues cv = new ContentValues();
                    cv.put("dialog_id", dialogId);
                    cv.put("message_id", msgId);
                    cv.put("deleted_at", deletedTimestamp);

                    if (msgObj != null) {
                        long senderId = msgObj.from_id != null ? MessageObject.getPeerId(msgObj.from_id) : dialogId;
                        cv.put("sender_id", senderId);
                        cv.put("original_date", msgObj.date);
                        cv.put("encrypted_text", encryptString(msgObj.message != null ? msgObj.message : ""));

                        // حفظ الكائن الثنائي الكامل للرسالة (الملصقات، الصوتيات، الصور، الردود)
                        SerializedData serializedData = new SerializedData(msgObj.getObjectSize());
                        msgObj.serializeToStream(serializedData);
                        cv.put("raw_tl_bytes", serializedData.toByteArray());
                        serializedData.cleanup();

                        // إذا كانت الرسالة تحتوي على ميديا محملة، ننسخها لخزنة المحذوفات حتى لا يمسحها الكاش
                        File mediaFile = FileLoader.getInstance(currentAccount).getPathToMessage(msgObj);
                        if (mediaFile != null && mediaFile.exists()) {
                            String vaultPath = copyFileToSecretVault(mediaFile, "deleted_" + dialogId + "_" + msgId);
                            cv.put("media_vault_path", vaultPath);
                        }
                    }

                    db.insertWithOnConflict("deleted_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                db.endTransaction();
            }
        });
    }

    /**
     * فحص فوري (O(1) من الرام) لمعرفة هل الرسالة محذوفة أم لا أثناء رسم الفقاعات في ChatMessageCell.
     */
    public boolean isMessageDeleted(long dialogId, int messageId) {
        if (isDecoyModeActive) return false; // إخفاء علامات الحذف في الوضع التمويهي
        return deletedMessagesCache.containsKey(makeKey(dialogId, messageId));
    }

    /**
     * إرجاع توقيت الحذف الدقيق بالثانية لعرضه بجانب وقت الرسالة (مثال: "🗑️ حُذفت 10:42:19 م")
     */
    public String getFormattedDeletionTime(long dialogId, int messageId) {
        if (isDecoyModeActive) return null;
        Long ts = deletedMessagesCache.get(makeKey(dialogId, messageId));
        if (ts == null) return null;
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault());
        return "🗑️ " + sdf.format(new Date(ts));
    }

    /**
     * استرجاع كائن TLRPC.Message كامل من قاعدة البيانات في حال تم مسحه من سيرفر تيليجرام.
     */
    public TLRPC.Message reconstructDeletedMessage(long dialogId, int messageId) {
        if (isDecoyModeActive) return null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT raw_tl_bytes, media_vault_path FROM deleted_messages WHERE dialog_id = ? AND message_id = ?",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)})) {
            if (c.moveToFirst()) {
                byte[] blob = c.getBlob(0);
                String vaultPath = c.getString(1);
                if (blob != null && blob.length > 0) {
                    SerializedData data = new SerializedData(blob);
                    int constructor = data.readInt32(false);
                    TLRPC.Message msg = TLRPC.Message.TLdeserialize(data, constructor, false);
                    data.cleanup();
                    if (msg != null && !TextUtils.isEmpty(vaultPath)) {
                        msg.attachPath = vaultPath;
                    }
                    return msg;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    // =========================================================================================
    // القسم الثاني: سجل التعديلات ومقارنة النصوص (Edit History & Word-Level LCS Diff View)
    // =========================================================================================

    public static class EditRevision {
        public final int versionNumber;
        public final String oldText;
        public final String newText;
        public final long editedAtMillis;

        public EditRevision(int versionNumber, String oldText, String newText, long editedAtMillis) {
            this.versionNumber = versionNumber;
            this.oldText = oldText;
            this.newText = newText;
            this.editedAtMillis = editedAtMillis;
        }

        public String getFormattedEditTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault());
            return sdf.format(new Date(editedAtMillis));
        }
    }

    /**
     * يتم استدعاؤها عند وصول TL_updateEditMessage أو TL_updateEditChannelMessage
     * قبل أن يقوم تيليجرام باستبدال النص القديم بالجديد.
     */
    public void recordMessageEdit(long dialogId, int messageId, String oldText, String newText, long editTimestampMillis) {
        if (TextUtils.isEmpty(oldText) || TextUtils.isEmpty(newText) || TextUtils.equals(oldText, newText)) {
            return;
        }
        editedMessagesCache.add(makeKey(dialogId, messageId));

        dbExecutor.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                int nextVersion = 1;
                try (Cursor c = db.rawQuery(
                        "SELECT MAX(version_number) FROM edit_history WHERE dialog_id = ? AND message_id = ?",
                        new String[]{String.valueOf(dialogId), String.valueOf(messageId)})) {
                    if (c.moveToFirst()) {
                        nextVersion = c.getInt(0) + 1;
                    }
                }

                ContentValues cv = new ContentValues();
                cv.put("dialog_id", dialogId);
                cv.put("message_id", messageId);
                cv.put("version_number", nextVersion);
                cv.put("old_text", encryptString(oldText));
                cv.put("new_text", encryptString(newText));
                cv.put("edited_at", editTimestampMillis);

                db.insert("edit_history", null, cv);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public boolean hasEditHistory(long dialogId, int messageId) {
        if (isDecoyModeActive) return false;
        return editedMessagesCache.contains(makeKey(dialogId, messageId));
    }

    /**
     * جلب جميع النسخ السابقة للرسالة لعرضها في نافذة سجل التعديلات.
     */
    public List<EditRevision> getEditHistory(long dialogId, int messageId) {
        List<EditRevision> history = new ArrayList<>();
        if (isDecoyModeActive) return history;

        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT version_number, old_text, new_text, edited_at FROM edit_history " +
                        "WHERE dialog_id = ? AND message_id = ? ORDER BY version_number ASC",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)})) {
            while (c.moveToNext()) {
                history.add(new EditRevision(
                        c.getInt(0),
                        decryptString(c.getString(1)),
                        decryptString(c.getString(2)),
                        c.getLong(3)
                ));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return history;
    }

    /**
     * خوارزمية مقارنة النصوص (LCS Word-Level Diff View)
     * تقارن النص القديم بالنص الجديد كلمة بكلمة وتنتج SpannableStringBuilder ملون:
     * - الكلمات المحذوفة: باللون الأحمر مع خط في المنتصف (Strikethrough).
     * - الكلمات المضافة: باللون الأخضر العريض (Bold).
     */
    public static SpannableStringBuilder buildColoredDiff(String oldText, String newText) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        // تقسيم النصوص إلى كلمات مع الحفاظ على المسافات وعلامات الترقيم
        String[] oldWords = oldText.trim().isEmpty() ? new String[0] : oldText.split("(?<=\\s)|(?=\\s)");
        String[] newWords = newText.trim().isEmpty() ? new String[0] : newText.split("(?<=\\s)|(?=\\s)");

        int m = oldWords.length;
        int n = newWords.length;

        // بناء مصفوفة Longest Common Subsequence (LCS)
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldWords[i].equals(newWords[j])) {
                    dp[i][j] = 1 + dp[i + 1][j + 1];
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        // تتبع الفروق وتلوين الكلمات المحذوفة والمضافة
        int i = 0, j = 0;
        while (i < m && j < n) {
            if (oldWords[i].equals(newWords[j])) {
                result.append(oldWords[i]);
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                appendStyledDiffToken(result, oldWords[i], true);
                i++;
            } else {
                appendStyledDiffToken(result, newWords[j], false);
                j++;
            }
        }

        while (i < m) {
            appendStyledDiffToken(result, oldWords[i], true);
            i++;
        }
        while (j < n) {
            appendStyledDiffToken(result, newWords[j], false);
            j++;
        }

        return result;
    }

    private static void appendStyledDiffToken(SpannableStringBuilder builder, String token, boolean isDeleted) {
        int start = builder.length();
        builder.append(token);
        int end = builder.length();

        if (token.trim().isEmpty()) {
            return; // لا نقوم بتلوين المسافات الفارغة وحدها
        }

        if (isDeleted) {
            builder.setSpan(new ForegroundColorSpan(COLOR_DELETED_TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new BackgroundColorSpan(COLOR_DELETED_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            builder.setSpan(new ForegroundColorSpan(COLOR_ADDED_TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new BackgroundColorSpan(COLOR_ADDED_BG), sta
