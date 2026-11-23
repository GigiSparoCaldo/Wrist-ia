package com.akemi.wristia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_SPEECH = 202;
    private static final String PREFS = "wrist_ia_prefs";
    private static final String KEY_TOKEN = "chatgpt_access_token";
    private static final String KEY_COOKIES = "chatgpt_cookies";
    private static final String KEY_ACCOUNT = "chatgpt_account_label";
    private static final String KEY_DEVICE_ID = "chatgpt_device_id";

    private static final String[] MODEL_NAMES = {"Auto", "Normale", "Ragionato"};

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_CHAT = 1;
    private static final int SCREEN_LOGIN = 2;

    private int currentScreen = SCREEN_HOME;
    private int modelIndex = 0;

    private FrameLayout rootContainer;
    private FrameLayout uiContainer;
    private WebView bgWebView;
    private boolean webReady = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ScrollView activeScrollView;
    private LinearLayout recentListContainer;
    private TextView homeStatusTv;

    private LinearLayout chatMessagesContainer;
    private TextView chatHeaderTitleTv;
    private Button modelToggleBtn;
    private EditText chatInputBox;
    private Button chatSendBtn;
    private TextView pendingThinkingTv;

    private String activeConversationId = null;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureOaiDeviceId();

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.BLACK);

        initBackgroundWebView();

        uiContainer = new FrameLayout(this);
        uiContainer.setBackgroundColor(Color.BLACK);
        rootContainer.addView(uiContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootContainer);

        if (hasActiveSession()) {
            showHomeScreen();
        } else {
            showLoginScreen();
        }
    }

    private boolean hasActiveSession() {
        final String ck = CookieManager.getInstance().getCookie("https://chatgpt.com");
        if (ck != null && ck.contains("session-token")) return true;
        return !getToken().isEmpty();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void initBackgroundWebView() {
        bgWebView = new WebView(this);
        final WebSettings ws = bgWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setSupportMultipleWindows(true);

        String ua = ws.getUserAgentString();
        if (ua != null) {
            ua = ua.replace("; wv", "").replaceAll("Version/\\d+\\.\\d+\\s*", "");
            ws.setUserAgentString(ua);
        }

        final CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(bgWebView, true);

        bgWebView.addJavascriptInterface(new WebAppBridge(), "AndroidBridge");

        bgWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(
                    final WebView view,
                    final boolean isDialog,
                    final boolean isUserGesture,
                    final Message resultMsg) {
                final WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(final WebView v, final String url) {
                        bgWebView.loadUrl(url);
                        return true;
                    }
                });
                final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        bgWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, final String url) {
                super.onPageFinished(view, url);
                cm.flush();
                if (url != null && url.startsWith("https://chatgpt.com")) {
                    webReady = true;
                    probeSessionInWebView();
                    if (currentScreen == SCREEN_HOME && recentListContainer != null
                            && recentListContainer.getChildCount() <= 1) {
                        handler.postDelayed(() -> loadRecentChatsAsync(), 600);
                    }
                }
            }
        });

        rootContainer.addView(bgWebView, new FrameLayout.LayoutParams(1, 1));
        bgWebView.loadUrl("https://chatgpt.com/");
    }

    private void setWebViewFullScreen(final boolean fullScreen) {
        final FrameLayout.LayoutParams lp;
        if (fullScreen) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            bgWebView.setAlpha(1f);
            uiContainer.setVisibility(View.GONE);
        } else {
            lp = new FrameLayout.LayoutParams(1, 1);
            bgWebView.setAlpha(0.01f);
            uiContainer.setVisibility(View.VISIBLE);
        }
        bgWebView.setLayoutParams(lp);
    }

    private void probeSessionInWebView() {
        if (bgWebView == null) return;
        final String js =
                "(async function(){" +
                "  try {" +
                "    const r = await fetch('/api/auth/session', {credentials:'include'});" +
                "    if (!r.ok) return;" +
                "    const d = await r.json();" +
                "    if (d && d.accessToken) {" +
                "      const did = (d.statsigContext && d.statsigContext.deviceId) || '';" +
                "      const em = (d.user && d.user.email) || 'ChatGPT Connesso';" +
                "      AndroidBridge.onSessionDetected(d.accessToken, did, em);" +
                "    }" +
                "  } catch(e) {}" +
                "})();";
        bgWebView.evaluateJavascript(js, null);
    }

    private void ensureOaiDeviceId() {
        final SharedPreferences sp = getPrefs();
        if (sp.getString(KEY_DEVICE_ID, "").isEmpty()) {
            sp.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply();
        }
    }

    private LinearLayout createCircularScreenRoot() {
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final int sidePad = Math.round(dm.widthPixels * 0.11f);
        final int topPad = Math.round(dm.heightPixels * 0.08f);
        final int botPad = Math.round(dm.heightPixels * 0.15f);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(sidePad, topPad, sidePad, botPad);
        return root;
    }

    private void showHomeScreen() {
        currentScreen = SCREEN_HOME;
        setWebViewFullScreen(false);
        uiContainer.removeAllViews();

        final LinearLayout root = createCircularScreenRoot();

        final LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        final TextView titleTv = new TextView(this);
        titleTv.setText("Wrist IA");
        titleTv.setTextColor(Color.parseColor("#34D399"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(titleTv);

        final Button refreshBtn = makePillBtn("↻", "#1E293B", Color.WHITE);
        refreshBtn.setOnClickListener(v -> loadRecentChatsAsync());
        topBar.addView(refreshBtn);

        final Button keyBtn = makePillBtn("🔑", "#1E293B", Color.WHITE);
        keyBtn.setOnClickListener(v -> showLoginScreen());
        topBar.addView(keyBtn);

        root.addView(topBar);

        final Button newChatBtn = new Button(this);
        newChatBtn.setText("✨ Nuova Chat");
        newChatBtn.setTextColor(Color.WHITE);
        newChatBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        newChatBtn.setTypeface(null, Typeface.BOLD);
        final GradientDrawable newBg = new GradientDrawable();
        newBg.setCornerRadius(dp(14));
        newBg.setColor(Color.parseColor("#10A37F"));
        newChatBtn.setBackground(newBg);
        final LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        newLp.topMargin = dp(6);
        newLp.bottomMargin = dp(6);
        newChatBtn.setLayoutParams(newLp);
        newChatBtn.setOnClickListener(v -> openNewChat());
        root.addView(newChatBtn);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        activeScrollView = scroll;

        recentListContainer = new LinearLayout(this);
        recentListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(recentListContainer);
        root.addView(scroll);

        homeStatusTv = new TextView(this);
        homeStatusTv.setTextColor(Color.parseColor("#94A3B8"));
        homeStatusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        homeStatusTv.setGravity(Gravity.CENTER);
        homeStatusTv.setText(getPrefs().getString(KEY_ACCOUNT, "ChatGPT Connesso"));
        root.addView(homeStatusTv);

        uiContainer.addView(root);
        scroll.requestFocus();

        loadRecentChatsAsync();
    }

    private void loadRecentChatsAsync() {
        if (recentListContainer == null) return;
        recentListContainer.removeAllViews();
        final TextView loading = new TextView(this);
        loading.setText(webReady ? "Caricamento chat recenti..." : "Avvio motore ChatGPT...");
        loading.setTextColor(Color.parseColor("#94A3B8"));
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        loading.setPadding(dp(4), dp(12), dp(4), dp(12));
        recentListContainer.addView(loading);

        if (!webReady) {
            if (bgWebView != null && (bgWebView.getUrl() == null || !bgWebView.getUrl().startsWith("https://chatgpt.com"))) {
                bgWebView.loadUrl("https://chatgpt.com/");
            }
            return;
        }

        final String js =
                "(async function(){" +
                "  try {" +
                "    const s = await fetch('/api/auth/session', {credentials:'include'}).then(r=>r.json());" +
                "    const tok = (s && s.accessToken) || " + JSONObject.quote(getToken()) + ";" +
                "    const did = (s && s.statsigContext && s.statsigContext.deviceId) || " + JSONObject.quote(getOaiDeviceId()) + ";" +
                "    if (s && s.accessToken) {" +
                "      AndroidBridge.onSessionDetected(tok, did, (s.user && s.user.email) || 'ChatGPT Connesso');" +
                "    }" +
                "    const r = await fetch('/backend-api/conversations?offset=0&limit=25&order=updated', {" +
                "      credentials: 'include'," +
                "      headers: { 'Authorization': 'Bearer ' + tok, 'oai-device-id': did, 'oai-language': 'it-IT' }" +
                "    });" +
                "    const txt = await r.text();" +
                "    AndroidBridge.onChatsJson(r.status, txt);" +
                "  } catch(e) {" +
                "    AndroidBridge.onChatsJson(0, String(e));" +
                "  }" +
                "})();";
        bgWebView.evaluateJavascript(js, null);
    }

    private void handleWebChatsResult(final int status, final String body) {
        if (recentListContainer == null || currentScreen != SCREEN_HOME) return;
        recentListContainer.removeAllViews();
        if (status == 200) {
            try {
                final JSONObject root = new JSONObject(body);
                final JSONArray items = root.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    final TextView empty = new TextView(this);
                    empty.setText("Nessuna chat recente trovata. Tocca Nuova Chat per iniziare!");
                    empty.setTextColor(Color.GRAY);
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    recentListContainer.addView(empty);
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    final String id = it.optString("id", "");
                    String title = it.optString("title", "Nuova chat").trim();
                    if (title.isEmpty() || "null".equalsIgnoreCase(title)) title = "Chat senza titolo";
                    String updated = it.optString("update_time", "");
                    if (updated.length() >= 10) updated = updated.substring(0, 10);
                    if (!id.isEmpty()) {
                        addRecentChatCard(new ChatGptClient.ChatSummary(id, title, updated));
                    }
                }
                return;
            } catch (final Exception ignored) {}
        }

        final TextView err = new TextView(this);
        err.setText("Sessione non attiva (HTTP " + status + ").\nTocca 🔑 in alto per accedere direttamente dall'orologio.");
        err.setTextColor(Color.parseColor("#F87171"));
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        recentListContainer.addView(err);
    }

    private void addRecentChatCard(final ChatGptClient.ChatSummary item) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor("#111827"));
        bg.setStroke(dp(1), Color.parseColor("#1F2937"));
        card.setBackground(bg);

        final TextView tTv = new TextView(this);
        tTv.setText(item.title);
        tTv.setTextColor(Color.WHITE);
        tTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tTv.setTypeface(null, Typeface.BOLD);
        tTv.setMaxLines(2);
        tTv.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(tTv);

        if (!item.updatedAt.isEmpty()) {
            final TextView dTv = new TextView(this);
            dTv.setText(item.updatedAt);
            dTv.setTextColor(Color.parseColor("#64748B"));
            dTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            card.addView(dTv);
        }

        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(5);
        card.setLayoutParams(lp);

        card.setOnClickListener(v -> openExistingChat(item));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setMessage("Vuoi nascondere questa chat?")
                    .setPositiveButton("Nascondi", (d, w) -> {
                        final String js =
                                "(async function(){" +
                                "  try {" +
                                "    const s = await fetch('/api/auth/session', {credentials:'include'}).then(r=>r.json());" +
                                "    const tok = (s && s.accessToken) || " + JSONObject.quote(getToken()) + ";" +
                                "    const did = (s && s.statsigContext && s.statsigContext.deviceId) || " + JSONObject.quote(getOaiDeviceId()) + ";" +
                                "    await fetch('/backend-api/conversation/" + item.id + "', {" +
                                "      method: 'PATCH', credentials: 'include'," +
                                "      headers: { 'Content-Type':'application/json', 'Authorization': 'Bearer ' + tok, 'oai-device-id': did }," +
                                "      body: JSON.stringify({is_visible: false})" +
                                "    });" +
                                "  } catch(e) {}" +
                                "})();";
                        bgWebView.evaluateJavascript(js, null);
                        recentListContainer.removeView(card);
                        Toast.makeText(this, "Chat nascosta", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Annulla", null)
                    .show();
            return true;
        });

        recentListContainer.addView(card);
    }

    private void openNewChat() {
        activeConversationId = null;
        if (bgWebView != null) {
            bgWebView.loadUrl("https://chatgpt.com/");
        }
        buildChatScreen("Nuova Chat");
        addMessageBubble("Ciao! Sono connesso al tuo account ChatGPT. Chiedimi qualsiasi cosa con 🎤 o tastiera.", false);
    }

    private void openExistingChat(final ChatGptClient.ChatSummary summary) {
        activeConversationId = summary.id;
        if (bgWebView != null) {
            bgWebView.loadUrl("https://chatgpt.com/c/" + summary.id);
        }
        buildChatScreen(summary.title);
        pendingThinkingTv = addMessageBubble("Caricamento messaggi...", false);

        final String js =
                "(async function(){" +
                "  try {" +
                "    const s = await fetch('/api/auth/session', {credentials:'include'}).then(r=>r.json());" +
                "    const tok = (s && s.accessToken) || " + JSONObject.quote(getToken()) + ";" +
                "    const did = (s && s.statsigContext && s.statsigContext.deviceId) || " + JSONObject.quote(getOaiDeviceId()) + ";" +
                "    const r = await fetch('/backend-api/conversation/" + summary.id + "', {" +
                "      credentials: 'include'," +
                "      headers: { 'Authorization': 'Bearer ' + tok, 'oai-device-id': did, 'oai-language': 'it-IT' }" +
                "    });" +
                "    const txt = await r.text();" +
                "    AndroidBridge.onConversationJson(r.status, " + JSONObject.quote(summary.id) + ", txt);" +
                "  } catch(e) {" +
                "    AndroidBridge.onConversationJson(0, " + JSONObject.quote(summary.id) + ", String(e));" +
                "  }" +
                "})();";
        bgWebView.evaluateJavascript(js, null);
    }

    private void handleWebConversationResult(final int status, final String convId, final String body) {
        if (currentScreen != SCREEN_CHAT || chatMessagesContainer == null) return;
        if (pendingThinkingTv != null) {
            chatMessagesContainer.removeView(pendingThinkingTv);
            pendingThinkingTv = null;
        }
        if (status == 200) {
            try {
                final JSONObject root = new JSONObject(body);
                final String currentNode = root.optString("current_node", "");
                final JSONObject mapping = root.optJSONObject("mapping");
                final List<ChatGptClient.ChatMessage> msgs = new ArrayList<>();
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
                                final JSONArray parts = content.optJSONArray("parts");
                                if (parts != null) {
                                    final StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < parts.length(); i++) {
                                        final Object item = parts.opt(i);
                                        if (item instanceof String) {
                                            if (sb.length() > 0) sb.append("\n");
                                            sb.append((String) item);
                                        }
                                    }
                                    final String t = sb.toString().trim();
                                    if (!t.isEmpty()) {
                                        msgs.add(new ChatGptClient.ChatMessage(role, t));
                                    }
                                }
                            }
                        }
                        cursor = node.isNull("parent") ? null : node.optString("parent", null);
                    }
                    Collections.reverse(msgs);
                }
                if (msgs.isEmpty()) {
                    addMessageBubble("Chat vuota. Scrivi un messaggio qui sotto!", false);
                } else {
                    for (final ChatGptClient.ChatMessage m : msgs) {
                        addMessageBubble(m.text, "user".equals(m.role));
                    }
                }
                return;
            } catch (final Exception ignored) {}
        }
        addMessageBubble("Errore apertura chat (HTTP " + status + "). Riprova tra poco.", false);
    }

    private void buildChatScreen(final String title) {
        currentScreen = SCREEN_CHAT;
        setWebViewFullScreen(false);
        uiContainer.removeAllViews();

        final LinearLayout root = createCircularScreenRoot();

        final LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        final Button backBtn = makePillBtn("←", "#1E293B", Color.WHITE);
        backBtn.setOnClickListener(v -> showHomeScreen());
        topBar.addView(backBtn);

        chatHeaderTitleTv = new TextView(this);
        chatHeaderTitleTv.setText(title);
        chatHeaderTitleTv.setTextColor(Color.WHITE);
        chatHeaderTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chatHeaderTitleTv.setTypeface(null, Typeface.BOLD);
        chatHeaderTitleTv.setSingleLine(true);
        chatHeaderTitleTv.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(4);
        titleLp.rightMargin = dp(4);
        chatHeaderTitleTv.setLayoutParams(titleLp);
        topBar.addView(chatHeaderTitleTv);

        modelToggleBtn = makePillBtn(MODEL_NAMES[modelIndex], "#064E3B", Color.parseColor("#34D399"));
        modelToggleBtn.setOnClickListener(v -> {
            modelIndex = (modelIndex + 1) % MODEL_NAMES.length;
            modelToggleBtn.setText(MODEL_NAMES[modelIndex]);
            Toast.makeText(this, "Modalità: " + MODEL_NAMES[modelIndex], Toast.LENGTH_SHORT).show();
        });
        topBar.addView(modelToggleBtn);

        root.addView(topBar);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        final LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scLp.topMargin = dp(4);
        scLp.bottomMargin = dp(4);
        scroll.setLayoutParams(scLp);
        activeScrollView = scroll;

        chatMessagesContainer = new LinearLayout(this);
        chatMessagesContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(chatMessagesContainer);
        root.addView(scroll);

        final LinearLayout botBar = new LinearLayout(this);
        botBar.setOrientation(LinearLayout.HORIZONTAL);
        botBar.setGravity(Gravity.CENTER_VERTICAL);

        final Button micBtn = makePillBtn("🎤", "#0284C7", Color.WHITE);
        micBtn.setOnClickListener(v -> startVoiceInput());
        botBar.addView(micBtn);

        chatInputBox = new EditText(this);
        chatInputBox.setHint("Messaggio...");
        chatInputBox.setTextColor(Color.WHITE);
        chatInputBox.setHintTextColor(Color.GRAY);
        chatInputBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chatInputBox.setSingleLine(true);
        chatInputBox.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInputBox.setOnEditorActionListener((v, actionId, event) -> {
            sendChatMessage();
            return true;
        });
        final LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inLp.leftMargin = dp(3);
        inLp.rightMargin = dp(3);
        chatInputBox.setLayoutParams(inLp);
        botBar.addView(chatInputBox);

        chatSendBtn = makePillBtn("➤", "#10A37F", Color.WHITE);
        chatSendBtn.setOnClickListener(v -> sendChatMessage());
        botBar.addView(chatSendBtn);

        root.addView(botBar);
        uiContainer.addView(root);
        scroll.requestFocus();
    }

    private void sendChatMessage() {
        final String text = chatInputBox.getText().toString().trim();
        if (text.isEmpty()) return;
        chatInputBox.setText("");
        addMessageBubble(text, true);

        pendingThinkingTv = addMessageBubble("ChatGPT sta scrivendo...", false);
        chatSendBtn.setEnabled(false);

        if ("Nuova Chat".equals(chatHeaderTitleTv.getText().toString())) {
            chatHeaderTitleTv.setText(text);
        }

        final String quotedMsg = JSONObject.quote(text);
        final String js =
                "(function(){" +
                "  const msgText = " + quotedMsg + ";" +
                "  let tries = 0;" +
                "  function attemptSend() {" +
                "    const el = document.querySelector('#prompt-textarea')" +
                "      || document.querySelector('div[contenteditable=\"true\"]')" +
                "      || document.querySelector('textarea');" +
                "    if (!el) {" +
                "      if (++tries < 25) { setTimeout(attemptSend, 350); return; }" +
                "      AndroidBridge.onSendResult(false, 'Pagina ChatGPT non pronta. Tocca 🔑 e verifica il login.', '');" +
                "      return;" +
                "    }" +
                "    const prevCount = document.querySelectorAll('[data-message-author-role=\"assistant\"]').length;" +
                "    el.focus();" +
                "    if (el.tagName === 'TEXTAREA') {" +
                "      const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
                "      setter.call(el, msgText);" +
                "      el.dispatchEvent(new Event('input', { bubbles: true }));" +
                "    } else {" +
                "      document.execCommand('selectAll', false, null);" +
                "      document.execCommand('insertText', false, msgText);" +
                "      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: msgText, inputType: 'insertText' }));" +
                "    }" +
                "    setTimeout(function(){" +
                "      const btn = document.querySelector('button[data-testid=\"send-button\"]')" +
                "        || document.querySelector('button[aria-label*=\"Invia\"]')" +
                "        || document.querySelector('button[aria-label*=\"Send\"]');" +
                "      if (btn && !btn.disabled) {" +
                "        btn.click();" +
                "      } else {" +
                "        el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));" +
                "      }" +
                "      let polls = 0;" +
                "      let lastTxt = '';" +
                "      let stableTicks = 0;" +
                "      const timer = setInterval(function(){" +
                "        polls++;" +
                "        const nodes = document.querySelectorAll('[data-message-author-role=\"assistant\"]');" +
                "        if (nodes.length > prevCount) {" +
                "          const last = nodes[nodes.length - 1];" +
                "          const cur = (last.innerText || last.textContent || '').trim();" +
                "          const stopBtn = document.querySelector('button[data-testid=\"stop-button\"]')" +
                "            || document.querySelector('button[aria-label*=\"Interrompi\"]')" +
                "            || document.querySelector('button[aria-label*=\"Stop\"]');" +
                "          if (cur.length > 0 && !stopBtn && cur === lastTxt) {" +
                "            stableTicks++;" +
                "          } else {" +
                "            stableTicks = 0;" +
                "          }" +
                "          lastTxt = cur;" +
                "          if (stableTicks >= 2 || polls > 140) {" +
                "            clearInterval(timer);" +
                "            const m = window.location.pathname.match(/\\/c\\/([a-zA-Z0-9-]+)/);" +
                "            AndroidBridge.onSendResult(true, cur || 'Risposta ricevuta.', m ? m[1] : '');" +
                "          }" +
                "        } else if (polls > 16) {" +
                "          const alertEl = document.querySelector('[role=\"alert\"]');" +
                "          if (alertEl && alertEl.innerText) {" +
                "            clearInterval(timer);" +
                "            AndroidBridge.onSendResult(false, alertEl.innerText.trim(), '');" +
                "            return;" +
                "          }" +
                "          if (polls > 70) {" +
                "            clearInterval(timer);" +
                "            AndroidBridge.onSendResult(false, 'Tempo scaduto in attesa della risposta di ChatGPT.', '');" +
                "          }" +
                "        }" +
                "      }, 500);" +
                "    }, 250);" +
                "  }" +
                "  attemptSend();" +
                "})();";
        bgWebView.evaluateJavascript(js, null);
    }

    private void handleWebSendResult(final boolean ok, final String replyText, final String convId) {
        if (currentScreen != SCREEN_CHAT || chatMessagesContainer == null) return;
        if (pendingThinkingTv != null) {
            chatMessagesContainer.removeView(pendingThinkingTv);
            pendingThinkingTv = null;
        }
        addMessageBubble(replyText, false);
        if (ok && convId != null && !convId.isEmpty()) {
            activeConversationId = convId;
        }
        if (chatSendBtn != null) {
            chatSendBtn.setEnabled(true);
        }
    }

    private TextView addMessageBubble(final String msg, final boolean isUser) {
        final TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));

        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor(isUser ? "#065F46" : "#1E293B"));
        tv.setBackground(bg);

        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        lp.gravity = isUser ? Gravity.END : Gravity.START;
        tv.setLayoutParams(lp);

        chatMessagesContainer.addView(tv);
        if (activeScrollView != null) {
            activeScrollView.post(() -> activeScrollView.fullScroll(View.FOCUS_DOWN));
        }
        return tv;
    }

    private void showLoginScreen() {
        currentScreen = SCREEN_LOGIN;
        uiContainer.removeAllViews();
        setWebViewFullScreen(true);

        if (bgWebView.getUrl() == null || !bgWebView.getUrl().startsWith("https://chatgpt.com")) {
            bgWebView.loadUrl("https://chatgpt.com/");
        }

        final LinearLayout overlayBar = new LinearLayout(this);
        overlayBar.setOrientation(LinearLayout.HORIZONTAL);
        overlayBar.setGravity(Gravity.CENTER);
        overlayBar.setPadding(dp(16), dp(14), dp(16), dp(4));

        final Button backBtn = makePillBtn("←", "#334155", Color.WHITE);
        backBtn.setOnClickListener(v -> showHomeScreen());
        overlayBar.addView(backBtn);

        final Button doneBtn = makePillBtn("✅ Entra", "#10A37F", Color.WHITE);
        doneBtn.setOnClickListener(v -> {
            probeSessionInWebView();
            showHomeScreen();
        });
        overlayBar.addView(doneBtn);

        final Button logoutBtn = makePillBtn("Esci", "#7F1D1D", Color.WHITE);
        logoutBtn.setOnClickListener(v -> {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            getPrefs().edit().clear().apply();
            bgWebView.loadUrl("https://chatgpt.com/");
            Toast.makeText(this, "Sessione azzerata", Toast.LENGTH_SHORT).show();
        });
        overlayBar.addView(logoutBtn);

        final FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP;
        rootContainer.addView(overlayBar, barLp);

        startLoginAutoCheck(overlayBar);
    }

    private void startLoginAutoCheck(final View overlayBar) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentScreen != SCREEN_LOGIN) {
                    rootContainer.removeView(overlayBar);
                    return;
                }
                probeSessionInWebView();
                handler.postDelayed(this, 2500);
            }
        }, 2500);
    }

    private final class WebAppBridge {
        @JavascriptInterface
        public void onSessionDetected(final String token, final String deviceId, final String email) {
            runOnUiThread(() -> {
                if (token != null && !token.isEmpty()) {
                    final SharedPreferences.Editor ed = getPrefs().edit().putString(KEY_TOKEN, token);
                    if (deviceId != null && !deviceId.isEmpty()) {
                        ed.putString(KEY_DEVICE_ID, deviceId);
                    }
                    if (email != null && !email.isEmpty()) {
                        ed.putString(KEY_ACCOUNT, email);
                    }
                    ed.apply();
                    CookieManager.getInstance().flush();
                    if (currentScreen == SCREEN_LOGIN) {
                        Toast.makeText(MainActivity.this, "Connesso!", Toast.LENGTH_SHORT).show();
                        showHomeScreen();
                    }
                }
            });
        }

        @JavascriptInterface
        public void onChatsJson(final int status, final String body) {
            runOnUiThread(() -> handleWebChatsResult(status, body));
        }

        @JavascriptInterface
        public void onConversationJson(final int status, final String convId, final String body) {
            runOnUiThread(() -> handleWebConversationResult(status, convId, body));
        }

        @JavascriptInterface
        public void onSendResult(final boolean ok, final String replyText, final String convId) {
            runOnUiThread(() -> handleWebSendResult(ok, replyText, convId));
        }
    }

    private void startVoiceInput() {
        try {
            final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toString());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Parla con ChatGPT...");
            startActivityForResult(intent, REQ_SPEECH);
        } catch (final Exception e) {
            Toast.makeText(this, "Input vocale non disponibile", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            final ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty() && chatInputBox != null) {
                chatInputBox.setText(results.get(0));
                sendChatMessage();
            }
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(final MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL
                && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                && activeScrollView != null) {
            float ax = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (ax == 0f) ax = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            final float step = Math.max(ViewConfiguration.get(this).getScaledVerticalScrollFactor(), dp(64));
            activeScrollView.smoothScrollBy(0, Math.round(-ax * step));
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == SCREEN_CHAT) {
            showHomeScreen();
        } else if (currentScreen == SCREEN_LOGIN && hasActiveSession()) {
            showHomeScreen();
        } else {
            super.onBackPressed();
        }
    }

    private Button makePillBtn(final String label, final String bgHex, final int textColor) {
        final Button b = new Button(this);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.parseColor(bgHex));
        b.setBackground(bg);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.leftMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String getToken() {
        return getPrefs().getString(KEY_TOKEN, "").trim();
    }

    private String getOaiDeviceId() {
        return getPrefs().getString(KEY_DEVICE_ID, "");
    }

    private int dp(final int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
