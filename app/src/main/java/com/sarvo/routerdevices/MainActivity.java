package com.sarvo.routerdevices;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 17, 23);
    private static final int CARD = Color.rgb(23, 32, 41);
    private static final int FIELD = Color.rgb(31, 43, 54);
    private static final int TEXT = Color.rgb(235, 243, 247);
    private static final int MUTED = Color.rgb(155, 174, 186);
    private static final int ACCENT = Color.rgb(49, 205, 195);
    private static final int GREEN = Color.rgb(105, 218, 151);
    private static final int RED = Color.rgb(255, 135, 135);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<RouterDevice> currentDevices = new ArrayList<>();
    private List<RouterDevice> savedDevices = new ArrayList<>();

    private EditText hostField;
    private EditText usernameField;
    private EditText passwordField;
    private EditText searchField;
    private Button loginButton;
    private Button logoutButton;
    private Button scanButton;
    private ProgressBar loginProgress;
    private ProgressBar scanProgress;
    private TextView loginStatus;
    private TextView activeSummary;
    private TextView savedSummary;
    private LinearLayout connectionContent;
    private LinearLayout activeDevicesContainer;
    private LinearLayout savedDevicesContainer;

    private RouterClient routerClient;
    private String activeHost = "192.168.1.1";
    private String activeUsername = "admin";
    private String activePassword = "";
    private boolean connected = false;
    private boolean scanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        buildUi();
        savedDevices = SavedDevicesStore.load(this);
        renderDevices();

        SecureCredentials.Saved saved = SecureCredentials.load(this);
        activeHost = saved.host == null ? "192.168.1.1" : saved.host;
        activeUsername = saved.username == null ? "admin" : saved.username;
        activePassword = saved.password == null ? "" : saved.password;

        if (saved.password != null) {
            showConnectingPanel("Reconnecting to your router…");
            startLogin(activeHost, activeUsername, activePassword, true);
        } else {
            showLoginPanel(activeHost, activeUsername, activePassword,
                    saved.password == null
                            ? "Enter your router admin credentials."
                            : "Credentials saved securely. Tap Login to connect.");
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page);

        TextView title = text("Network Scanner Airtel XStream", 29, TEXT, true);
        page.addView(title, matchWrap());
        TextView subtitle = text("Airtel Xstream Fiber  ·  Nokia G-2425G-A", 13, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(20));
        page.addView(subtitle, matchWrap());

        LinearLayout connectionCard = card();
        page.addView(connectionCard, matchWrap());
        TextView connectionTitle = text("ROUTER CONNECTION", 12, ACCENT, true);
        connectionTitle.setLetterSpacing(0.08f);
        connectionCard.addView(connectionTitle, matchWrap());

        connectionContent = new LinearLayout(this);
        connectionContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams connectionContentParams = matchWrap();
        connectionContentParams.topMargin = dp(4);
        connectionCard.addView(connectionContent, connectionContentParams);

        LinearLayout activeHeader = new LinearLayout(this);
        activeHeader.setGravity(Gravity.CENTER_VERTICAL);
        activeHeader.setOrientation(LinearLayout.HORIZONTAL);
        activeHeader.setPadding(0, dp(22), 0, dp(8));
        page.addView(activeHeader, matchWrap());
        TextView activeTitle = text("Active devices", 20, TEXT, true);
        activeHeader.addView(activeTitle, new LinearLayout.LayoutParams(0, -2, 1));
        scanButton = button("Scan", false);
        scanButton.setTextSize(13);
        scanButton.setOnClickListener(v -> scanNetwork());
        activeHeader.addView(scanButton, new LinearLayout.LayoutParams(-2, dp(42)));

        activeSummary = text("No scan performed yet", 13, MUTED, false);
        page.addView(activeSummary, matchWrap());

        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setIndeterminate(true);
        scanProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams scanProgressParams = matchWrap();
        scanProgressParams.topMargin = dp(8);
        page.addView(scanProgress, scanProgressParams);

        searchField = input("Search name, IP, MAC or interface", "", false);
        LinearLayout.LayoutParams searchParams = fieldParams();
        searchParams.topMargin = dp(12);
        page.addView(searchField, searchParams);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderDevices(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        activeDevicesContainer = new LinearLayout(this);
        activeDevicesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams activeParams = matchWrap();
        activeParams.topMargin = dp(8);
        page.addView(activeDevicesContainer, activeParams);

        LinearLayout savedHeader = new LinearLayout(this);
        savedHeader.setOrientation(LinearLayout.HORIZONTAL);
        savedHeader.setGravity(Gravity.CENTER_VERTICAL);
        savedHeader.setPadding(0, dp(22), 0, dp(8));
        page.addView(savedHeader, matchWrap());
        TextView savedTitle = text("All saved devices", 20, TEXT, true);
        savedHeader.addView(savedTitle, new LinearLayout.LayoutParams(0, -2, 1));
        savedSummary = text("No saved devices yet", 13, MUTED, false);
        page.addView(savedSummary, matchWrap());

        savedDevicesContainer = new LinearLayout(this);
        savedDevicesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams savedParams = matchWrap();
        savedParams.topMargin = dp(8);
        page.addView(savedDevicesContainer, savedParams);

        TextView note = text("Read-only access. This app only reads the router's device list; it cannot block devices or change Wi-Fi settings.", 12, MUTED, false);
        note.setPadding(dp(4), dp(18), dp(4), 0);
        page.addView(note, matchWrap());

        setContentView(scroll);
    }

    private void showLoginPanel(String host, String username, String password, String message) {
        connectionContent.removeAllViews();
        hostField = input("Router IP", host == null ? "192.168.1.1" : host, false);
        usernameField = input("Admin username", username == null ? "admin" : username, false);
        passwordField = input("Admin password", password == null ? "" : password, true);
        connectionContent.addView(hostField, fieldParams());
        connectionContent.addView(usernameField, fieldParams());
        connectionContent.addView(passwordField, fieldParams());

        loginButton = button("Login and load devices", true);
        connectionContent.addView(loginButton, buttonParams());
        loginButton.setOnClickListener(v -> {
            String h = hostField.getText().toString().trim();
            String u = usernameField.getText().toString().trim();
            String p = passwordField.getText().toString();
            if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                loadLoginPanelAfterError(h, u, p, "Enter the router IP, admin username and password.");
                return;
            }
            startLogin(h, u, p, false);
        });

        loginProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loginProgress.setIndeterminate(true);
        loginProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        loginProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(12);
        connectionContent.addView(loginProgress, progressParams);

        loginStatus = text(message == null ? "" : message, 13, MUTED, false);
        loginStatus.setPadding(0, dp(10), 0, 0);
        connectionContent.addView(loginStatus, matchWrap());

        SecureCredentials.Saved saved = SecureCredentials.load(this);
        if (saved.password != null) {
            Button forget = button("Forget saved credentials", false);
            forget.setTextSize(12);
            LinearLayout.LayoutParams forgetParams = buttonParams();
            forgetParams.topMargin = dp(8);
            connectionContent.addView(forget, forgetParams);
            forget.setOnClickListener(v -> {
                SecureCredentials.forget(this);
                activePassword = "";
                showLoginPanel("192.168.1.1", "admin", "", "Saved router credentials removed.");
                Toast.makeText(this, "Saved credentials forgotten", Toast.LENGTH_SHORT).show();
            });
        }
        updateScanButton();
    }

    private void showConnectingPanel(String message) {
        connectionContent.removeAllViews();
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        connectionContent.addView(progress, new LinearLayout.LayoutParams(-2, dp(34)));
        TextView status = text(message, 13, MUTED, false);
        status.setPadding(0, dp(8), 0, 0);
        connectionContent.addView(status, matchWrap());
        updateScanButton();
    }

    private void showSessionPanel() {
        connectionContent.removeAllViews();
        TextView status = text("Connected to " + activeHost, 13, GREEN, false);
        status.setPadding(0, dp(8), 0, dp(2));
        connectionContent.addView(status, matchWrap());

        logoutButton = button("Logout", false);
        connectionContent.addView(logoutButton, buttonParams());
        logoutButton.setOnClickListener(v -> logout());
        updateScanButton();
    }

    private void loadLoginPanelAfterError(String host, String username, String password, String error) {
        showLoginPanel(host, username, password, error);
        if (loginStatus != null) loginStatus.setTextColor(RED);
    }

    private void startLogin(String host, String username, String password, boolean automatic) {
        activeHost = host;
        activeUsername = username;
        activePassword = password;

        // Save credentials as soon as Login is pressed, before any router/network work.
        // This also keeps them if the router page read fails after authentication.
        String credentialSaveError = null;
        try {
            boolean keystoreEncrypted = SecureCredentials.save(this, host, username, password);
            if (!keystoreEncrypted) {
                credentialSaveError = "Android Keystore is unavailable. Password saved in this app's private storage, but not hardware-encrypted.";
            }
        } catch (Exception saveError) {
            String detail = saveError.getMessage();
            credentialSaveError = "Could not save the password: " + saveError.getClass().getSimpleName()
                    + (detail == null ? "" : " — " + detail);
        }
        final String credentialSaveWarning = credentialSaveError;

        if (!automatic && loginButton != null) {
            loginButton.setEnabled(false);
            loginButton.setText("Connecting…");
            if (loginProgress != null) loginProgress.setVisibility(View.VISIBLE);
            if (loginStatus != null) {
                loginStatus.setText("Connecting to the router…");
                loginStatus.setTextColor(MUTED);
            }
        } else if (automatic) {
            showConnectingPanel("Reconnecting to " + host + "…");
        }
        updateScanButton();

        executor.execute(() -> {
            RouterClient client = new RouterClient(host, username, password);
            try {
                List<RouterDevice> devices = client.connectAndReadDevices();
                List<RouterDevice> merged = SavedDevicesStore.merge(this, devices);
                mainHandler.post(() -> {
                    routerClient = client;
                    connected = true;
                    currentDevices.clear();
                    currentDevices.addAll(devices);
                    savedDevices = merged;
                    showSessionPanel();
                    updateSummaries();
                    renderDevices();
                    updateScanButton();
                });
            } catch (Exception e) {
                client.logout();
                mainHandler.post(() -> {
                    connected = false;
                    routerClient = null;
                    String error = safeMessage(e);
                    if (credentialSaveWarning != null) error += "\n" + credentialSaveWarning;
                    loadLoginPanelAfterError(host, username, password, error);
                    updateScanButton();
                });
            }
        });
    }

    private void scanNetwork() {
        if (!connected || routerClient == null || scanning) return;
        scanning = true;
        scanButton.setEnabled(false);
        scanButton.setText("Scanning…");
        scanProgress.setVisibility(View.VISIBLE);

        RouterClient client = routerClient;
        executor.execute(() -> {
            try {
                List<RouterDevice> devices = client.connectAndReadDevices();
                List<RouterDevice> merged = SavedDevicesStore.merge(this, devices);
                mainHandler.post(() -> {
                    currentDevices.clear();
                    currentDevices.addAll(devices);
                    savedDevices = merged;
                    scanning = false;
                    scanProgress.setVisibility(View.GONE);
                    scanButton.setText("Scan");
                    scanButton.setEnabled(true);
                    updateSummaries();
                    renderDevices();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    scanning = false;
                    scanProgress.setVisibility(View.GONE);
                    scanButton.setText("Scan");
                    scanButton.setEnabled(true);
                    Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void logout() {
        // Logout ends this in-app session. Saved credentials are retained, and the next app launch auto-connects.
        RouterClient oldClient = routerClient;
        routerClient = null;
        connected = false;
        scanning = false;
        currentDevices.clear();
        renderDevices();

        SecureCredentials.Saved saved = SecureCredentials.load(this);
        String host = saved.host == null ? "192.168.1.1" : saved.host;
        String username = saved.username == null ? "admin" : saved.username;
        String password = saved.password == null ? "" : saved.password;
        showLoginPanel(host, username, password, "Logged out. Saved credentials will be used automatically next time you open the app.");
        updateScanButton();

        executor.execute(() -> {
            if (oldClient != null) oldClient.logout();
        });
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Could not communicate with the router. Check Wi-Fi and credentials.";
        }
        if (message.contains("CLEARTEXT communication") || message.contains("cleartext")) {
            return "Android blocked the local HTTP connection. Confirm this build's network security setting.";
        }
        if (message.contains("Failed to connect") || message.contains("Unable to resolve host")) {
            return "Cannot reach the router. Connect to your Airtel Wi-Fi and check the router IP.";
        }
        return message;
    }

    private void updateSummaries() {
        int active = 0;
        for (RouterDevice device : currentDevices) if (device.active) active++;
        int inactive = 0;
        for (RouterDevice device : savedDevices) if (!device.active) inactive++;
        activeSummary.setText(active + " active devices in the latest scan");
        savedSummary.setText(savedDevices.size() + " saved records  ·  " + active + " active  ·  " + inactive + " inactive");
    }

    private void updateScanButton() {
        if (scanButton == null) return;
        scanButton.setEnabled(connected && !scanning);
        if (!scanning) scanButton.setText("Scan");
    }

    private void renderDevices() {
        if (activeDevicesContainer == null || savedDevicesContainer == null) return;
        String query = searchField == null ? "" : searchField.getText().toString();

        activeDevicesContainer.removeAllViews();
        int activeCount = 0;
        for (RouterDevice device : currentDevices) {
            if (!device.active || !device.matches(query)) continue;
            activeCount++;
            activeDevicesContainer.addView(deviceCard(device, false), matchWrapWithBottom(dp(10)));
        }
        if (activeCount == 0) {
            addEmpty(activeDevicesContainer, currentDevices.isEmpty()
                    ? "Log in and scan the router to see currently active devices."
                    : "No active devices match your search.");
        }

        savedDevicesContainer.removeAllViews();
        int savedCount = 0;
        for (RouterDevice device : savedDevices) {
            if (!device.matches(query)) continue;
            savedCount++;
            savedDevicesContainer.addView(deviceCard(device, true), matchWrapWithBottom(dp(10)));
        }
        if (savedCount == 0) {
            addEmpty(savedDevicesContainer, savedDevices.isEmpty()
                    ? "Devices will be saved here after the first successful scan."
                    : "No saved devices match your search.");
        }
        updateSummaries();
    }

    private void addEmpty(LinearLayout parent, String message) {
        TextView empty = text(message, 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(18), dp(24), dp(18), dp(24));
        parent.addView(empty, matchWrap());
    }

    private View deviceCard(RouterDevice device, boolean showStatus) {
        LinearLayout outer = card();
        outer.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(top, matchWrap());

        TextView name = text(device.displayName(), 17, TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        if (showStatus) {
            TextView state = text(device.active ? "ACTIVE" : "INACTIVE", 10,
                    device.active ? GREEN : MUTED, true);
            state.setPadding(dp(9), dp(5), dp(9), dp(5));
            state.setBackground(pill(device.active ? Color.rgb(28, 66, 57) : Color.rgb(48, 57, 65)));
            top.addView(state, new LinearLayout.LayoutParams(-2, -2));
        }

        addDetail(outer, "IP", device.ip.isEmpty() ? "Not reported" : device.ip);
        addDetail(outer, "MAC", device.mac.isEmpty() ? "Not reported" : device.mac);
        addDetail(outer, "CONNECTION", device.interfaceType.isEmpty() ? "Not reported" : device.interfaceType);
        return outer;
    }

    private void addDetail(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        parent.addView(row, matchWrap());
        TextView key = text(label, 10, MUTED, true);
        key.setLetterSpacing(0.06f);
        row.addView(key, new LinearLayout.LayoutParams(dp(92), -2));
        TextView content = text(value, 13, TEXT, false);
        content.setMaxLines(2);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(round(CARD, dp(20), 0, Color.TRANSPARENT));
        return layout;
    }

    private EditText input(String hint, String value, boolean password) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setHint(hint);
        edit.setPadding(dp(14), dp(11), dp(14), dp(11));
        edit.setBackground(round(FIELD, dp(12), 1, Color.rgb(54, 69, 82)));
        if (value != null && !value.isEmpty()) edit.setText(value);
        if (password) edit.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
        else edit.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_NORMAL);
        return edit;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.rgb(5, 29, 33) : ACCENT);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(round(primary ? ACCENT : FIELD, dp(14), 0, Color.TRANSPARENT));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable round(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(30));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(9);
        p.height = dp(50);
        return p;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(10);
        p.height = dp(50);
        return p;
    }

    private LinearLayout.LayoutParams matchWrapWithBottom(int bottom) {
        LinearLayout.LayoutParams p = matchWrap();
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
