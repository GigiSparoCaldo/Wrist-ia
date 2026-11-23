package com.akemi.wristia;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ChatGptClient {

    private static final String BASE_URL = "https://chatgpt.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; OnePlus Watch 3) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    public static final class ChatSummary {
        public final String id;
        public final String title;
        public final String updatedAt;

        public ChatSummary(final String id, final String title, final String updatedAt) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
        }
    }

    public static final class ChatMessage {
        public final String role;
        public final String text;

        public ChatMessage(final String role, final String text) {
            this.role = role;
            this.text = text;
        }
    }

    public static final class ConversationDetail {
        public final String id;
        public final String title;
        public final String lastNodeId;
        public final List<ChatMessage> messages;

        public ConversationDetail(
                final String id,
                final String title,
                final String lastNodeId,
                final List<ChatMessage> messages) {
            this.id = id;
            this.title = title;
            this.lastNodeId = lastNodeId;
            this.messages = messages;
        }
    }

    public static final class SendResult {
        public final boolean ok;
        public final String replyText;
        public final String conversationId;
        public final String assistantMessageId;

        public SendResult(
                final boolean ok,
                final String replyText,
                final String conversationId,
                final String assistantMessageId) {
            this.ok = ok;
            this.replyText = replyText;
            this.conversationId = conversationId;
            this.assistantMessageId = assistantMessageId;
        }
    }

    private ChatGptClient() {}

    public static String extractToken(final String rawInput) {
        if (rawInput == null) return "";
        final String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) return "";
        try {
            if (trimmed.startsWith("{")) {
                final JSONObject obj = new JSONObject(trimmed);
                final String tok = obj.optString("accessToken", "").trim();
                if (!tok.isEmpty()) return tok;
            }
        } catch (final Exception ignored) {}

        final int idx = trimmed.indexOf("eyJ");
        if (idx >= 0) {
            final String sub = trimmed.substring(idx);
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sub.length(); i++) {
                final char c = sub.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                    sb.append(c);
                } else {
                    break;
                }
            }
            if (sb.length() > 30) return sb.toString();
        }
        return trimmed;
    }

    public static String verifyAndFetchAccountLabel(
            final String token,
            final String deviceId,
            final String cookies) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new Exception("Token vuoto");
        }

        HttpURLConnection conn = null;
        try {
            conn = openConn(BASE_URL + "/backend-api/me", "GET", token, deviceId, cookies);
            final int code = conn.getResponseCode();
            if (code == 200) {
                final JSONObject me = new JSONObject(readStream(conn.getInputStream()));
                final String email = me.optString("email", "").trim();
                final String name = me.optString("name", "").trim();
                if (!email.isEmpty()) return email;
                if (!name.isEmpty()) return name;
                return "ChatGPT Connesso";
            }
        } catch (final Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }

        try {
            conn = openConn(BASE_URL + "/backend-api/conversations?offset=0&limit=1", "GET", token, deviceId, cookies);
            final int code = conn.getResponseCode();
            if (code == 200) {
                return "ChatGPT Connesso";
            } else if (code == 401 || code == 403) {
                throw new Exception("Token scaduto o bloccato (HTTP " + code + "). Rigeneralo da chatgpt.com/api/auth/session");
            } else {
                throw new Exception("Risposta server HTTP " + code);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static List<ChatSummary> fetchRecentChats(
            final String token,
            final String deviceId,
            final String cookies) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openConn(
                    BASE_URL + "/backend-api/conversations?offset=0&limit=25&order=updated",
                    "GET",
                    token,
                    deviceId,
                    cookies);
            final int code = conn.getResponseCode();
            if (code >= 400) {
                throw new Exception("Errore caricamento chat (HTTP " + code + ")");
            }
            final JSONObject root = new JSONObject(readStream(conn.getInputStream()));
            final JSONArray items = root.optJSONArray("items");
            final List<ChatSummary> list = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    final String id = it.optString("id", "");
                    String title = it.optString("title", "Nuova chat").trim();
                    if (title.isEmpty() || "null".equalsIgnoreCase(title)) title = "Chat senza titolo";
                    String updated = it.optString("update_time", "");
                    if (updated.length() >= 10) updated = updated.substring(0, 10);
                    if (!id.isEmpty()) {
                        list.add(new ChatSummary(id, title, updated));
                    }
                }
            }
            return list;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static ConversationDetail fetchConversationDetail(
            final String token,
            final String deviceId,
            final String cookies,
            final String conversationId) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openConn(
                    BASE_URL + "/backend-api/conversation/" + conversationId,
                    "GET",
                    token,
                    deviceId,
                    cookies);
            final int code = conn.getResponseCode();
            if (code >= 400) {
                throw new Exception("Errore apertura chat (HTTP " + code + ")");
            }
            final JSONObject root = new JSONObject(readStream(conn.getInputStream()));
            final String title = root.optString("title", "Chat");
            final String currentNode = root.optString("current_node", "");
            final JSONObject mapping = root.optJSONObject("mapping");

            final List<ChatMessage> msgs = new ArrayList<>();
            if (mapping != null && !currentNode.isEmpty()) {
                String cursor = currentNode;
                final Set<String> visited = new HashSet<>();
                while (cursor != null && !cursor.isEmpty() && !visited.contains(cursor)) {
                    visited.add(cursor);
                    final JSONObject node = mapping.optJSONObject(cursor);
                    if (node == null) break;
                    final JSONObject msgObj = node.optJSONObject("message");
                    if (msgObj != null) {
                        final JSONObject author = msgObj.optJSONObject("author");
                        final String role = author != null ? author.optString("role", "") : "";
                        final JSONObject content = msgObj.optJSONObject("content");
                        if (content != null && ("user".equals(role) || "assistant".equals(role))) {
                            final String text = extractPartsText(content);
                            if (!text.isEmpty()) {
                                msgs.add(new ChatMessage(role, text));
                            }
                        }
                    }
                    cursor = node.isNull("parent") ? null : node.optString("parent", null);
                }
                Collections.reverse(msgs);
            }
            return new ConversationDetail(conversationId, title, currentNode, msgs);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean hideConversation(
            final String token,
            final String deviceId,
            final String cookies,
            final String conversationId) {
        HttpURLConnection conn = null;
        try {
            conn = openConn(
                    BASE_URL + "/backend-api/conversation/" + conversationId,
                    "PATCH",
                    token,
                    deviceId,
                    cookies);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"is_visible\":false}".getBytes(StandardCharsets.UTF_8));
            }
            return conn.getResponseCode() < 400;
        } catch (final Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static SendResult sendMessage(
            final String token,
            final String deviceId,
            final String cookies,
            final String conversationId,
            final String parentMessageId,
            final String modelSlug,
            final String userText) {
        final SendResult firstTry = sendMessageInternal(
                token, deviceId, cookies, conversationId, parentMessageId, modelSlug, userText);
        if (!firstTry.ok && !"auto".equals(modelSlug) && firstTry.replyText != null) {
            final String lower = firstTry.replyText.toLowerCase(Locale.ROOT);
            if (lower.contains("model") || lower.contains("invalid") || lower.contains("not found")) {
                return sendMessageInternal(
                        token, deviceId, cookies, conversationId, parentMessageId, "auto", userText);
            }
        }
        return firstTry;
    }

    private static SendResult sendMessageInternal(
            final String token,
            final String deviceId,
            final String cookies,
            final String conversationId,
            final String parentMessageId,
            final String modelSlug,
            final String userText) {
        final String sentinelToken = fetchSentinelToken(token, deviceId, cookies);
        HttpURLConnection conn = null;
        try {
            final String userMsgId = UUID.randomUUID().toString();
            final String parentId = (parentMessageId == null || parentMessageId.isEmpty())
                    ? UUID.randomUUID().toString()
                    : parentMessageId;

            final JSONObject msgObj = new JSONObject();
            msgObj.put("id", userMsgId);
            msgObj.put("author", new JSONObject().put("role", "user"));
            msgObj.put("content", new JSONObject()
                    .put("content_type", "text")
                    .put("parts", new JSONArray().put(userText)));

            final JSONObject payload = new JSONObject();
            payload.put("action", "next");
            payload.put("messages", new JSONArray().put(msgObj));
            payload.put("parent_message_id", parentId);
            payload.put("model", modelSlug);
            payload.put("timezone_offset_min", -60);
            payload.put("history_and_training_disabled", false);
            payload.put("conversation_mode", new JSONObject().put("kind", "primary_assistant"));
            payload.put("force_paragen", false);
            payload.put("force_null_suggestion", false);

            if (conversationId != null && !conversationId.isEmpty()) {
                payload.put("conversation_id", conversationId);
            }

            conn = openConn(BASE_URL + "/backend-api/conversation", "POST", token, deviceId, cookies);
            conn.setRequestProperty("Accept", "text/event-stream");
            if (sentinelToken != null && !sentinelToken.isEmpty()) {
                conn.setRequestProperty("OpenAI-Sentinel-Chat-Requirements-Token", sentinelToken);
            }
            conn.setDoOutput(true);
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            final int code = conn.getResponseCode();
            if (code >= 400) {
                final String errBody = readStream(conn.getErrorStream());
                String detail = "Errore HTTP " + code;
                try {
                    final JSONObject ej = new JSONObject(errBody);
                    if (ej.has("detail")) {
                        final Object d = ej.get("detail");
                        if (d instanceof JSONObject) {
                            detail = ((JSONObject) d).optString("message", detail);
                        } else {
                            detail = String.valueOf(d);
                        }
                    }
                } catch (final Exception ignored) {}
                return new SendResult(false, detail, conversationId, parentMessageId);
            }

            String outConvId = conversationId;
            String outAssistantMsgId = parentMessageId;
            String latestText = "";

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    final String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    if (!data.startsWith("{")) continue;
                    try {
                        final JSONObject chunk = new JSONObject(data);
                        final String cid = chunk.optString("conversation_id", "");
                        if (!cid.isEmpty()) outConvId = cid;

                        final JSONObject m = chunk.optJSONObject("message");
                        if (m != null) {
                            final JSONObject author = m.optJSONObject("author");
                            final String role = author != null ? author.optString("role", "") : "";
                            if ("assistant".equals(role)) {
                                final String mid = m.optString("id", "");
                                if (!mid.isEmpty()) outAssistantMsgId = mid;
                                final JSONObject content = m.optJSONObject("content");
                                if (content != null) {
                                    final String part = extractPartsText(content);
                                    if (!part.isEmpty()) latestText = part;
                                }
                            }
                        }
                    } catch (final Exception ignored) {}
                }
            }

            if (latestText.isEmpty()) {
                return new SendResult(false, "Nessuna risposta ricevuta da ChatGPT.", outConvId, outAssistantMsgId);
            }
            return new SendResult(true, latestText, outConvId, outAssistantMsgId);
        } catch (final Exception e) {
            return new SendResult(false, "Errore rete: " + e.getMessage(), conversationId, parentMessageId);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String fetchSentinelToken(
            final String token,
            final String deviceId,
            final String cookies) {
        HttpURLConnection conn = null;
        try {
            conn = openConn(
                    BASE_URL + "/backend-api/sentinel/chat-requirements",
                    "POST",
                    token,
                    deviceId,
                    cookies);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"p\":\"\"}".getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() == 200) {
                final JSONObject json = new JSONObject(readStream(conn.getInputStream()));
                return json.optString("token", "");
            }
        } catch (final Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    private static String extractPartsText(final JSONObject content) {
        final JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            final Object item = parts.opt(i);
            if (item instanceof String) {
                if (sb.length() > 0) sb.append("\n");
                sb.append((String) item);
            }
        }
        return sb.toString().trim();
    }

    private static HttpURLConnection openConn(
            final String urlStr,
            final String method,
            final String token,
            final String deviceId,
            final String cookies) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Origin", BASE_URL);
        conn.setRequestProperty("Referer", BASE_URL + "/");
        conn.setRequestProperty("oai-device-id", deviceId);
        conn.setRequestProperty("oai-language", "it-IT");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (cookies != null && !cookies.isEmpty()) {
            conn.setRequestProperty("Cookie", cookies);
        }
        return conn;
    }

    private static String readStream(final InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
