package com.nepalanurag.lgremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The whole phone UI: discovery and pairing, the button remote, trackpad and settings. */
public final class MainActivity extends Activity implements RemoteService.Listener {
    private static final int REQ_NOTIFICATIONS = 41;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> keyControls = new ArrayList<>();
    private final List<View> commandControls = new ArrayList<>();
    private final List<View> textControls = new ArrayList<>();

    private RemoteService service;
    private boolean bound;
    private boolean setupVisible;
    private boolean choosingTv;
    private boolean manuallyDisconnected;
    private boolean discoveryStarted;
    private boolean buttonsMode = true;
    private boolean haptics;

    private FrameLayout root;
    private View setupView;
    private View remoteView;
    private TextView setupStatus;
    private LinearLayout discoveryList;
    private EditText manualIp;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private TextView powerBadge;
    private View statusDot;
    private LinearLayout banner;
    private TextView bannerText;
    private TextView bannerAction;
    private Runnable bannerActionRunnable;
    private TextView buttonsTab;
    private TextView trackpadTab;
    private LinearLayout buttonsPanel;
    private LinearLayout trackpadPanel;
    private DpadView dpad;
    private TrackpadView trackpad;
    private TextView volumeLabel;
    private TextView muteLabel;
    private EditText tvTextField;
    private TextView tvKeyboardHint;
    private boolean textAutoOpened;
    private boolean boxTextAutoOpened;
    private String foregroundApp = "";

