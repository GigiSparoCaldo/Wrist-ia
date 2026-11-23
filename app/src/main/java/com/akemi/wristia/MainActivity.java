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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_SPEECH = 202;
    private static final String PREFS = "wrist_ia_prefs";
    private static final String KEY_TOKEN = "chatgpt_access_token";
    private static final String KEY_COOKIES = "chatgpt_cookies";
    private static final String KEY_ACCOUNT = "chatgpt_account_label";
    private static final String KEY_DEVICE_ID = "chatgpt_device_id";

    private static final String[] MODEL_SLUGS = {"auto", "gpt-6-2", "gpt-6-2-thinking", "gpt-6-1"};
    private static final String[] MODEL_NAMES = {"Auto", "Normale", "Ragionato", "6.1"};

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_CHAT = 1;
    private static final int SCREEN_LOGIN = 2;

    private int currentScreen = SCREEN_HOME;
    private int modelIndex = 0;

    private FrameLayout rootContainer;
    private ScrollView activeScrollView;

    private LinearLayout recentListContainer;
    private TextView homeStatusTv;

    private LinearLayout chatMessagesContainer;
    private TextView chatHeaderTitleTv;
    private Button modelToggleBtn;
    private EditText chatInputBox;
    private Button chatSendBtn;

    private String activeConversationId = null;
    private String activeParentNodeId = null;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureOaiDeviceId();

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.BLACK);
        setContentView(rootContainer);

        if (getToken().isEmpty()) {
            showLoginScreen();
        } else {
            showHomeScreen();
        }
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
        rootContainer.removeAllViews();

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

        rootContainer.addView(root);
        scroll.requestFocus();

        loadRecentChatsAsync();
    }

    private void loadRecentChatsAsync() {
        recentListContainer.removeAllViews();
        final TextView loading = new TextView(this);
        loading.setText("Caricamento chat recenti...");
        loading.setTextColor(Color.parseColor("#94A3B8"));
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        loading.setPadding(dp(4), dp(12), dp(4), dp(12));
        recentListContainer.addView(loading);

        final String token = getToken();
        final String devId = getOaiDeviceId();
        final String cookies = getCookies();

        new Thread(() -> {
            try {
                final List<ChatGptClient.ChatSummary> items =
                        ChatGptClient.fetchRecentChats(token, devId, cookies);
                runOnUiThread(() -> {
                    recentListContainer.removeAllViews();
                    if (items.isEmpty()) {
                        final TextView empty = new TextView(this);
                        empty.setText("Nessuna chat recente trovata. Tocca Nuova Chat per iniziare!");
                        empty.setTextColor(Color.GRAY);
                        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        recentListContainer.addView(empty);
                        return;
                    }
                    for (final ChatGptClient.ChatSummary item : items) {
                        addRecentChatCard(item);
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    recentListContainer.removeAllViews();
                    final TextView err = new TextView(this);
                    err.setText("Sessione scaduta o errore rete:\n" + e.getMessage() + "\n\nTocca 🔑 in alto per aggiornare il token.");
                    err.setTextColor(Color.parseColor("#F87171"));
                    err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    recentListContainer.addView(err);
                });
            }
        }).start();
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
                    .setMessage("Vuoi archiviare/nascondere questa chat?")
                    .setPositiveButton("Nascondi", (d, w) -> new Thread(() -> {
                        final boolean ok = ChatGptClient.hideConversation(
                                getToken(), getOaiDeviceId(), getCookies(), item.id);
                        runOnUiThread(() -> {
                            if (ok) {
                                recentListContainer.removeView(card);
                                Toast.makeText(this, "Chat nascosta", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start())
                    .setNegativeButton("Annulla", null)
                    .show();
            return true;
        });

        recentListContainer.addView(card);
    }

    private void openNewChat() {
        activeConversationId = null;
        activeParentNodeId = null;
        buildChatScreen("Nuova Chat");
        addMessageBubble("Ciao! Sono connesso al tuo account ChatGPT. Chiedimi qualsiasi cosa con 🎤 o tastiera.", false);
    }

    private void openExistingChat(final ChatGptClient.ChatSummary summary) {
        activeConversationId = summary.id;
        activeParentNodeId = null;
        buildChatScreen(summary.title);
        final TextView loadingTv = addMessageBubble("Caricamento messaggi...", false);

        new Thread(() -> {
            try {
                final ChatGptClient.ConversationDetail detail = ChatGptClient.fetchConversationDetail(
                        getToken(), getOaiDeviceId(), getCookies(), summary.id);
                runOnUiThread(() -> {
                    chatMessagesContainer.removeView(loadingTv);
                    activeParentNodeId = detail.lastNodeId;
                    if (detail.messages.isEmpty()) {
                        addMessageBubble("Chat vuota. Scrivi un messaggio qui sotto!", false);
                    } else {
                        for (final ChatGptClient.ChatMessage m : detail.messages) {
                            addMessageBubble(m.text, "user".equals(m.role));
                        }
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    chatMessagesContainer.removeView(loadingTv);
                    addMessageBubble("Errore caricamento cronologia: " + e.getMessage(), false);
                });
            }
        }).start();
    }

    private void buildChatScreen(final String title) {
        currentScreen = SCREEN_CHAT;
        rootContainer.removeAllViews();

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
            modelIndex = (modelIndex + 1) % MODEL_SLUGS.length;
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
        rootContainer.addView(root);
        scroll.requestFocus();
    }

    private void sendChatMessage() {
        final String text = chatInputBox.getText().toString().trim();
        if (text.isEmpty()) return;
        chatInputBox.setText("");
        addMessageBubble(text, true);

        final TextView thinkingTv = addMessageBubble("ChatGPT sta scrivendo...", false);
        chatSendBtn.setEnabled(false);

        final String token = getToken();
        final String devId = getOaiDeviceId();
        final String cookies = getCookies();
        final String convId = activeConversationId;
        final String parentId = activeParentNodeId;
        final String model = MODEL_SLUGS[modelIndex];

        new Thread(() -> {
            final ChatGptClient.SendResult res = ChatGptClient.sendMessage(
                    token, devId, cookies, convId, parentId, model, text);
            runOnUiThread(() -> {
                chatMessagesContainer.removeView(thinkingTv);
                addMessageBubble(res.replyText, false);
                if (res.ok) {
                    activeConversationId = res.conversationId;
                    activeParentNodeId = res.assistantMessageId;
                    if ("Nuova Chat".equals(chatHeaderTitleTv.getText().toString())) {
                        chatHeaderTitleTv.setText(text);
                    }
                }
                chatSendBtn.setEnabled(true);
            });
        }).start();
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
        rootContainer.removeAllViews();

        final LinearLayout root = createCircularScreenRoot();

        final ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activeScrollView = scroll;

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);

        final TextView hdr = new TextView(this);
        hdr.setText("Login ChatGPT");
        hdr.setTextColor(Color.parseColor("#34D399"));
        hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hdr.setTypeface(null, Typeface.BOLD);
        box.addView(hdr);

        final TextView desc = new TextView(this);
        desc.setText("1. Apri chatgpt.com/api/auth/session su telefono o PC.\n2. Copia e incolla qui sotto il testo o accessToken (eyJ...):");
        desc.setTextColor(Color.parseColor("#CBD5E1"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        desc.setPadding(0, dp(4), 0, dp(6));
        box.addView(desc);

        final EditText tokenInput = new EditText(this);
        tokenInput.setHint("Incolla JSON o eyJ...");
        tokenInput.setTextColor(Color.WHITE);
        tokenInput.setHintTextColor(Color.GRAY);
        tokenInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tokenInput.setSingleLine(true);
        tokenInput.setText(getToken());
        box.addView(tokenInput);

        final TextView statusTv = new TextView(this);
        statusTv.setTextColor(Color.parseColor("#38BDF8"));
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusTv.setPadding(0, dp(4), 0, dp(4));
        box.addView(statusTv);

        final Button saveBtn = new Button(this);
        saveBtn.setText("✅ Verifica e Entra");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        final GradientDrawable saveBg = new GradientDrawable();
        saveBg.setCornerRadius(dp(12));
        saveBg.setColor(Color.parseColor("#10A37F"));
        saveBtn.setBackground(saveBg);
        saveBtn.setOnClickListener(v -> {
            final String extracted = ChatGptClient.extractToken(tokenInput.getText().toString());
            if (extracted.isEmpty()) {
                statusTv.setText("Incolla un token valido.");
                return;
            }
            saveBtn.setEnabled(false);
            statusTv.setText("Verifica account...");
            new Thread(() -> {
                try {
                    final String label = ChatGptClient.verifyAndFetchAccountLabel(
                            extracted, getOaiDeviceId(), getCookies());
                    getPrefs().edit()
                            .putString(KEY_TOKEN, extracted)
                            .putString(KEY_ACCOUNT, label)
                            .apply();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Connesso: " + label, Toast.LENGTH_SHORT).show();
                        showHomeScreen();
                    });
                } catch (final Exception e) {
                    runOnUiThread(() -> {
                        saveBtn.setEnabled(true);
                        statusTv.setText("Errore: " + e.getMessage());
                    });
                }
            }).start();
        });
        box.addView(saveBtn);

        final Button webBtn = new Button(this);
        webBtn.setText("🌐 Prova Login Browser");
        webBtn.setTextColor(Color.WHITE);
        webBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        final GradientDrawable webBg = new GradientDrawable();
        webBg.setCornerRadius(dp(12));
        webBg.setColor(Color.parseColor("#1E293B"));
        webBtn.setBackground(webBg);
        final LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        webLp.topMargin = dp(6);
        webBtn.setLayoutParams(webLp);
        webBtn.setOnClickListener(v -> openEmbeddedWebLogin());
        box.addView(webBtn);

        if (!getToken().isEmpty()) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            final LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(6);
            row.setLayoutParams(rLp);

            final Button backHomeBtn = makePillBtn("← Torna", "#334155", Color.WHITE);
            backHomeBtn.setOnClickListener(v -> showHomeScreen());
            row.addView(backHomeBtn);

            final Button logoutBtn = makePillBtn("Esci", "#7F1D1D", Color.WHITE);
            logoutBtn.setOnClickListener(v -> {
                getPrefs().edit().remove(KEY_TOKEN).remove(KEY_COOKIES).remove(KEY_ACCOUNT).apply();
                Toast.makeText(this, "Disconnesso", Toast.LENGTH_SHORT).show();
                showLoginScreen();
            });
            row.addView(logoutBtn);
            box.addView(row);
        }

        root.addView(scroll);
        rootContainer.addView(root);
        scroll.requestFocus();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openEmbeddedWebLogin() {
        try {
            rootContainer.removeAllViews();
            final LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setBackgroundColor(Color.BLACK);

            final LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            bar.setPadding(dp(12), dp(14), dp(12), dp(4));

            final WebView webView = new WebView(this);
            final WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);

            final Button cancelBtn = makePillBtn("✕", "#334155", Color.WHITE);
            cancelBtn.setOnClickListener(v -> showLoginScreen());
            bar.addView(cancelBtn);

            final Button grabBtn = makePillBtn("✅ Ho fatto login", "#10A37F", Color.WHITE);
            grabBtn.setOnClickListener(v -> webView.evaluateJavascript(
                    "(function(){try{var x=new XMLHttpRequest();x.open('GET','/api/auth/session',false);x.send(null);return x.responseText;}catch(e){return '';}})()",
                    value -> {
                        String unescaped = value == null ? "" : value.trim();
                        if (unescaped.startsWith("\"") && unescaped.endsWith("\"") && unescaped.length() >= 2) {
                            unescaped = unescaped.substring(1, unescaped.length() - 1);
                        }
                        unescaped = unescaped.replace("\\\"", "\"");
                        final String tok = ChatGptClient.extractToken(unescaped);
                        final String ck = CookieManager.getInstance().getCookie("https://chatgpt.com");
                        if (!tok.isEmpty()) {
                            getPrefs().edit()
                                    .putString(KEY_TOKEN, tok)
                                    .putString(KEY_COOKIES, ck == null ? "" : ck)
                                    .putString(KEY_ACCOUNT, "ChatGPT Web Login")
                                    .apply();
                            Toast.makeText(this, "Sessione catturata!", Toast.LENGTH_SHORT).show();
                            showHomeScreen();
                        } else {
                            Toast.makeText(this, "Completa prima il login nella pagina", Toast.LENGTH_SHORT).show();
                        }
                    }));
            bar.addView(grabBtn);

            wrap.addView(bar);
            webView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("https://chatgpt.com");
            wrap.addView(webView);

            rootContainer.addView(wrap);
        } catch (final Throwable t) {
            Toast.makeText(this, "Browser non presente su questo orologio: usa il Token.", Toast.LENGTH_LONG).show();
            showLoginScreen();
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
        } else if (currentScreen == SCREEN_LOGIN && !getToken().isEmpty()) {
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

    private String getCookies() {
        return getPrefs().getString(KEY_COOKIES, "").trim();
    }

    private String getOaiDeviceId() {
        return getPrefs().getString(KEY_DEVICE_ID, "");
    }

    private int dp(final int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
