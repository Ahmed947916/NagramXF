package org.telegram.messenger.ultimate;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UltimateAIEngine
 * ----------------
 * محرك الذكاء الاصطناعي المدمج (BYOK - Bring Your Own Key):
 * يدعم Gemini API, OpenAI/Whisper, والنماذج المحلية عبر Ollama:
 * 1. تحويل البصمات الصوتية والفيديوهات الدائرية إلى نص مجاناً (بديل زر البريميوم الرسمي A->).
 * 2. تلخيص آخر 500-1000 رسالة غير مقروءة في المجموعات (Catch-up Summary).
 * 3. إعادة صياغة الرسائل (رسمي، ساخر، مختصر) والترجمة الفورية أثناء الكتابة.
 * 4. استخراج النصوص من الصور مباشرة داخل المحادثة (Built-in Vision OCR).
 */
public class UltimateAIEngine {

    private static final ExecutorService aiExecutor = Executors.newCachedThreadPool();

    public interface AICallback {
        void onSuccess(String resultText);
        void onError(String errorMessage);
    }

    // =========================================================================================
    // 1. تحويل البصمات الصوتية إلى نص (Free Voice-to-Text Hook for Hybrid Premium)
    // =========================================================================================

    public static void transcribeVoiceFile(File audioFile, AICallback callback) {
        if (audioFile == null || !audioFile.exists()) {
            callback.onError("ملف الصوت غير موجود محلياً بعد.");
            return;
        }

        aiExecutor.execute(() -> {
            try {
                String apiKey = UltimateConfig.getGeminiApiKey();
                if (TextUtils.isEmpty(apiKey)) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("يرجى وضع مفتاح Gemini API في إعدادات Ultimate أولاً."));
                    return;
                }

                // قراءة الملف الصوتي وتحويله إلى Base64 لإرساله إلى Gemini 2.5 Flash
                byte[] audioBytes = new byte[(int) audioFile.length()];
                try (FileInputStream fis = new FileInputStream(audioFile)) {
                    fis.read(audioBytes);
                }
                String base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP);

                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "audio/ogg");
                inlineData.put("data", base64Audio);

                JSONObject partAudio = new JSONObject().put("inline_data", inlineData);
                JSONObject partPrompt = new JSONObject().put("text",
                        "فرّغ هذه الرسالة الصوتية إلى نص دقيق بنفس لغتها. إذا كانت طويلة جداً أضف ملخصاً سريعاً في سطرين في النهاية.");

                JSONArray parts = new JSONArray().put(partPrompt).put(partAudio);
                JSONObject content = new JSONObject().put("parts", parts);
                JSONObject payload = new JSONObject().put("contents", new JSONArray().put(content));

                String responseText = executeGeminiRequest(apiKey, payload);
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(responseText));
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError("فشل تفريغ الصوت: " + e.getMessage()));
            }
        });
    }

    // =========================================================================================
    // 2. ملخص المحادثات الطويلة (Catch-up Unread Messages Summary)
    // =========================================================================================

    public static void summarizeUnreadMessages(List<MessageObject> messages, AICallback callback) {
        if (messages == null || messages.isEmpty()) {
            callback.onError("لا توجد رسائل لتلخيصها.");
            return;
        }

        aiExecutor.execute(() -> {
            try {
                StringBuilder conversationLog = new StringBuilder();
                conversationLog.append("لخص النقاش التالي في نقاط مركزة وواضحة بالعربية مع ذكر رقم الرسالة المهمة [ID]:\n\n");

                int count = Math.min(messages.size(), 600);
                for (int i = 0; i < count; i++) {
                    MessageObject msg = messages.get(i);
                    if (msg != null && !TextUtils.isEmpty(msg.messageText)) {
                        conversationLog.append("[#").append(msg.getId()).append("]: ")
                                .append(msg.messageText).append("\n");
                    }
                }

                String result = generateTextPrompt(conversationLog.toString());
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(result));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // =========================================================================================
    // 3. إعادة صياغة الرسائل والترجمة الفورية أثناء الكتابة (Smart Rewriter & Translator)
    // =========================================================================================

    public static void rewriteOrTranslateDraft(String draftText, String mode, String targetLang, AICallback callback) {
        aiExecutor.execute(() -> {
            try {
                String prompt;
                switch (mode) {
                    case "FORMAL":
                        prompt = "أعد صياغة النص التالي بأسلوب رسمي واحترافي قوي دون شرح إضافي:\n" + draftText;
                        break;
                    case "SARCASTIC":
                        prompt = "أعد صياغة النص التالي بأسلوب ذكي وساخر بلمسة فكاهية:\n" + draftText;
                        break;
                    case "SHORT":
                        prompt = "اختصر النص التالي إلى أقصى حد مع الحفاظ على المعنى الكامل:\n" + draftText;
                        break;
                    case "TRANSLATE":
                    default:
                        prompt = "ترجم النص التالي ترجمة طبيعية ودقيقة إلى اللغة (" + targetLang + ") بدون أي مقدمات:\n" + draftText;
                        break;
                }
                String output = generateTextPrompt(prompt);
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(output));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // =========================================================================================
    // 4. استخراج النصوص من الصور داخل المحادثة (Built-in Image OCR)
    // =========================================================================================

    public static void extractTextFromBitmapOCR(Bitmap bitmap, AICallback callback) {
        if (bitmap == null) {
            callback.onError("الصورة غير صالحة.");
            return;
        }

        aiExecutor.execute(() -> {
            try {
                String apiKey = UltimateConfig.getGeminiApiKey();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Image);

                JSONArray parts = new JSONArray()
                        .put(new JSONObject().put("text", "استخرج جميع النصوص الموجودة في هذه الصورة بدقة كاملة كما هي دون أي شرح إضافي:"))
                        .put(new JSONObject().put("inline_data", inlineData));

                JSONObject payload = new JSONObject().put("contents",
                        new JSONArray().put(new JSONObject().put("parts", parts)));

                String extracted = executeGeminiRequest(apiKey, payload);
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(extracted));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> callback.onError("خطأ في استخراج النص: " + e.getMessage()));
            }
        });
    }

    // =========================================================================================
    // محرك الاتصال بـ Gemini API أو Ollama المحلي
    // =========================================================================================

    private static String generateTextPrompt(String prompt) throws Exception {
        String provider = UltimateConfig.getAiProvider();
        if ("OLLAMA".equalsIgnoreCase(provider)) {
            return executeOllamaRequest(prompt);
        }
        String apiKey = UltimateConfig.getGeminiApiKey();
        JSONObject part = new JSONObject().put("text", prompt);
        JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
        JSONObject payload = new JSONObject().put("contents", new JSONArray().put(content));
        return executeGeminiRequest(apiKey, payload);
    }

    private static String executeGeminiRequest(String apiKey, JSONObject payload) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8
        ));
        StringBuilder responseBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            responseBuilder.append(line);
        }
        reader.close();
        conn.disconnect();

        JSONObject jsonResponse = new JSONObject(responseBuilder.toString());
        if (status != 200) {
            throw new Exception(jsonResponse.optJSONObject("error") != null
                    ? jsonResponse.getJSONObject("error").optString("message")
                    : "HTTP Error " + status);
        }

        return jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text").trim();
    }

    private static String executeOllamaRequest(String prompt) throws Exception {
        URL url = new URL(UltimateConfig.getOllamaBaseUrl() + "/api/generate");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        JSONObject req = new JSONObject();
        req.put("model", "llama3");
        req.put("prompt", prompt);
        req.put("stream", false);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString()).optString("response", "");
        }
    }
}