    private TvDiscovery discovery;
    private Dialog settingsDialog;
    private Dialog appsDialog;
    private Dialog inputsDialog;
    private LinearLayout appsList;
    private TextView appsStatus;
    private LinearLayout inputsList;
    private TextView inputsStatus;
    private AndroidTvRemote.Pairing boxPairing;
    private AlertDialog boxProgress;
    private boolean boxHomeBusy;
    private boolean boxSettingsBusy;
    private boolean boxTextBusy;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RemoteService.LocalBinder) binder).service();
            bound = true;
            service.addListener(MainActivity.this);
            if (service.getIp() != null && !setupVisible) {
                showRemote();
            }
            renderConnection();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            renderConnection();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        haptics = SecureStore.getHaptics(this);
        getWindow().setStatusBarColor(getColor(R.color.bg));
        getWindow().setNavigationBarColor(getColor(R.color.bg));
        getWindow().getDecorView().setSystemUiVisibility(0);

        discovery = new TvDiscovery(this);
        root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.bg_app);
        setupView = buildSetup();
        remoteView = buildRemote();
        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(remoteView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        String savedIp = SecureStore.getSavedIp(this);
        if (savedIp == null) {
            showSetup(false);
        } else {
            headerTitle.setText(savedName());
            showRemote();
        }
        requestNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String saved = SecureStore.getSavedIp(this);
        if (saved != null && !setupVisible && !manuallyDisconnected) {
            RemoteService.start(this, null, null);
        }
        if (!bound) {
            bindService(new Intent(this, RemoteService.class), connection, Context.BIND_AUTO_CREATE);
        }
        if (setupVisible && !discoveryStarted) {
            startDiscovery();
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            if (service != null) {
                service.removeListener(this);
            }
            unbindService(connection);
            bound = false;
            service = null;
        }
        if (discovery != null) {
            discovery.stop();
            discoveryStarted = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        dismiss(settingsDialog);
        dismiss(appsDialog);
        dismiss(inputsDialog);
        dismissBoxProgress();
        cancelBoxPairing();
        if (discovery != null) {
            discovery.stop();
        }
        super.onDestroy();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    // ------------------------------------------------------------------ setup screen

    private View buildSetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(22), dp(12), dp(22), dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = Ui.row(this);
        TextView brand = Ui.text(this, "LG REMOTE", 10, getColor(R.color.accent),
                Typeface.BOLD);
        brand.setLetterSpacing(0.18f);
        TextView suffix = Ui.text(this, "  REMOTE", 10, getColor(R.color.muted), Typeface.BOLD);
        suffix.setLetterSpacing(0.18f);
        top.addView(brand);
        top.addView(suffix);
        View topSpacer = new View(this);
        top.addView(topSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        ImageButton setupGear = iconButton(R.drawable.ic_gear, this::showSettings, getString(R.string.settings));
        top.addView(setupGear);
        content.addView(top);

        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(Ui.round(this, getColor(R.color.accent_dim), 22,
                getColor(R.color.stroke), 1));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_foreground);
        logo.addView(mark, new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER));
        LinearLayout.LayoutParams logoParams = Ui.dpParams(this, 70, 70);
        logoParams.topMargin = dp(26);
        content.addView(logo, logoParams);

        TextView title = Ui.text(this, getString(R.string.setup_title), 31,
                getColor(R.color.text), Typeface.NORMAL);
        title.setLineSpacing(0f, 1.02f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(20);
        content.addView(title, titleParams);

        TextView sub = Ui.text(this, getString(R.string.setup_sub), 14,
                getColor(R.color.muted), Typeface.NORMAL);
        sub.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(12);
        content.addView(sub, subParams);

        LinearLayout card = Ui.column(this);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 22,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(24);
        content.addView(card, cardParams);

        LinearLayout discoveryHeader = Ui.row(this);
        discoveryHeader.addView(Ui.label(this, getString(R.string.on_this_wifi)));
        View headerSpacer = new View(this);
        discoveryHeader.addView(headerSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView searchAgain = Ui.text(this, getString(R.string.search_again), 11,
                getColor(R.color.accent), Typeface.BOLD);
        searchAgain.setPadding(dp(6), dp(4), dp(2), dp(4));
        searchAgain.setOnClickListener(v -> {
            clickHaptic(v);
            discovery.stop();
            discoveryStarted = false;
            startDiscovery();
        });
        discoveryHeader.addView(searchAgain);
        card.addView(discoveryHeader);

        setupStatus = Ui.text(this, getString(R.string.searching), 13,
                getColor(R.color.muted), Typeface.NORMAL);
        setupStatus.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        card.addView(setupStatus, statusParams);

        discoveryList = Ui.column(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(4);
        card.addView(discoveryList, listParams);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.stroke));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(16);
        dividerParams.bottomMargin = dp(16);
        card.addView(divider, dividerParams);

        card.addView(Ui.label(this, getString(R.string.or_connect_by_ip)));

        LinearLayout manualRow = Ui.row(this);
        LinearLayout.LayoutParams manualRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manualRowParams.topMargin = dp(10);
        card.addView(manualRow, manualRowParams);

        manualIp = new EditText(this);
        manualIp.setHint(R.string.tv_ip_hint);
        manualIp.setHintTextColor(getColor(R.color.muted));
        manualIp.setTextColor(getColor(R.color.text));
        manualIp.setTextSize(14);
        manualIp.setSingleLine(true);
        manualIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualIp.setImeOptions(EditorInfo.IME_ACTION_GO);
        manualIp.setPadding(dp(14), 0, dp(14), 0);
        manualIp.setBackground(Ui.round(this, getColor(R.color.surface_2), 14,
                getColor(R.color.stroke), 1));
        manualIp.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectFromField();
                return true;
            }
            return false;
        });
        manualRow.addView(manualIp, new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView connect = pill(getString(R.string.connect), true);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
        connectParams.leftMargin = dp(8);
        manualRow.addView(connect, connectParams);
        connect.setOnClickListener(v -> {
            clickHaptic(v);
            connectFromField();
        });

        LinearLayout help = Ui.column(this);
        help.setPadding(dp(16), dp(15), dp(16), dp(15));
        help.setBackground(Ui.round(this, getColor(R.color.surface), 18,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpParams.topMargin = dp(18);
        content.addView(help, helpParams);
        TextView helpTitle = Ui.text(this, getString(R.string.setup_help_title), 13,
                getColor(R.color.accent), Typeface.BOLD);
        help.addView(helpTitle);
        TextView helpBody = Ui.text(this, getString(R.string.setup_help), 12,
                getColor(R.color.muted), Typeface.NORMAL);
        helpBody.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams helpBodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpBodyParams.topMargin = dp(7);
        help.addView(helpBody, helpBodyParams);

        TextView footer = Ui.text(this, getString(R.string.about, BuildConfig.VERSION_NAME), 10,
                getColor(R.color.muted), Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(22);
        content.addView(footer, footerParams);

        return scroll;
    }

    private void connectFromField() {
        String ip = manualIp.getText().toString().trim();
        if (!Patterns.IP_ADDRESS.matcher(ip).matches()) {
            manualIp.setError(getString(R.string.invalid_ip));
            return;
        }
        hideKeyboard();
        connectTo(ip, "");
    }

    private void startDiscovery() {
        if (discovery == null || discovery.isRunning()) {
            return;
        }
        discoveryStarted = true;
        setupStatus.setText(R.string.searching);
        discoveryList.removeAllViews();
        discovery.start(tvs -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            renderDiscovery(tvs);
        });
    }

    private void renderDiscovery(List<TvDiscovery.Tv> tvs) {
        discoveryList.removeAllViews();
        if (tvs.isEmpty()) {
            setupStatus.setText(R.string.no_tvs_found);
            return;
        }
        setupStatus.setText(getString(R.string.tv_found, tvs.size()));
        for (TvDiscovery.Tv tv : tvs) {
            discoveryList.addView(tvRow(tv));
        }
    }

    private View tvRow(TvDiscovery.Tv tv) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 16,
                getColor(R.color.stroke), 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(tv.name + ", " + tv.ip);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.round(this, getColor(R.color.accent_dim), 12));
        ImageView icon = Ui.icon(this, R.drawable.ic_notification, getColor(R.color.accent), 20);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        row.addView(badge, Ui.dpParams(this, 40, 40));

        LinearLayout text = Ui.column(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(11);
        row.addView(text, textParams);
        text.addView(Ui.text(this, tv.name, 14, getColor(R.color.text), Typeface.BOLD));
        String sub = tv.ip + (tv.model == null || tv.model.isEmpty() ? "" : "  ·  " + tv.model);
        TextView subView = Ui.text(this, sub, 11, getColor(R.color.muted), Typeface.NORMAL);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(3);
        text.addView(subView, subParams);

        row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 18));
        row.setOnClickListener(v -> {
            clickHaptic(v);
            connectTo(tv.ip, tv.name);
        });
        return row;
    }

    private void connectTo(String ip, String name) {
        hideKeyboard();
        manuallyDisconnected = false;
        RemoteService.start(this, ip, name);
        headerTitle.setText(name == null || name.isEmpty() ? getString(R.string.device_unknown) : name);
        headerSubtitle.setText(getString(R.string.connecting_to, ip));
        showRemote();
        setupStatus.setText(R.string.searching);
    }

    // ------------------------------------------------------------------ remote screen

    private View buildRemote() {
        boolean landscape = isLandscape();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        content.setPadding(landscape ? dp(14) : dp(20), landscape ? dp(4) : dp(10),
                landscape ? dp(14) : dp(20), landscape ? dp(6) : dp(30));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = Ui.row(this);
        statusDot = new View(this);
        statusDot.setBackground(Ui.round(this, getColor(R.color.amber), 99));
        LinearLayout.LayoutParams dotParams = Ui.dpParams(this, 9, 9);
        dotParams.rightMargin = dp(10);
        header.addView(statusDot, dotParams);

        LinearLayout headerText = Ui.column(this);
        header.addView(headerText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerTitle = Ui.text(this, savedName(), landscape ? 16 : 18,
                getColor(R.color.text), Typeface.BOLD);
        if (BuildConfig.DEBUG) {
            headerTitle.setOnLongClickListener(v -> {
                if (service != null) {
                    service.requestScreenshot((uri, error) ->
                            Log.d("LgRemote", "TV screenshot: " + uri + " error=" + error));
                }
                return true;
            });
        }
        headerText.addView(headerTitle);
        headerSubtitle = Ui.text(this, getString(R.string.connecting), landscape ? 11 : 12,
                getColor(R.color.muted), Typeface.NORMAL);
        LinearLayout.LayoutParams headerSubParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerSubParams.topMargin = dp(3);
        headerText.addView(headerSubtitle, headerSubParams);

        powerBadge = Ui.text(this, "…", 9, getColor(R.color.muted), Typeface.BOLD);
        powerBadge.setAllCaps(true);
        powerBadge.setLetterSpacing(0.08f);
        powerBadge.setGravity(Gravity.CENTER);
        powerBadge.setPadding(dp(8), dp(5), dp(8), dp(5));
        powerBadge.setContentDescription(getString(R.string.tv_power_status));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.rightMargin = dp(7);
        header.addView(powerBadge, badgeParams);

        ImageButton gear = iconButton(R.drawable.ic_gear, this::showSettings, getString(R.string.settings));
        header.addView(gear);
        if (landscape) {
            ImageButton tvSettings = iconButton(R.drawable.ic_tv_settings,
                    this::deviceSettingsPressed, getString(R.string.device_settings));
            LinearLayout.LayoutParams tvSettingsParams =
                    new LinearLayout.LayoutParams(dp(42), dp(42));
            tvSettingsParams.rightMargin = dp(8);
            header.addView(tvSettings, tvSettingsParams);
        }
        content.addView(header);

        banner = Ui.row(this);
        banner.setPadding(dp(13), dp(11), dp(8), dp(11));
        banner.setBackground(Ui.round(this, getColor(R.color.surface_2), 14,
                getColor(R.color.accent_dim), 1));
        bannerText = Ui.text(this, "", 12, getColor(R.color.text), Typeface.NORMAL);
        bannerText.setLineSpacing(0f, 1.25f);
        banner.addView(bannerText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bannerAction = Ui.text(this, "", 11, getColor(R.color.accent), Typeface.BOLD);
        bannerAction.setPadding(dp(10), dp(6), dp(6), dp(6));
        banner.addView(bannerAction);
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerParams.topMargin = dp(12);
        content.addView(banner, bannerParams);

        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(Ui.round(this, getColor(R.color.surface), 17,
                getColor(R.color.stroke), 1));
        int tabBarHeight = landscape ? dp(36) : dp(44);
        int tabHeight = landscape ? dp(28) : dp(36);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, tabBarHeight);
        tabsParams.topMargin = landscape ? dp(7) : dp(14);
        content.addView(tabs, tabsParams);
        buttonsTab = tabLabel(getString(R.string.buttons));
        trackpadTab = tabLabel(getString(R.string.trackpad));
        tabs.addView(buttonsTab, new LinearLayout.LayoutParams(0, tabHeight, 1f));
        tabs.addView(trackpadTab, new LinearLayout.LayoutParams(0, tabHeight, 1f));
        buttonsTab.setOnClickListener(v -> {
            clickHaptic(v);
            showMode(true);
        });
        trackpadTab.setOnClickListener(v -> {
            clickHaptic(v);
            showMode(false);
        });

        FrameLayout modes = new FrameLayout(this);
        LinearLayout.LayoutParams modesParams = new LinearLayout.LayoutParams(
                landscape ? landscapeBoardWidth() : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        modesParams.topMargin = landscape ? dp(8) : dp(14);
        if (landscape) {
            modesParams.gravity = Gravity.CENTER_HORIZONTAL;
        }
        content.addView(modes, modesParams);

        buttonsPanel = buildButtonsPanel();
        trackpadPanel = buildTrackpadPanel();
        modes.addView(buttonsPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modes.addView(trackpadPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        trackpadPanel.setVisibility(View.GONE);
        styleTabs();

        if (!landscape) {
            Space pushDown = new Space(this);
            content.addView(pushDown, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            content.addView(buildTvKeyboardBar());
        }

        return scroll;
    }

    /** Persistent text entry and device-settings actions, anchored to the bottom of the phone. */
    private View buildTvKeyboardBar() {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(Ui.round(this, getColor(R.color.surface), 18,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(14);
        card.setLayoutParams(cardParams);

        LinearLayout header = Ui.row(this);
        TextView label = Ui.label(this, getString(R.string.tv_keyboard_section));
        header.addView(label);
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView tvSettings = Ui.text(this, getString(R.string.device_settings), 10.5f,
                getColor(R.color.accent), Typeface.BOLD);
        tvSettings.setAllCaps(true);
        tvSettings.setLetterSpacing(0.08f);
        tvSettings.setPadding(dp(10), dp(6), dp(2), dp(6));
        tvSettings.setClickable(true);
        tvSettings.setFocusable(true);
        tvSettings.setContentDescription(getString(R.string.device_settings));
        tvSettings.setOnClickListener(v -> {
            clickHaptic(v);
            deviceSettingsPressed();
        });
        header.addView(tvSettings);
        card.addView(header);

        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        card.addView(row, rowParams);

        tvTextField = new EditText(this);
        tvTextField.setHint(R.string.tv_keyboard_hint);
        tvTextField.setHintTextColor(getColor(R.color.muted));
        tvTextField.setTextColor(getColor(R.color.text));
        tvTextField.setTextSize(14);
        tvTextField.setSingleLine(true);
        tvTextField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        tvTextField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        tvTextField.setPadding(dp(13), 0, dp(13), 0);
        tvTextField.setBackground(Ui.round(this, getColor(R.color.surface_2), 13,
                getColor(R.color.stroke), 1));
        tvTextField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTvText();
                return true;
            }
            return false;
        });
        row.addView(tvTextField, new LinearLayout.LayoutParams(0, dp(48), 1f));
        textControls.add(tvTextField);

        ImageButton delete = iconAction(R.drawable.ic_backspace,
                getString(R.string.tv_keyboard_delete), false, this::deleteTvText);
        ImageButton enter = iconAction(R.drawable.ic_enter,
                getString(R.string.tv_keyboard_enter), false, this::sendTvTextAndEnter);
        ImageButton send = iconAction(R.drawable.ic_send,
                getString(R.string.tv_keyboard_send), true, this::sendTvText);
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(dp(46), dp(48));
        firstParams.leftMargin = dp(7);
        row.addView(delete, firstParams);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(dp(46), dp(48));
        secondParams.leftMargin = dp(7);
        row.addView(enter, secondParams);
        LinearLayout.LayoutParams thirdParams = new LinearLayout.LayoutParams(dp(46), dp(48));
        thirdParams.leftMargin = dp(7);
        row.addView(send, thirdParams);

        tvKeyboardHint = Ui.text(this, getString(R.string.tv_keyboard_help), 10.5f,
                getColor(R.color.muted), Typeface.NORMAL);
        tvKeyboardHint.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        card.addView(tvKeyboardHint, hintParams);
        return card;
    }

    private ImageButton iconAction(int iconRes, String description, boolean primary,
                                   Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(getColor(primary ? R.color.on_accent : R.color.text));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(primary
                ? Ui.pressable(this, getColor(R.color.accent), 13)
                : Ui.pressable(this, getColor(R.color.surface_2), 13,
                getColor(R.color.stroke), 1));
        button.setContentDescription(description);
        button.setOnClickListener(v -> {
            clickHaptic(v);
            action.run();
        });
        textControls.add(button);
        return button;
    }

    private TextView tabLabel(String text) {
        TextView view = Ui.text(this, text, 12, getColor(R.color.muted), Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout buildButtonsPanel() {
        if (isLandscape()) {
            return buildLandscapeButtonsPanel();
        }
        LinearLayout panel = Ui.column(this);

        LinearLayout quick = Ui.row(this);
        View power = actionTile(R.drawable.ic_power, getString(R.string.power),
                getColor(R.color.danger), this::powerPressed);
        View inputs = actionTile(R.drawable.ic_input, getString(R.string.inputs),
                getColor(R.color.accent), this::showInputs);
        View apps = actionTile(R.drawable.ic_apps, getString(R.string.apps),
                getColor(R.color.accent), this::showApps);
        quick.addView(power, weighted(1f, 68, 0, 8));
        quick.addView(inputs, weighted(1f, 68, 0, 8));
        quick.addView(apps, weighted(1f, 68, 0, 0));
        panel.addView(quick);
        commandControls.add(inputs);
        commandControls.add(apps);

        LinearLayout padRow = Ui.row(this);
        LinearLayout.LayoutParams padRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        padRowParams.topMargin = dp(14);
        panel.addView(padRow, padRowParams);

        dpad = new DpadView(this);
        dpad.setHaptics(haptics);
        dpad.setListener(name -> sendButton(name));
        dpad.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        padRow.addView(dpad);
        keyControls.add(dpad);

        LinearLayout volume = Ui.column(this);
        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(dp(76),
                ViewGroup.LayoutParams.MATCH_PARENT);
        volumeParams.leftMargin = dp(10);
        padRow.addView(volume, volumeParams);

        volumeLabel = Ui.text(this, "VOL", 9, getColor(R.color.muted), Typeface.BOLD);
        volumeLabel.setLetterSpacing(0.1f);
        volumeLabel.setGravity(Gravity.CENTER);
        volume.addView(volumeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        LinearLayout volUp = iconLabelButton(R.drawable.ic_vol_up, getString(R.string.vol_up));
        LinearLayout volDown = iconLabelButton(R.drawable.ic_vol_down, getString(R.string.vol_down));
        LinearLayout mute = iconLabelButton(R.drawable.ic_mute, getString(R.string.mute));
        volume.addView(volUp, volumeButtonParams(6));
        volume.addView(volDown, volumeButtonParams(6));
        volume.addView(mute, volumeButtonParams(0));
        keyControls.add(volUp);
        keyControls.add(volDown);
        keyControls.add(mute);
        muteLabel = (TextView) mute.getChildAt(1);
        attachRepeat(volUp, () -> sendButton("VOLUMEUP"));
        attachRepeat(volDown, () -> sendButton("VOLUMEDOWN"));
        mute.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("MUTE");
        });

        LinearLayout nav = Ui.row(this);
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navParams.topMargin = dp(12);
        panel.addView(nav, navParams);
        View back = wideButton(R.drawable.ic_back, getString(R.string.back), 13);
        View home = wideButton(R.drawable.ic_home, getString(R.string.home), 13);
        nav.addView(back, weighted(1f, 54, 0, 8));
        nav.addView(home, weighted(1f, 54, 0, 0));
        back.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("BACK");
        });
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        keyControls.add(back);
        keyControls.add(home);

        LinearLayout transport = Ui.row(this);
        LinearLayout.LayoutParams transportParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        transportParams.topMargin = dp(8);
        panel.addView(transport, transportParams);
        View rewind = wideButton(R.drawable.ic_rewind, getString(R.string.rewind), 11);
        View play = wideButton(R.drawable.ic_play_pause, getString(R.string.play_pause), 11);
        View forward = wideButton(R.drawable.ic_forward, getString(R.string.forward), 11);
        transport.addView(rewind, weighted(1f, 54, 0, 8));
        transport.addView(play, weighted(1f, 54, 0, 8));
        transport.addView(forward, weighted(1f, 54, 0, 0));
        rewind.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("REWIND");
        });
        play.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("PLAY");
        });
        forward.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("FASTFORWARD");
        });
        keyControls.add(rewind);
        keyControls.add(play);
        keyControls.add(forward);

        return panel;
    }

    /**
     * Landscape gets a compact control board that fits on one screen: D-pad and volume on the
     * left, quick actions and navigation stacked on the right. The portrait tree is unchanged.
     */
    private LinearLayout buildLandscapeButtonsPanel() {
        int size = landscapePadSize();
        LinearLayout board = Ui.row(this);
        board.setGravity(Gravity.CENTER_VERTICAL);
        board.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, size));

        dpad = new DpadView(this);
        dpad.setHaptics(haptics);
        dpad.setListener(name -> sendButton(name));
        board.addView(dpad, new LinearLayout.LayoutParams(size, size));
        keyControls.add(dpad);

        LinearLayout volume = Ui.column(this);
        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(dp(72), size);
        volumeParams.leftMargin = dp(10);
        board.addView(volume, volumeParams);

        volumeLabel = Ui.text(this, "VOL", 9, getColor(R.color.muted), Typeface.BOLD);
        volumeLabel.setLetterSpacing(0.1f);
        volumeLabel.setGravity(Gravity.CENTER);
        volume.addView(volumeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        LinearLayout volUp = iconLabelButton(R.drawable.ic_vol_up, getString(R.string.vol_up));
        LinearLayout volDown = iconLabelButton(R.drawable.ic_vol_down, getString(R.string.vol_down));
        LinearLayout mute = iconLabelButton(R.drawable.ic_mute, getString(R.string.mute));
        volume.addView(volUp, volumeButtonParams(6));
        volume.addView(volDown, volumeButtonParams(6));
        volume.addView(mute, volumeButtonParams(0));
        keyControls.add(volUp);
        keyControls.add(volDown);
        keyControls.add(mute);
        muteLabel = (TextView) mute.getChildAt(1);
        attachRepeat(volUp, () -> sendButton("VOLUMEUP"));
        attachRepeat(volDown, () -> sendButton("VOLUMEDOWN"));
        mute.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("MUTE");
        });

        LinearLayout controls = Ui.column(this);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        controlsParams.leftMargin = dp(10);
        board.addView(controls, controlsParams);

        LinearLayout quick = Ui.row(this);
        View power = actionTile(R.drawable.ic_power, getString(R.string.power),
                getColor(R.color.danger), this::powerPressed);
        View inputs = actionTile(R.drawable.ic_input, getString(R.string.inputs),
                getColor(R.color.accent), this::showInputs);
        View apps = actionTile(R.drawable.ic_apps, getString(R.string.apps),
                getColor(R.color.accent), this::showApps);
        quick.addView(power, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(inputs, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(apps, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        controls.addView(quick, landscapeRowParams(0));
        commandControls.add(inputs);
        commandControls.add(apps);

        LinearLayout nav = Ui.row(this);
        View back = wideButton(R.drawable.ic_back, getString(R.string.back), 13);
        View home = wideButton(R.drawable.ic_home, getString(R.string.home), 13);
        nav.addView(back, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        nav.addView(home, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        back.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("BACK");
        });
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        controls.addView(nav, landscapeRowParams(8));
        keyControls.add(back);
        keyControls.add(home);

        LinearLayout transport = Ui.row(this);
        View rewind = wideButton(R.drawable.ic_rewind, getString(R.string.rewind), 11);
        View play = wideButton(R.drawable.ic_play_pause, getString(R.string.play_pause), 11);
        View forward = wideButton(R.drawable.ic_forward, getString(R.string.forward), 11);
        transport.addView(rewind, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        transport.addView(play, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        transport.addView(forward, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        rewind.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("REWIND");
        });
        play.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("PLAY");
        });
        forward.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("FASTFORWARD");
        });
        controls.addView(transport, landscapeRowParams(8));
        keyControls.add(rewind);
        keyControls.add(play);
        keyControls.add(forward);

        return board;
    }

    private LinearLayout.LayoutParams landscapeRowParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout buildLandscapeTrackpadPanel() {
        int size = landscapePadSize();
        LinearLayout board = Ui.row(this);
        board.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, size));

        trackpad = new TrackpadView(this);
        trackpad.setPointerSpeed(SecureStore.getPointerSpeed(this));
        trackpad.setHaptics(haptics);
        trackpad.setListener(new TrackpadView.Listener() {
            @Override
            public void onMove(int dx, int dy, boolean buttonDown) {
                if (service != null) {
                    service.sendMove(dx, dy, buttonDown);
                }
            }

            @Override
            public void onClick() {
                if (service != null) {
                    service.sendClick();
                }
            }

            @Override
            public void onScroll(int dx, int dy) {
                if (service != null) {
                    service.sendScroll(dx, dy);
                }
            }
        });
        board.addView(trackpad, new LinearLayout.LayoutParams(0, size, 1f));
        keyControls.add(trackpad);

        LinearLayout controls = Ui.column(this);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(dp(250), size);
        controlsParams.leftMargin = dp(10);
        board.addView(controls, controlsParams);

        LinearLayout quick = Ui.row(this);
        View power = actionTile(R.drawable.ic_power, getString(R.string.power),
                getColor(R.color.danger), this::powerPressed);
        View inputs = actionTile(R.drawable.ic_input, getString(R.string.inputs),
                getColor(R.color.accent), this::showInputs);
        View apps = actionTile(R.drawable.ic_apps, getString(R.string.apps),
                getColor(R.color.accent), this::showApps);
        quick.addView(power, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(inputs, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(apps, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        controls.addView(quick, landscapeRowParams(0));
        commandControls.add(inputs);
        commandControls.add(apps);

        LinearLayout nav = Ui.row(this);
        View back = wideButton(R.drawable.ic_back, getString(R.string.back), 12);
        View click = wideButton(R.drawable.ic_play_pause, getString(R.string.click), 12);
        View home = wideButton(R.drawable.ic_home, getString(R.string.home), 12);
        nav.addView(back, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
        nav.addView(click, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
        nav.addView(home, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        back.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("BACK");
        });
        click.setOnClickListener(v -> {
            clickHaptic(v);
            if (service != null) {
                service.sendClick();
            }
        });
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        controls.addView(nav, landscapeRowParams(8));
        keyControls.add(back);
        keyControls.add(click);
        keyControls.add(home);

        LinearLayout volume = Ui.row(this);
        LinearLayout volUp = iconLabelButton(R.drawable.ic_vol_up, getString(R.string.vol_up));
        LinearLayout volDown = iconLabelButton(R.drawable.ic_vol_down, getString(R.string.vol_down));
        LinearLayout mute = iconLabelButton(R.drawable.ic_mute, getString(R.string.mute));
        volume.addView(volUp, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
        volume.addView(volDown, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
        volume.addView(mute, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        attachRepeat(volUp, () -> sendButton("VOLUMEUP"));
        attachRepeat(volDown, () -> sendButton("VOLUMEDOWN"));
        mute.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("MUTE");
        });
        controls.addView(volume, landscapeRowParams(8));
        keyControls.add(volUp);
        keyControls.add(volDown);
        keyControls.add(mute);

        return board;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private int landscapeBoardWidth() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(700), Math.max(dp(520), screenWidth - dp(28)));
    }

    private int landscapePadSize() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int preferred = Math.min(dp(220), Math.max(dp(170), (int) (screenHeight * 0.55f)));
        // Keep enough room for the header, tabs spaced at their landscape heights and margins.
        int available = screenHeight - dp(160);
        return Math.max(dp(160), Math.min(preferred, available));
    }

    private LinearLayout buildTrackpadPanel() {
        if (isLandscape()) {
            return buildLandscapeTrackpadPanel();
        }
        LinearLayout panel = Ui.column(this);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int height = Math.max(dp(270), Math.min(dp(410), (int) (screenHeight * 0.44f)));
        trackpad = new TrackpadView(this);
        trackpad.setPointerSpeed(SecureStore.getPointerSpeed(this));
        trackpad.setHaptics(haptics);
        trackpad.setListener(new TrackpadView.Listener() {
            @Override
            public void onMove(int dx, int dy, boolean buttonDown) {
                if (service != null) {
                    service.sendMove(dx, dy, buttonDown);
                }
            }

            @Override
            public void onClick() {
                if (service != null) {
                    service.sendClick();
                }
            }

            @Override
            public void onScroll(int dx, int dy) {
                if (service != null) {
                    service.sendScroll(dx, dy);
                }
            }
        });
        panel.addView(trackpad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        keyControls.add(trackpad);

        LinearLayout actions = Ui.row(this);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(12);
        panel.addView(actions, actionsParams);
        View back = wideButton(R.drawable.ic_back, getString(R.string.back), 13);
        View click = wideButton(R.drawable.ic_play_pause, getString(R.string.click), 13);
        View home = wideButton(R.drawable.ic_home, getString(R.string.home), 13);
        actions.addView(back, weighted(1f, 54, 0, 8));
        actions.addView(click, weighted(1f, 54, 0, 8));
        actions.addView(home, weighted(1f, 54, 0, 0));
        back.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("BACK");
        });
        click.setOnClickListener(v -> {
            clickHaptic(v);
            if (service != null) {
                service.sendClick();
            }
        });
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        keyControls.add(back);
        keyControls.add(click);
        keyControls.add(home);
        return panel;
    }

    private void showMode(boolean buttons) {
        buttonsMode = buttons;
        buttonsPanel.setVisibility(buttons ? View.VISIBLE : View.GONE);
        trackpadPanel.setVisibility(buttons ? View.GONE : View.VISIBLE);
        styleTabs();
        if (!buttons) {
            trackpad.setPointerSpeed(SecureStore.getPointerSpeed(this));
            trackpad.setHaptics(SecureStore.getHaptics(this));
        }
    }

    private void styleTabs() {
        if (buttonsTab == null) {
            return;
        }
        buttonsTab.setBackground(buttonsMode ? Ui.round(this, getColor(R.color.accent_dim), 13) : null);
        buttonsTab.setTextColor(buttonsMode ? getColor(R.color.accent) : getColor(R.color.muted));
        trackpadTab.setBackground(buttonsMode ? null : Ui.round(this, getColor(R.color.accent_dim), 13));
        trackpadTab.setTextColor(buttonsMode ? getColor(R.color.muted) : getColor(R.color.accent));
    }

    private LinearLayout.LayoutParams weighted(float weight, int heightDp, int leftDp, int rightDp) {
        int height = heightDp == ViewGroup.LayoutParams.MATCH_PARENT
                ? ViewGroup.LayoutParams.MATCH_PARENT : dp(heightDp);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, weight);
        params.leftMargin = dp(leftDp);
        params.rightMargin = dp(rightDp);
        return params;
    }

    private LinearLayout.LayoutParams volumeButtonParams(int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        params.bottomMargin = dp(bottomDp);
        return params;
    }

    private View actionTile(int iconRes, String label, int tint, Runnable action) {
        LinearLayout tile = Ui.column(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Ui.pressable(this, getColor(R.color.surface), 18,
                getColor(R.color.stroke), 1));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(label);
        tile.addView(Ui.icon(this, iconRes, tint, 22));
        TextView text = Ui.text(this, label, 10.5f, getColor(R.color.text), Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(6);
        tile.addView(text, textParams);
        tile.setOnClickListener(v -> {
            clickHaptic(v);
            action.run();
        });
        return tile;
    }

    private LinearLayout iconLabelButton(int iconRes, String label) {
        LinearLayout button = Ui.column(this);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 14,
                getColor(R.color.stroke), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        button.addView(Ui.icon(this, iconRes, getColor(R.color.text), 20));
        TextView text = Ui.text(this, label, 9.5f, getColor(R.color.muted), Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setAllCaps(true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(4);
        button.addView(text, textParams);
        return button;
    }

    private View wideButton(int iconRes, String label, float textSize) {
        LinearLayout button = Ui.row(this);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.pressable(this, getColor(R.color.surface), 16,
                getColor(R.color.stroke), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        button.addView(Ui.icon(this, iconRes, getColor(R.color.text), 18));
        TextView text = Ui.text(this, label, textSize, getColor(R.color.text), Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.leftMargin = dp(7);
        button.addView(text, textParams);
        return button;
    }

    // ------------------------------------------------------------------ state

    private String savedName() {
        String name = SecureStore.getSavedName(this);
        return name == null || name.isEmpty() ? getString(R.string.device_unknown) : name;
    }

    private void showSetup(boolean choose) {
        setupVisible = true;
        choosingTv = choose;
        setupView.setVisibility(View.VISIBLE);
        remoteView.setVisibility(View.GONE);
        if (!discoveryStarted) {
            startDiscovery();
        }
    }

    private void showRemote() {
        setupVisible = false;
        remoteView.setVisibility(View.VISIBLE);
        setupView.setVisibility(View.GONE);
        renderConnection();
    }

    private void renderConnection() {
        if (headerTitle == null) {
            return;
        }
        if (service == null) {
            String ip = SecureStore.getSavedIp(this);
            headerTitle.setText(savedName());
            headerSubtitle.setText(ip == null ? getString(R.string.not_connected)
                    : getString(R.string.connecting_to, ip));
            styleDot(getColor(R.color.amber));
            setPowerBadge("…", getColor(R.color.muted),
                    getColor(R.color.surface_2), getColor(R.color.stroke));
            refreshEnabled();
            return;
        }
        String ip = service.getIp();
        String name = service.displayName();
        headerTitle.setText(name);
        LgTvClient.State state = service.getState();
        String detail = service.getDetail();
        updatePowerBadge(state);
        switch (state) {
            case READY:
                styleDot(getColor(R.color.green));
                headerSubtitle.setText(readyDetail(ip)
                        + (service.isPointerReady() ? "" : " · preparing keys…"));
                hideBanner();
                break;
            case CONNECTING:
                styleDot(getColor(R.color.amber));
                headerSubtitle.setText(ip == null ? getString(R.string.connecting)
                        : getString(R.string.connecting_to, ip));
                hideBanner();
                break;
            case PAIRING:
                styleDot(getColor(R.color.violet));
                headerSubtitle.setText(getString(R.string.approve_tv) + (ip == null ? "" : " · " + ip));
                tintBanner(getColor(R.color.violet));
                showBanner(getString(R.string.pairing_banner), null, null);
                break;
            case RECONNECTING:
                styleDot(getColor(R.color.amber));
                headerSubtitle.setText(detail);
                tintBanner(getColor(R.color.amber));
                showBanner(detail, getString(R.string.retry), this::retry);
                break;
            case ERROR:
                styleDot(getColor(R.color.danger));
                headerSubtitle.setText(detail);
                tintBanner(getColor(R.color.danger));
                showBanner(detail, getString(R.string.retry), this::retry);
                break;
            case IDLE:
            default:
                styleDot(getColor(R.color.muted));
                headerSubtitle.setText(ip == null ? getString(R.string.not_connected)
                        : getString(R.string.not_connected) + " · " + ip);
                if (ip != null) {
                    tintBanner(getColor(R.color.stroke));
                    showBanner(getString(R.string.disconnected_banner),
                            getString(R.string.reconnect), this::retry);
                } else {
                    hideBanner();
                }
                break;
        }
        refreshEnabled();
        if (!setupVisible) {
            remoteView.setVisibility(View.VISIBLE);
            setupView.setVisibility(View.GONE);
        }
    }

    private String readyDetail(String ip) {
        StringBuilder line = new StringBuilder(
                service == null ? getString(R.string.connected) : service.getTvPowerLabel());
        String input = service == null ? null : service.getActiveInputLabel();
        String address = ip;
        if (service != null && service.isBoxActive()) {
            input = getString(R.string.android_tv_box);
            String boxHost = SecureStore.getBoxHost(this);
            if (boxHost != null && !boxHost.isEmpty()) {
                address = boxHost;
            }
        }
        if (input != null && !input.isEmpty()) {
            line.append(" · ").append(input);
        }
        if (address != null && !address.isEmpty()) {
            line.append(" · ").append(address);
        }
        return line.toString();
    }

    private void updatePowerBadge(LgTvClient.State state) {
        if (powerBadge == null) {
            return;
        }
        if (state == LgTvClient.State.READY && service != null) {
            if (service.isScreenOff()) {
                setPowerBadge(getString(R.string.tv_power_screen_off),
                        getColor(R.color.amber), getColor(R.color.surface_2),
                        getColor(R.color.amber));
            } else if (service.isTvOff()) {
                setPowerBadge(getString(R.string.tv_power_standby),
                        getColor(R.color.amber), getColor(R.color.surface_2),
                        getColor(R.color.amber));
            } else {
                setPowerBadge(getString(R.string.tv_power_on),
                        getColor(R.color.accent), getColor(R.color.accent_dim),
                        getColor(R.color.accent));
            }
            return;
        }
        if (state == LgTvClient.State.CONNECTING || state == LgTvClient.State.PAIRING) {
            setPowerBadge("…", getColor(R.color.violet),
                    getColor(R.color.surface_2), getColor(R.color.stroke));
        } else if (state == LgTvClient.State.IDLE && service == null) {
            setPowerBadge("…", getColor(R.color.muted),
                    getColor(R.color.surface_2), getColor(R.color.stroke));
        } else {
            setPowerBadge(getString(R.string.tv_power_offline),
                    getColor(R.color.muted), getColor(R.color.surface_2),
                    getColor(R.color.stroke));
        }
    }

    private void setPowerBadge(String text, int textColor, int fill, int stroke) {
        powerBadge.setText(text);
        powerBadge.setTextColor(textColor);
        powerBadge.setBackground(Ui.round(this, fill, 99, stroke, 1));
    }

    private void styleDot(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
    }

    private void showBanner(String text, String action, Runnable runnable) {
        bannerText.setText(text);
        bannerActionRunnable = runnable;
        if (action == null) {
            bannerAction.setVisibility(View.GONE);
        } else {
            bannerAction.setVisibility(View.VISIBLE);
            bannerAction.setText(action);
            bannerAction.setOnClickListener(v -> {
                clickHaptic(v);
                if (bannerActionRunnable != null) {
                    bannerActionRunnable.run();
                }
            });
        }
        banner.setVisibility(View.VISIBLE);
    }

    private void tintBanner(int strokeColor) {
        banner.setBackground(Ui.round(this, getColor(R.color.surface_2), 14, strokeColor, 1));
    }

    private void hideBanner() {
        banner.setVisibility(View.GONE);
    }

    private void refreshEnabled() {
        boolean connected = service != null && service.isConnected();
        boolean pointer = connected && service.isPointerReady();
        for (View view : keyControls) {
            view.setEnabled(pointer);
            view.setAlpha(pointer ? 1f : 0.42f);
        }
        for (View view : commandControls) {
            view.setEnabled(connected);
            view.setAlpha(connected ? 1f : 0.42f);
        }
        refreshTextBar(connected);
    }

    private void refreshTextBar(boolean connected) {
        if (tvTextField == null) {
            return;
        }
        boolean boxActive = service != null && service.isBoxActive();
        boolean boxReady = boxActive && AndroidTvRemote.isPaired(this);
        boolean ready = connected && !boxTextBusy && (!boxActive || boxReady);
        for (View view : textControls) {
            view.setEnabled(ready);
            view.setAlpha(ready ? 1f : 0.42f);
        }
        if (tvKeyboardHint != null) {
            if (!connected) {
                tvKeyboardHint.setText(R.string.tv_keyboard_offline);
            } else if (boxActive && !boxReady) {
                tvKeyboardHint.setText(R.string.tv_keyboard_box_unpaired);
            } else if (boxActive) {
                tvKeyboardHint.setText(R.string.tv_keyboard_box_help);
            } else {
                tvKeyboardHint.setText(R.string.tv_keyboard_help);
            }
        }
    }

    private void retry() {
        manuallyDisconnected = false;
        if (service != null) {
            service.reconnect();
        } else {
            String saved = SecureStore.getSavedIp(this);
            if (saved != null) {
                RemoteService.start(this, saved, SecureStore.getSavedName(this));
            }
        }
    }

    // ------------------------------------------------------------------ service callbacks

    @Override
    public void onConnection(LgTvClient.State state, String detail, String name, String model) {
        if (headerTitle != null && name != null && !name.isEmpty()) {
            headerTitle.setText(name);
        }
        if (state != LgTvClient.State.READY) {
            textAutoOpened = false;
        }
        renderConnection();
    }

    @Override
    public void onPointer(boolean ready) {
        renderConnection();
    }

    @Override
    public void onVolume(int volume, boolean muted) {
        if (volumeLabel != null) {
            volumeLabel.setText(volume >= 0 ? String.format(Locale.US, "VOL %d", volume) : "VOL");
        }
        if (muteLabel != null) {
            muteLabel.setText(muted ? getString(R.string.muted) : getString(R.string.mute));
        }
    }

    @Override
    public void onApps(List<LgTvClient.TvApp> apps) {
        populateApps(apps);
    }

    @Override
    public void onInputs(List<LgTvClient.TvInput> inputs) {
        populateInputs(inputs);
        if (service != null) {
            renderConnection();
        }
    }

    @Override
    public void onForegroundApp(String appId) {
        foregroundApp = appId == null ? "" : appId;
        if (service != null) {
            renderConnection();
        }
    }

    @Override
    public void onTvPower(String state) {
        if (service != null) {
            renderConnection();
        }
    }

    @Override
    public void onTextField(boolean focused, String contentType, boolean hidden) {
        if (tvTextField == null) {
            return;
        }
        if (focused && !hidden) {
            boolean lgReady = service != null && service.isConnected() && !service.isBoxActive();
            if (lgReady && !textAutoOpened) {
                textAutoOpened = true;
                tvTextField.postDelayed(() -> {
                    if (isFinishing() || isDestroyed() || tvTextField == null) {
                        return;
                    }
                    if (service == null || !service.isTextFieldFocused()
                            || service.isBoxActive()) {
                        return;
                    }
                    showTvKeyboard();
                }, 220);
            }
        } else {
            textAutoOpened = false;
            if (tvTextField.hasFocus() && (service == null || !service.isBoxActive())) {
                tvTextField.clearFocus();
                hideKeyboard();
            }
        }
    }

    @Override
    public void onBoxTextField(boolean focused, String label) {
        if (tvTextField == null) {
            return;
        }
        if (focused) {
            boolean boxReady = service != null && service.isConnected() && service.isBoxActive();
            if (boxReady && !boxTextAutoOpened) {
                boxTextAutoOpened = true;
                tvTextField.postDelayed(() -> {
                    if (isFinishing() || isDestroyed() || tvTextField == null) {
                        return;
                    }
                    if (service == null || !service.isBoxActive()
                            || !service.isBoxTextFieldFocused() || !hasWindowFocus()) {
                        return;
                    }
                    if (remoteView != null && remoteView.getVisibility() != View.VISIBLE) {
                        return;
                    }
                    showTvKeyboard();
                }, 220);
            }
        } else {
            boxTextAutoOpened = false;
            if (tvTextField.hasFocus() && service != null && service.isBoxActive()) {
                tvTextField.clearFocus();
                hideKeyboard();
            }
        }
    }

    private void showTvKeyboard() {
        if (tvTextField == null) {
            return;
        }
        tvTextField.requestFocus();
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.showSoftInput(tvTextField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ------------------------------------------------------------------ actions

    private void sendButton(String name) {
        if (service == null) {
            toast(R.string.tv_not_ready);
            return;
        }
        if ("VOLUMEUP".equals(name) || "VOLUMEDOWN".equals(name) || "MUTE".equals(name)) {
            // Volume, mute and power are always TV controls, even on the box input.
            if (!service.sendLgButton(name)) {
                toast(R.string.tv_not_ready);
            }
            return;
        }
        service.activeRemote().sendNavigation(name, this::onRemoteAction);
    }

    private void onRemoteAction(boolean ok, String error) {
        if (!ok) {
            toast(error == null ? getString(R.string.tv_not_ready) : error);
        }
    }

    private void homePressed() {
        if (service == null || !service.isConnected()) {
            toast(R.string.tv_not_ready);
            return;
        }
        boolean box = service.isBoxActive();
        if (box && !AndroidTvRemote.isPaired(this)) {
            showBoxSetupPrompt();
            return;
        }
        if (box && boxHomeBusy) {
            return;
        }
        if (box) {
            boxHomeBusy = true;
            showBoxProgress(R.string.box_home_sending);
        }
        service.activeRemote().sendHome((ok, error) -> {
            if (box) {
                boxHomeBusy = false;
                dismissBoxProgress();
            }
            if (!ok) {
                toast(error == null ? getString(R.string.tv_not_ready) : error);
                if (box && error != null
                        && error.toLowerCase(Locale.US).contains("pair")) {
                    showBoxSetupPrompt();
                }
            }
        });
    }

    /** Opens settings on whichever device is currently on screen. */
    private void deviceSettingsPressed() {
        if (service == null || !service.isConnected()) {
            toast(R.string.settings_not_ready);
            return;
        }
        boolean box = service.isBoxActive();
        if (box && !AndroidTvRemote.isPaired(this)) {
            showBoxSetupPrompt();
            return;
        }
        if (box && boxSettingsBusy) {
            return;
        }
        if (box) {
            boxSettingsBusy = true;
            showBoxProgress(R.string.box_settings_sending);
        } else {
            toast(R.string.opening_tv_settings);
        }
        service.activeRemote().sendSettings((ok, error) -> {
            if (box) {
                boxSettingsBusy = false;
                dismissBoxProgress();
            }
            if (!ok) {
                toast(error == null ? getString(R.string.settings_not_ready) : error);
            }
        });
    }

    private void sendTvText() {
        sendTvText(false);
    }

    private void sendTvTextAndEnter() {
        sendTvText(true);
    }

    private void sendTvText(boolean enter) {
        if (tvTextField == null) {
            return;
        }
        if (service == null || !service.isConnected()) {
            toast(R.string.tv_not_ready);
            return;
        }
        String text = tvTextField.getText().toString();
        if (text.isEmpty() && !enter) {
            toast(R.string.tv_keyboard_empty);
            return;
        }
        boolean box = service.isBoxActive();
        if (box && !AndroidTvRemote.isPaired(this)) {
            showBoxSetupPrompt();
            return;
        }
        if (box && boxTextBusy) {
            return;
        }
        RemoteController remote = service.activeRemote();
        if (text.isEmpty()) {
            sendRemoteEnter(remote, box);
            return;
        }
        if (box) {
            setBoxTextBusy(true);
        }
        remote.sendText(text, (ok, error) -> {
            if (!ok) {
                if (box) {
                    setBoxTextBusy(false);
                }
                toast(error == null ? getString(R.string.tv_keyboard_failed) : error);
                return;
            }
            clearTvText();
            if (!enter) {
                if (box) {
                    setBoxTextBusy(false);
                }
                return;
            }
            if (box) {
                main.postDelayed(() -> sendRemoteEnter(remote, true), 260);
            } else {
                sendRemoteEnter(remote, false);
            }
        });
    }

    private void sendRemoteEnter(RemoteController remote, boolean box) {
        if (box) {
            setBoxTextBusy(true);
        }
        remote.sendEnter((ok, error) -> {
            if (box) {
                setBoxTextBusy(false);
            }
            if (!ok) {
                toast(error == null ? getString(R.string.tv_keyboard_failed) : error);
            }
        });
    }

    private void deleteTvText() {
        if (service == null || !service.isConnected()) {
            toast(R.string.tv_not_ready);
            return;
        }
        boolean box = service.isBoxActive();
        if (box && !AndroidTvRemote.isPaired(this)) {
            showBoxSetupPrompt();
            return;
        }
        if (box && boxTextBusy) {
            return;
        }
        if (box) {
            setBoxTextBusy(true);
        }
        service.activeRemote().sendDelete((ok, error) -> {
            if (box) {
                setBoxTextBusy(false);
            }
            if (!ok) {
                toast(error == null ? getString(R.string.tv_keyboard_failed) : error);
            }
        });
    }

    private void clearTvText() {
        if (tvTextField != null) {
            tvTextField.setText("");
        }
    }

    private void setBoxTextBusy(boolean busy) {
        boxTextBusy = busy;
        refreshEnabled();
    }

    private void showBoxSetupPrompt() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.box_setup_title)
                .setMessage(R.string.box_setup_body)
                .setPositiveButton(R.string.box_pair, (dialog, which) -> beginBoxPairing())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void beginBoxPairing() {
        if (boxPairing != null) {
            boxPairing.close();
            boxPairing = null;
        }
        String saved = SecureStore.getBoxHost(this);
        if (saved != null && !saved.isEmpty()) {
            // Try the known address first. It avoids depending on mDNS and keeps re-pairing quick.
            startBoxPairing(saved, true);
            return;
        }
        discoverBoxForPairing();
    }

    private void discoverBoxForPairing() {
        showBoxProgress(R.string.box_finding);
        AndroidTvRemote.discover(this, (host, error) -> {
            dismissBoxProgress();
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (host == null) {
                toast(error == null ? getString(R.string.box_not_found) : error);
                showBoxIpDialog();
                return;
            }
            startBoxPairing(host, false);
        });
    }

    private void showBoxIpDialog() {
        EditText address = new EditText(this);
        address.setHint(R.string.box_ip_hint);
        address.setHintTextColor(getColor(R.color.muted));
        address.setTextColor(getColor(R.color.text));
        address.setTextSize(15);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setBackground(Ui.round(this, getColor(R.color.surface_2), 12,
                getColor(R.color.stroke), 1));
        String saved = SecureStore.getBoxHost(this);
        if (saved != null && !saved.isEmpty()) {
            address.setText(saved);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.box_ip_title)
                .setMessage(R.string.box_ip_body)
                .setView(address)
                .setPositiveButton(R.string.connect, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String host = address.getText().toString().trim();
                    if (!Patterns.IP_ADDRESS.matcher(host).matches()) {
                        address.setError(getString(R.string.invalid_ip));
                        return;
                    }
                    dialog.dismiss();
                    startBoxPairing(host, false);
                }));
        dialog.show();
    }

    private void startBoxPairing(String host, boolean searchOnFailure) {
        showBoxProgress(R.string.box_connecting);
        boxPairing = AndroidTvRemote.startPairing(this, host, (ok, error) -> {
            dismissBoxProgress();
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!ok) {
                boxPairing = null;
                if (searchOnFailure) {
                    toast(error == null ? getString(R.string.box_not_found) : error);
                    discoverBoxForPairing();
                } else {
                    toast(error == null ? getString(R.string.box_pair_failed) : error);
                }
                return;
            }
            showBoxCodeDialog();
        });
    }

    private void showBoxCodeDialog() {
        EditText code = new EditText(this);
        code.setHint(R.string.box_code_hint);
        code.setHintTextColor(getColor(R.color.muted));
        code.setTextColor(getColor(R.color.text));
        code.setTextSize(24);
        code.setGravity(Gravity.CENTER);
        code.setSingleLine(true);
        code.setLetterSpacing(0.18f);
        code.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(6)});
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.box_code_title)
                .setMessage(R.string.box_code_body)
                .setView(code)
                .setPositiveButton(R.string.box_pair_confirm, null)
                .setNegativeButton(R.string.cancel, (d, which) -> cancelBoxPairing())
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> cancelBoxPairing());
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = code.getText().toString().trim().toUpperCase(Locale.US);
                if (!value.matches("[0-9A-F]{6}")) {
                    code.setError(getString(R.string.box_invalid_code));
                    return;
                }
                dialog.dismiss();
                finishBoxPairing(value);
            });
            code.requestFocus();
            code.postDelayed(() -> {
                InputMethodManager keyboard = (InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) {
                    keyboard.showSoftInput(code, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 250);
        });
        dialog.show();
    }

    private void finishBoxPairing(String code) {
        AndroidTvRemote.Pairing pairing = boxPairing;
        if (pairing == null) {
            return;
        }
        showBoxProgress(R.string.box_pairing);
        pairing.finish(code, (ok, error) -> {
            dismissBoxProgress();
            boxPairing = null;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (ok) {
                toast(R.string.box_paired);
                if (service != null && service.isConnected()) {
                    service.linkBoxToActiveInput();
                    if (service.isBoxActive()) {
                        homePressed();
                    }
                }
            } else {
                toast(error == null ? getString(R.string.box_pair_failed) : error);
            }
        });
    }

    private void cancelBoxPairing() {
        if (boxPairing != null) {
            boxPairing.close();
            boxPairing = null;
        }
    }

    private void showBoxProgress(int message) {
        dismissBoxProgress();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boxProgress = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .create();
        boxProgress.show();
    }

    private void dismissBoxProgress() {
        if (boxProgress != null) {
            boxProgress.dismiss();
            boxProgress = null;
        }
    }

    private void showBoxOptions(Dialog settings) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.box_section)
                .setItems(new String[]{getString(R.string.box_pair_again),
                        getString(R.string.forget_pairing)}, (dialog, which) -> {
                    settings.dismiss();
                    if (which == 0) {
                        beginBoxPairing();
                    } else {
                        confirmForgetBox();
                    }
                })
                .show();
    }

    private void confirmForgetBox() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.box_forget_title)
                .setMessage(R.string.box_forget_body)
                .setPositiveButton(R.string.box_forget_confirm, (dialog, which) -> {
                    cancelBoxPairing();
                    AndroidTvRemote.forget(this);
                    toast(R.string.box_forgotten);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String boxInputLabel() {
        String id = SecureStore.getBoxInputId(this);
        if (id == null || id.isEmpty()) {
            return getString(R.string.box_input_unlinked);
        }
        String label = SecureStore.getBoxInputLabel(this);
        return getString(R.string.box_input_row,
                label == null || label.isEmpty() ? id : label);
    }

    private void showBoxInputPicker(TextView row) {
        if (service == null || !service.isConnected()) {
            toast(R.string.settings_not_ready);
            return;
        }
        List<LgTvClient.TvInput> inputs = service.getInputs();
        if (inputs.isEmpty()) {
            service.requestInputs();
            toast(R.string.box_input_loading);
            return;
        }
        String[] labels = new String[inputs.size() + 1];
        for (int i = 0; i < inputs.size(); i++) {
            LgTvClient.TvInput input = inputs.get(i);
            labels[i] = input.label;
        }
        labels[inputs.size()] = getString(R.string.box_input_clear);
        new AlertDialog.Builder(this)
                .setTitle(R.string.box_input_title)
                .setItems(labels, (dialog, which) -> {
                    if (which == inputs.size()) {
                        SecureStore.setBoxInput(this, "", "");
                        toast(R.string.box_input_cleared);
                    } else {
                        LgTvClient.TvInput input = inputs.get(which);
                        service.linkBoxInput(input.id);
                        toast(getString(R.string.box_input_linked, input.label));
                    }
                    if (row != null) {
                        row.setText(boxInputLabel());
                    }
                })
                .show();
    }

    private void powerPressed() {
        if (service != null && service.isConnected()) {
            if (service.isTvOff()) {
                wakeTv();
                return;
            }
            if (service.isScreenOff()) {
                service.turnOnScreen();
                toast(R.string.turn_on_sent);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.power_off_title)
                    .setMessage(R.string.power_off_body)
                    .setPositiveButton(R.string.turn_off, (dialog, which) -> service.powerOff())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        wakeTv();
    }

    private void wakeTv() {
        String ip = service != null ? service.getIp() : SecureStore.getSavedIp(this);
        String mac = SecureStore.getMac(this);
        if (!WakeOnLan.isValidMac(mac)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.wake)
                    .setMessage(R.string.no_mac)
                    .setPositiveButton(R.string.wake_settings, (dialog, which) -> showSettings())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        WakeOnLan.send(mac, ip);
        toast(R.string.wake_sent);
        retry();
    }

    // ------------------------------------------------------------------ dialogs

    private void showSettings() {
        dismiss(settingsDialog);
        Dialog dialog = new Dialog(this);
        settingsDialog = dialog;

        LinearLayout panel = sheet(dialog, getString(R.string.settings_title));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout content = Ui.column(this);
        content.setPadding(0, dp(6), 0, dp(2));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int height = Math.min(dp(560), getResources().getDisplayMetrics().heightPixels - dp(110));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(300), height)));
        panel.addView(scroll);

        content.addView(Ui.label(this, getString(R.string.current_tv)));
        String ip = service != null ? service.getIp() : SecureStore.getSavedIp(this);
        String name = service != null ? service.displayName() : savedName();
        TextView tvName = Ui.text(this, name, 17, getColor(R.color.text), Typeface.BOLD);
        LinearLayout.LayoutParams tvNameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvNameParams.topMargin = dp(8);
        content.addView(tvName, tvNameParams);
        String ipText = (ip == null ? getString(R.string.not_connected) : ip)
                + (service != null && service.getTvModel() != null && !service.getTvModel().isEmpty()
                ? "  ·  " + service.getTvModel() : "");
        TextView tvIp = Ui.text(this, ipText, 12, getColor(R.color.muted), Typeface.NORMAL);
        LinearLayout.LayoutParams tvIpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvIpParams.topMargin = dp(4);
        content.addView(tvIp, tvIpParams);
        boolean paired = ip != null && SecureStore.hasClientKey(this, ip);
        TextView pairState = Ui.text(this, paired ? getString(R.string.paired_keystore)
                        : getString(R.string.paired_none), 11,
                paired ? getColor(R.color.green) : getColor(R.color.muted), Typeface.NORMAL);
        pairState.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pairParams.topMargin = dp(5);
        content.addView(pairState, pairParams);

        LinearLayout actions = Ui.row(this);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(14);
        content.addView(actions, actionParams);
        TextView reconnect = pill(getString(R.string.reconnect), true);
        TextView disconnect = pill(getString(R.string.disconnect), false);
        TextView change = pill(getString(R.string.change_tv), false);
        actions.addView(reconnect, weighted(1f, 42, 0, 6));
        actions.addView(disconnect, weighted(1f, 42, 0, 6));
        actions.addView(change, weighted(1f, 42, 0, 0));
        reconnect.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            retry();
        });
        disconnect.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            manuallyDisconnected = true;
            if (service != null) {
                service.disconnectAndStop();
            }
        });
        change.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            manuallyDisconnected = true;
            if (service != null) {
                service.disconnectAndStop();
            }
            showSetup(true);
        });

        content.addView(divider(), sectionGap());

        content.addView(Ui.label(this, getString(R.string.box_section)));
        boolean boxPaired = AndroidTvRemote.isPaired(this);
        TextView boxRow = settingsRow(boxPaired ? getString(R.string.box_row_paired)
                : getString(R.string.box_row_unpaired), false);
        LinearLayout.LayoutParams boxRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxRowParams.topMargin = dp(6);
        content.addView(boxRow, boxRowParams);
        boxRow.setOnClickListener(v -> {
            clickHaptic(v);
            if (AndroidTvRemote.isPaired(this)) {
                showBoxOptions(dialog);
            } else {
                dialog.dismiss();
                beginBoxPairing();
            }
        });
        TextView boxHelp = Ui.text(this, getString(R.string.box_row_help), 11,
                getColor(R.color.muted), Typeface.NORMAL);
        boxHelp.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams boxHelpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxHelpParams.topMargin = dp(7);
        content.addView(boxHelp, boxHelpParams);

        TextView inputLabel = Ui.label(this, getString(R.string.box_input_section));
        LinearLayout.LayoutParams inputLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLabelParams.topMargin = dp(14);
        content.addView(inputLabel, inputLabelParams);
        TextView boxInputRow = settingsRow(boxInputLabel(), false);
        LinearLayout.LayoutParams inputRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputRowParams.topMargin = dp(6);
        content.addView(boxInputRow, inputRowParams);
        boxInputRow.setOnClickListener(v -> {
            clickHaptic(v);
            showBoxInputPicker(boxInputRow);
        });

        content.addView(divider(), sectionGap());

        content.addView(Ui.label(this, getString(R.string.wake_mac)));
        LinearLayout macRow = Ui.row(this);
        LinearLayout.LayoutParams macRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        macRowParams.topMargin = dp(8);
        content.addView(macRow, macRowParams);
        EditText macField = new EditText(this);
        macField.setHint(R.string.wake_mac_hint);
        macField.setHintTextColor(getColor(R.color.muted));
        macField.setTextColor(getColor(R.color.text));
        macField.setTextSize(13);
        macField.setSingleLine(true);
        macField.setInputType(InputType.TYPE_CLASS_TEXT);
        macField.setPadding(dp(12), 0, dp(12), 0);
        macField.setBackground(Ui.round(this, getColor(R.color.surface_2), 12,
                getColor(R.color.stroke), 1));
        macField.setText(SecureStore.getMac(this));
        macRow.addView(macField, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView save = pill(getString(R.string.save), false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        saveParams.leftMargin = dp(8);
        macRow.addView(save, saveParams);
        save.setOnClickListener(v -> {
            clickHaptic(v);
            String value = macField.getText().toString().trim();
            if (value.isEmpty()) {
                SecureStore.setMac(this, "");
                toast(R.string.mac_saved);
                return;
            }
            String normalized = WakeOnLan.normalize(value);
            if (normalized == null) {
                macField.setError(getString(R.string.mac_invalid));
                return;
            }
            SecureStore.setMac(this, normalized);
            macField.setText(normalized);
            toast(R.string.mac_saved);
        });
        TextView macHelp = Ui.text(this, getString(R.string.mac_help), 11,
                getColor(R.color.muted), Typeface.NORMAL);
        macHelp.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams macHelpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        macHelpParams.topMargin = dp(7);
        content.addView(macHelp, macHelpParams);

        content.addView(divider(), sectionGap());

        LinearLayout speedHeader = Ui.row(this);
        speedHeader.addView(Ui.label(this, getString(R.string.pointer_speed)));
        View speedSpacer = new View(this);
        speedHeader.addView(speedSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView speedValue = Ui.text(this,
                getString(R.string.pointer_speed_value, SecureStore.getPointerSpeed(this)),
                12, getColor(R.color.accent), Typeface.BOLD);
        speedHeader.addView(speedValue);
        content.addView(speedHeader);

        SeekBar speed = new SeekBar(this);
        speed.setMax(250);
        speed.setProgress(Math.round((SecureStore.getPointerSpeed(this) - 0.5f) * 100));
        speed.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        speed.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        speedParams.topMargin = dp(4);
        content.addView(speed, speedParams);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float value = 0.5f + progress / 100f;
                speedValue.setText(getString(R.string.pointer_speed_value, value));
                if (trackpad != null) {
                    trackpad.setPointerSpeed(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                SecureStore.setPointerSpeed(MainActivity.this, 0.5f + bar.getProgress() / 100f);
            }
        });

        LinearLayout hapticRow = Ui.row(this);
        LinearLayout.LayoutParams hapticParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hapticParams.topMargin = dp(12);
        content.addView(hapticRow, hapticParams);
        LinearLayout hapticText = Ui.column(this);
        hapticRow.addView(hapticText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hapticText.addView(Ui.text(this, getString(R.string.haptics), 14,
                getColor(R.color.text), Typeface.BOLD));
        TextView hapticHelp = Ui.text(this, getString(R.string.haptics_help), 11,
                getColor(R.color.muted), Typeface.NORMAL);
        hapticHelp.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams hapticHelpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hapticHelpParams.topMargin = dp(3);
        hapticText.addView(hapticHelp, hapticHelpParams);
        Switch hapticSwitch = new Switch(this);
        hapticSwitch.setChecked(haptics);
        hapticSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        hapticSwitch.setTrackTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_dim)));
        hapticRow.addView(hapticSwitch);
        hapticSwitch.setOnCheckedChangeListener((button, checked) -> {
            haptics = checked;
            SecureStore.setHaptics(MainActivity.this, checked);
            if (dpad != null) {
                dpad.setHaptics(checked);
            }
            if (trackpad != null) {
                trackpad.setHaptics(checked);
            }
        });

        content.addView(divider(), sectionGap());

        TextView help = settingsRow(getString(R.string.tv_help), false);
        content.addView(help, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        help.setOnClickListener(v -> {
            clickHaptic(v);
            showHelp();
        });
        TextView forget = settingsRow(getString(R.string.forget_pairing), true);
        LinearLayout.LayoutParams forgetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        forgetParams.topMargin = dp(4);
        content.addView(forget, forgetParams);
        forget.setOnClickListener(v -> {
            clickHaptic(v);
            confirmForget(dialog);
        });

        TextView about = Ui.text(this, getString(R.string.about, BuildConfig.VERSION_NAME), 10,
                getColor(R.color.muted), Typeface.NORMAL);
        about.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aboutParams.topMargin = dp(18);
        content.addView(about, aboutParams);

        showSheet(dialog, panel);
    }

    private void confirmForget(Dialog settings) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_title)
                .setMessage(R.string.forget_body)
                .setPositiveButton(R.string.forget_confirm, (dialog, which) -> {
                    settings.dismiss();
                    manuallyDisconnected = true;
                    if (service != null) {
                        service.forgetPairing();
                    } else {
                        String ip = SecureStore.getSavedIp(this);
                        if (ip != null) {
                            SecureStore.forgetClientKey(this, ip);
                        }
                    }
                    toast(R.string.forgotten);
                    showSetup(false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private TextView settingsRow(String label, boolean danger) {
        TextView row = Ui.text(this, label, 13,
                danger ? getColor(R.color.danger) : getColor(R.color.text), Typeface.BOLD);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 12,
                getColor(R.color.stroke), 1));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_help_title)
                .setMessage(R.string.setup_help)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showApps() {
        if (service == null || !service.isConnected()) {
            toast(R.string.tv_not_ready);
            return;
        }
        dismiss(appsDialog);
        Dialog dialog = new Dialog(this);
        appsDialog = dialog;
        LinearLayout panel = sheet(dialog, getString(R.string.open_app_title));
        appsStatus = Ui.text(this, getString(R.string.loading_apps), 12,
                getColor(R.color.muted), Typeface.NORMAL);
        appsStatus.setGravity(Gravity.CENTER);
        panel.addView(appsStatus);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int height = Math.min(dp(430), getResources().getDisplayMetrics().heightPixels / 2);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        appsList = Ui.column(this);
        scroll.addView(appsList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll);
        if (!service.getApps().isEmpty()) {
            populateApps(service.getApps());
        }
        showSheet(dialog, panel);
        service.requestApps();
    }

    private void populateApps(List<LgTvClient.TvApp> apps) {
        if (appsList == null) {
            return;
        }
        appsList.removeAllViews();
        if (apps == null || apps.isEmpty()) {
            if (appsStatus != null) {
                appsStatus.setText(R.string.no_apps);
                appsStatus.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (appsStatus != null) {
            appsStatus.setVisibility(View.GONE);
        }
        for (LgTvClient.TvApp app : apps) {
            appsList.addView(appRow(app));
        }
    }

    private View appRow(LgTvClient.TvApp app) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 12,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(6);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);
        TextView badge = Ui.text(this,
                app.title.substring(0, 1).toUpperCase(Locale.US), 14,
                getColor(R.color.accent), Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(this, getColor(R.color.accent_dim), 10));
        row.addView(badge, Ui.dpParams(this, 34, 34));
        LinearLayout text = Ui.column(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(10);
        row.addView(text, textParams);
        text.addView(Ui.text(this, app.title, 14, getColor(R.color.text), Typeface.BOLD));
        TextView id = Ui.text(this, app.id, 10, getColor(R.color.muted), Typeface.NORMAL);
        LinearLayout.LayoutParams idParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        idParams.topMargin = dp(2);
        text.addView(id, idParams);
        row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 16));
        row.setOnClickListener(v -> {
            clickHaptic(v);
            service.launchApp(app.id);
            toast(getString(R.string.opening_app, app.title));
            if (appsDialog != null) {
                appsDialog.dismiss();
            }
        });
        return row;
    }

    private void showInputs() {
        if (service == null || !service.isConnected()) {
            toast(R.string.tv_not_ready);
            return;
        }
        dismiss(inputsDialog);
        Dialog dialog = new Dialog(this);
        inputsDialog = dialog;
        LinearLayout panel = sheet(dialog, getString(R.string.inputs_title));
        inputsStatus = Ui.text(this, getString(R.string.loading_inputs), 12,
                getColor(R.color.muted), Typeface.NORMAL);
        inputsStatus.setGravity(Gravity.CENTER);
        panel.addView(inputsStatus);
        LinearLayout list = Ui.column(this);
        inputsList = list;
        panel.addView(list);
        if (!service.getInputs().isEmpty()) {
            populateInputs(service.getInputs());
        }
        showSheet(dialog, panel);
        service.requestInputs();
    }

    private void populateInputs(List<LgTvClient.TvInput> inputs) {
        if (inputsList == null) {
            return;
        }
        inputsList.removeAllViews();
        if (inputs == null || inputs.isEmpty()) {
            if (inputsStatus != null) {
                inputsStatus.setText(R.string.no_inputs);
                inputsStatus.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (inputsStatus != null) {
            inputsStatus.setVisibility(View.GONE);
        }
        for (LgTvClient.TvInput input : inputs) {
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(14), dp(13), dp(14), dp(13));
            row.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 12,
                    getColor(R.color.stroke), 1));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(6);
            row.setLayoutParams(rowParams);
            row.setClickable(true);
            row.setFocusable(true);
            View dot = new View(this);
            dot.setBackground(Ui.round(this, input.connected ? getColor(R.color.green)
                    : getColor(R.color.stroke), 99));
            LinearLayout.LayoutParams dotParams = Ui.dpParams(this, 8, 8);
            dotParams.rightMargin = dp(10);
            row.addView(dot, dotParams);
            TextView label = Ui.text(this, input.label, 14, getColor(R.color.text), Typeface.BOLD);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (input.connected) {
                row.addView(Ui.text(this, getString(R.string.connected_input), 10,
                        getColor(R.color.green), Typeface.BOLD));
            }
            row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 16));
            row.setOnClickListener(v -> {
                clickHaptic(v);
                service.switchInput(input.id);
                toast(getString(R.string.switching_input, input.label));
                if (inputsDialog != null) {
                    inputsDialog.dismiss();
                }
            });
            inputsList.addView(row);
        }
    }

    private LinearLayout sheet(Dialog dialog, String title) {
        LinearLayout panel = Ui.column(this);
        panel.setPadding(dp(18), dp(12), dp(18), dp(20));
        panel.setBackground(Ui.round(this, getColor(R.color.surface), 26,
                getColor(R.color.stroke), 1));

        View handle = new View(this);
        handle.setBackground(Ui.round(this, getColor(R.color.stroke), 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        panel.addView(handle, handleParams);

        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, title, 17, getColor(R.color.text), Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton close = iconButton(R.drawable.ic_close, dialog::dismiss, getString(R.string.cancel));
        header.addView(close);
        panel.addView(header);
        return panel;
    }

    private void showSheet(Dialog dialog, View panel) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(8), 0, dp(8), dp(8));
        wrapper.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(wrapper);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.62f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(getColor(R.color.stroke));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return view;
    }

    private LinearLayout.LayoutParams sectionGap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(16);
        params.bottomMargin = dp(16);
        return params;
    }

    // ------------------------------------------------------------------ small views

    private ImageButton iconButton(int iconRes, Runnable action, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(getColor(R.color.text));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(Ui.circle(this, getColor(R.color.surface), getColor(R.color.stroke)));
        button.setContentDescription(description);
        button.setLayoutParams(Ui.dpParams(this, 42, 42));
        button.setOnClickListener(v -> {
            clickHaptic(v);
            action.run();
        });
        return button;
    }

    private TextView pill(String label, boolean primary) {
        TextView view = Ui.text(this, label, 12,
                primary ? getColor(R.color.on_accent) : getColor(R.color.text), Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(primary
                ? Ui.pressable(this, getColor(R.color.accent), 12)
                : Ui.pressable(this, getColor(R.color.surface_2), 12,
                getColor(R.color.stroke), 1));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void attachRepeat(View view, Runnable action) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] held = new boolean[1];
        final Runnable[] tick = new Runnable[1];
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (!v.isEnabled()) {
                        return false;
                    }
                    held[0] = true;
                    v.setPressed(true);
                    if (haptics) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    action.run();
                    tick[0] = new Runnable() {
                        @Override
                        public void run() {
                            if (held[0]) {
                                action.run();
                                handler.postDelayed(this, 120);
                            }
                        }
                    };
                    handler.postDelayed(tick[0], 400);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    held[0] = false;
                    v.setPressed(false);
                    if (tick[0] != null) {
                        handler.removeCallbacks(tick[0]);
                    }
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void clickHaptic(View view) {
        if (haptics) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (manager != null && focus != null) {
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void dismiss(Dialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
