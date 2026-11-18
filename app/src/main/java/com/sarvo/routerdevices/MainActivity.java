package com.sarvo.routerdevices;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
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
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<RouterDevice> currentDevices = new ArrayList<>();

    private EditText hostField;
    private EditText usernameField;
    private EditText passwordField;
    private EditText searchField;
    private CheckBox rememberCheck;
    private Button connectButton;
    private Button refreshButton;
    private Button forgetButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView summaryText;
    private LinearLayout devicesContainer;

    private String activeHost = "192.168.1.1";
    private String activeUsername = "admin";
    private String activePassword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadSavedCredentials();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page);

        TextView title = text("Router Devices", 29, TEXT, true);
        page.addView(title, matchWrap());
        TextView subtitle = text("Airtel Xstream Fiber  ·  Nokia G-2425G-A", 13, MUTED, false);
        subtitle.setPadding(0, dp(5), 0, dp(20));
        page.addView(subtitle, matchWrap());

        LinearLayout connectionCard = card();
        page.addView(connectionCard, matchWrap());
        TextView connectionTitle = text("ROUTER CONNECTION", 12, ACCENT, true);
        connectionTitle.setLetterSpacing(0.08f);
        connectionCard.addView(connectionTitle, matchWrap());

        hostField = input("Router IP", "192.168.1.1", false);
        usernameField = input("Admin username", "admin", false);
        passwordField = input("Admin password", "", true);
        connectionCard.addView(hostField, fieldParams());
        connectionCard.addView(usernameField, fieldParams());
        connectionCard.addView(passwordField, fieldParams());

        rememberCheck = new CheckBox(this);
        rememberCheck.setText("Remember password securely on this phone");
        rememberCheck.setTextColor(MUTED);
        rememberCheck.setTextSize(13);
        rememberCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        rememberCheck.setPadding(0, dp(2), 0, dp(5));
        connectionCard.addView(rememberCheck, matchWrap());

        connectButton = button("Connect and load devices", true);
        connectionCard.addView(connectButton, buttonParams());
        connectButton.setOnClickListener(v -> connect(false));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(12);
        connectionCard.addView(progressBar, progressParams);

        statusText = text("Not connected. Join your Airtel Wi-Fi, then connect to the router.", 13, MUTED, false);
        statusText.setPadding(0, dp(10), 0, 0);
        connectionCard.addView(statusText, matchWrap());

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.setOrientation(LinearLayout.HORIZONTAL);
        listHeader.setPadding(0, dp(22), 0, dp(10));
        page.addView(listHeader, matchWrap());
        TextView sectionTitle = text("Connected devices", 20, TEXT, true);
        listHeader.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = button("Refresh", false);
        refreshButton.setTextSize(13);
        refreshButton.setOnClickListener(v -> connect(true));
        listHeader.addView(refreshButton, new LinearLayout.LayoutParams(-2, dp(42)));

        summaryText = text("No device list loaded yet", 13, MUTED, false);
        page.addView(summaryText, matchWrap());

        searchField = input("Search name, IP, MAC or interface", "", false);
        LinearLayout.LayoutParams searchParams = fieldParams();
        searchParams.topMargin = dp(12);
        page.addView(searchField, searchParams);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderDevices(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        devicesContainer = new LinearLayout(this);
        devicesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams deviceParams = matchWrap();
        deviceParams.topMargin = dp(8);
        page.addView(devicesContainer, deviceParams);

        forgetButton = button("Forget saved router credentials", false);
        forgetButton.setTextColor(MUTED);
        LinearLayout.LayoutParams forgetParams = buttonParams();
        forgetParams.topMargin = dp(18);
        page.addView(forgetButton, forgetParams);
        forgetButton.setOnClickListener(v -> forgetCredentials());

        TextView note = text("Read-only access. This app only reads the router's device list; it cannot block devices or change Wi-Fi settings.", 12, MUTED, false);
        note.setPadding(dp(4), dp(16), dp(4), 0);
        page.addView(note, matchWrap());

        setContentView(scroll);
    }

    private void loadSavedCredentials() {
        SecureCredentials.Saved saved = SecureCredentials.load(this);
        hostField.setText(saved.host == null ? "192.168.1.1" : saved.host);
        usernameField.setText(saved.username == null ? "admin" : saved.username);
        if (saved.password != null) {
            passwordField.setText(saved.password);
            rememberCheck.setChecked(true);
            statusText.setText("Saved credentials found. Tap Connect and load devices.");
        }
    }

    private void connect(boolean refresh) {
        final String host = hostField.getText().toString().trim();
        final String username = usernameField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            setStatus("Enter the router IP, admin username and password.", RED);
            return;
        }
        if (rememberCheck.isChecked()) {
            try {
                SecureCredentials.save(this, host, username, password);
            } catch (Exception e) {
                setStatus("Could not securely save the password: " + safeMessage(e), RED);
            }
        } else {
            SecureCredentials.forget(this);
        }

        activeHost = host;
        activeUsername = username;
        activePassword = password;
        setBusy(true, refresh ? "Refreshing the router's device list…" : "Connecting to the router…");

        executor.execute(() -> {
            try {
                RouterClient client = new RouterClient(host, username, password);
                List<RouterDevice> devices = client.connectAndReadDevices();
                mainHandler.post(() -> {
                    currentDevices.clear();
                    currentDevices.addAll(devices);
                    summaryText.setText(makeSummary(devices));
                    setStatus("Connected to " + host + " · device list loaded successfully.", GREEN);
                    setBusy(false, null);
                    renderDevices();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, null);
                    setStatus(safeMessage(e), RED);
                    if (currentDevices.isEmpty()) {
                        summaryText.setText("No device list loaded");
                        renderDevices();
                    }
                });
            }
        });
    }

    private String makeSummary(List<RouterDevice> devices) {
        int online = 0;
        for (RouterDevice device : devices) if (device.active) online++;
        return devices.size() + " router records  ·  " + online + " active  ·  " + (devices.size() - online) + " inactive";
    }

    private void renderDevices() {
        if (devicesContainer == null) return;
        devicesContainer.removeAllViews();
        String query = searchField == null ? "" : searchField.getText().toString();
        int count = 0;
        for (RouterDevice device : currentDevices) {
            if (!device.matches(query)) continue;
            count++;
            devicesContainer.addView(deviceCard(device), matchWrapWithBottom(dp(10)));
        }
        if (count == 0) {
            TextView empty = text(currentDevices.isEmpty()
                    ? "Connect to the router to retrieve its device names."
                    : "No devices match your search.", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(28), dp(18), dp(28));
            devicesContainer.addView(empty, matchWrap());
        }
    }

    private View deviceCard(RouterDevice device) {
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

        TextView state = text(device.active ? "ACTIVE" : "INACTIVE", 10,
                device.active ? GREEN : MUTED, true);
        state.setPadding(dp(9), dp(5), dp(9), dp(5));
        state.setBackground(pill(device.active ? Color.rgb(28, 66, 57) : Color.rgb(48, 57, 65)));
        top.addView(state, new LinearLayout.LayoutParams(-2, -2));

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

    private void forgetCredentials() {
        SecureCredentials.forget(this);
        passwordField.setText("");
        rememberCheck.setChecked(false);
        activePassword = "";
        setStatus("Saved router credentials removed from this app.", MUTED);
        Toast.makeText(this, "Saved credentials forgotten", Toast.LENGTH_SHORT).show();
    }

    private void setBusy(boolean busy, String message) {
        connectButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy && !currentDevices.isEmpty());
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        connectButton.setText(busy ? "Please wait…" : "Connect and load devices");
        if (message != null) setStatus(message, MUTED);
    }

    private void setStatus(String message, int color) {
        statusText.setText(message);
        statusText.setTextColor(color);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "Could not communicate with the router. Check Wi-Fi and credentials.";
        if (message.contains("CLEARTEXT communication") || message.contains("cleartext")) {
            return "Android blocked the local HTTP connection. Confirm this build's network security setting.";
        }
        if (message.contains("Failed to connect") || message.contains("Unable to resolve host")) {
            return "Cannot reach the router. Connect to your Airtel Wi-Fi and check the router IP.";
        }
        return message;
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
