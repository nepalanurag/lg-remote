package com.nepalanurag.lgremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Patterns;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import android.widget.HorizontalScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The whole phone UI: discovery and pairing, the button remote, trackpad and settings. */
public final class MainActivity extends Activity implements RemoteService.Listener, AndroidTvRemote.VoiceSessionListener {
    private static final String TAG = "LgRemote";
    private static final int REQ_NOTIFICATIONS = 41;
    /**
     * A brief settle time for the keyboard, short enough that letters show up almost as they are
     * typed. Keystrokes that arrive during the pause are batched into one edit.
     */
    private static final long AUTO_SEND_DELAY_MS = 180L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoSendRunnable = this::autoSendText;
    private final List<View> keyControls = new ArrayList<>();
    private final List<View> commandControls = new ArrayList<>();
    private final List<View> textControls = new ArrayList<>();
    private final List<View> appsTiles = new ArrayList<>();
    private final List<TvDiscovery.Tv> lastDiscoveredTvs = new ArrayList<>();
    private final List<AndroidTvRemote.DiscoveredBox> lastDiscoveredBoxes = new ArrayList<>();

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
    private TextView modeBadge;
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
    private final List<ImageView> muteIcons = new ArrayList<>();
    private CarouselView carousel;
    private final List<View> carouselDots = new ArrayList<>();
    private View tvKeyboardBar;
    private RemoteEditText tvTextField;
    private TextView tvKeyboardLabel;
    private TextView tvKeyboardHint;
    private boolean textAutoOpened;
    private boolean boxTextAutoOpened;
    private boolean changingTvText;
    private boolean textSendInFlight;
    /** The prefix of the phone draft that the active screen already has. */
    private String sentText = "";
    private int textSession;
    private String foregroundApp = "";
    private static final int REQUEST_RECORD_AUDIO = 4102;
    private static final int REQUEST_SPEECH_DICTATION = 4103;
    private final VoiceStreamer voiceStreamer = new VoiceStreamer();
    private Drawable voiceOriginalBackground;
    private SpeechRecognizer inAppRecognizer;
    private boolean inAppRecognizing;

    private TvDiscovery discovery;
    private Dialog settingsDialog;
    private Dialog appsDialog;
    private Dialog inputsDialog;
    private Dialog pairingPinDialog;
    private EditText pairingPinField;
    private TextView pairingPinError;
    private TextView pairingPinAction;
    private boolean pairingPinSubmitting;
    private LinearLayout appsList;
    private TextView appsStatus;
    private LinearLayout inputsList;
    private TextView inputsStatus;
    private AndroidTvRemote.Pairing boxPairing;
    private AlertDialog boxProgress;
    private boolean boxHomeBusy;
    private boolean boxSettingsBusy;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RemoteService.LocalBinder) binder).service();
            bound = true;
            service.addListener(MainActivity.this);
            if (service.getBoxMouse() != null) {
                service.getBoxMouse().autoConnect();
            }
            if (service.getIp() != null && !setupVisible) {
                showRemote();
            }
            renderConnection();
            maybeOpenTextKeyboard();
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

        int mode = SecureStore.getDeviceMode(this);
        boolean hasTv = SecureStore.getSavedIp(this) != null;
        boolean hasBox = AndroidTvRemote.isPaired(this);
        boolean ready = (mode == SecureStore.MODE_ANDROID_TV && hasBox)
                || (mode == SecureStore.MODE_LG_TV && hasTv)
                || (mode == SecureStore.MODE_AUTO && (hasTv || hasBox));
        if (!ready) {
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
        AndroidTvRemote.setVoiceSessionListener(this);
        int mode = SecureStore.getDeviceMode(this);
        String saved = SecureStore.getSavedIp(this);
        if (!setupVisible && !manuallyDisconnected) {
            if (mode == SecureStore.MODE_ANDROID_TV && AndroidTvRemote.isPaired(this)) {
                RemoteService.start(this, null, null);
            } else if (saved != null) {
                RemoteService.start(this, null, null);
            }
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
        AndroidTvRemote.setVoiceSessionListener(null);
        voiceStreamer.stop();
        dismissVoiceActiveHud();
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
        dismiss(pairingPinDialog);
        dismissBoxProgress();
        cancelBoxPairing();
        if (discovery != null) {
            discovery.stop();
        }
        voiceStreamer.stop();
        if (inAppRecognizer != null) {
            try {
                inAppRecognizer.destroy();
            } catch (Exception ignored) {
            }
            inAppRecognizer = null;
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
        TextView brand = Ui.text(this, "LG", 10, getColor(R.color.accent),
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

        content.addView(Ui.label(this, getString(R.string.choose_remote_mode)));
        LinearLayout modeRow = Ui.row(this);
        LinearLayout.LayoutParams modeRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        modeRowParams.topMargin = dp(8);
        content.addView(modeRow, modeRowParams);

        int currentMode = SecureStore.getDeviceMode(this);
        TextView optBoth = pill(getString(R.string.mode_unified), currentMode == SecureStore.MODE_AUTO);
        TextView optBox = pill(getString(R.string.mode_android_tv), currentMode == SecureStore.MODE_ANDROID_TV);
        TextView optLg = pill(getString(R.string.mode_lg_tv), currentMode == SecureStore.MODE_LG_TV);
        modeRow.addView(optBoth, weighted(1f, 40, 0, 4));
        modeRow.addView(optBox, weighted(1f, 40, 0, 4));
        modeRow.addView(optLg, weighted(1f, 40, 0, 0));

        if (currentMode == SecureStore.MODE_ANDROID_TV) {
            title.setText(R.string.android_tv_setup_title);
            sub.setText(R.string.android_tv_setup_sub);
        }

        Runnable updateSetupModeUi = () -> {
            int m = SecureStore.getDeviceMode(this);
            optBoth.setBackground(m == SecureStore.MODE_AUTO
                    ? Ui.pressable(this, getColor(R.color.accent), 12)
                    : Ui.pressable(this, getColor(R.color.surface_2), 12, getColor(R.color.stroke), 1));
            optBoth.setTextColor(m == SecureStore.MODE_AUTO ? getColor(R.color.on_accent) : getColor(R.color.text));

            optBox.setBackground(m == SecureStore.MODE_ANDROID_TV
                    ? Ui.pressable(this, getColor(R.color.accent), 12)
                    : Ui.pressable(this, getColor(R.color.surface_2), 12, getColor(R.color.stroke), 1));
            optBox.setTextColor(m == SecureStore.MODE_ANDROID_TV ? getColor(R.color.on_accent) : getColor(R.color.text));

            optLg.setBackground(m == SecureStore.MODE_LG_TV
                    ? Ui.pressable(this, getColor(R.color.accent), 12)
                    : Ui.pressable(this, getColor(R.color.surface_2), 12, getColor(R.color.stroke), 1));
            optLg.setTextColor(m == SecureStore.MODE_LG_TV ? getColor(R.color.on_accent) : getColor(R.color.text));

            if (m == SecureStore.MODE_ANDROID_TV) {
                title.setText(R.string.android_tv_setup_title);
                sub.setText(R.string.android_tv_setup_sub);
                manualIp.setHint(R.string.box_ip_hint);
            } else {
                title.setText(R.string.setup_title);
                sub.setText(R.string.setup_sub);
                manualIp.setHint(R.string.tv_ip_hint);
            }
            if (discovery != null) {
                discovery.stop();
            }
            discoveryStarted = false;
            startDiscovery();
        };

        optBoth.setOnClickListener(v -> {
            clickHaptic(v);
            SecureStore.setDeviceMode(this, SecureStore.MODE_AUTO);
            updateSetupModeUi.run();
        });
        optBox.setOnClickListener(v -> {
            clickHaptic(v);
            SecureStore.setDeviceMode(this, SecureStore.MODE_ANDROID_TV);
            updateSetupModeUi.run();
        });
        optLg.setOnClickListener(v -> {
            clickHaptic(v);
            SecureStore.setDeviceMode(this, SecureStore.MODE_LG_TV);
            updateSetupModeUi.run();
        });

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
        int mode = SecureStore.getDeviceMode(this);
        if (mode == SecureStore.MODE_ANDROID_TV) {
            startBoxPairing(ip, false);
            return;
        }
        connectTo(ip, "");
    }

    private void startDiscovery() {
        int mode = SecureStore.getDeviceMode(this);
        discoveryStarted = true;
        setupStatus.setText(R.string.searching);
        lastDiscoveredTvs.clear();
        lastDiscoveredBoxes.clear();
        renderDiscovery();

        if (mode == SecureStore.MODE_AUTO || mode == SecureStore.MODE_LG_TV) {
            if (discovery != null) {
                discovery.stop();
                discovery.start(tvs -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    lastDiscoveredTvs.clear();
                    if (tvs != null) {
                        lastDiscoveredTvs.addAll(tvs);
                    }
                    renderDiscovery();
                });
            }
        }

        if (mode == SecureStore.MODE_AUTO || mode == SecureStore.MODE_ANDROID_TV) {
            AndroidTvRemote.discoverAll(this, boxes -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                lastDiscoveredBoxes.clear();
                if (boxes != null) {
                    lastDiscoveredBoxes.addAll(boxes);
                }
                renderDiscovery();
            });
        }
    }

    private void renderDiscovery() {
        discoveryList.removeAllViews();
        int mode = SecureStore.getDeviceMode(this);
        int totalFound = 0;

        if (mode == SecureStore.MODE_AUTO) {
            totalFound = lastDiscoveredTvs.size() + lastDiscoveredBoxes.size();
            if (totalFound == 0) {
                setupStatus.setText(R.string.searching);
                return;
            }
            setupStatus.setText(getString(R.string.tv_found, totalFound));

            // 1. LG Smart TV section
            TextView lgLabel = Ui.label(this, getString(R.string.mode_lg_tv).toUpperCase(Locale.US));
            lgLabel.setPadding(0, dp(4), 0, dp(2));
            discoveryList.addView(lgLabel);

            if (lastDiscoveredTvs.isEmpty()) {
                TextView waiting = Ui.text(this, "Searching for LG webOS TV...", 12, getColor(R.color.muted), Typeface.NORMAL);
                waiting.setPadding(dp(4), dp(4), dp(4), dp(8));
                discoveryList.addView(waiting);
            } else {
                for (TvDiscovery.Tv tv : lastDiscoveredTvs) {
                    discoveryList.addView(tvRow(tv));
                }
            }

            // 2. Android TV Box section
            TextView boxLabel = Ui.label(this, getString(R.string.mode_android_tv).toUpperCase(Locale.US));
            boxLabel.setPadding(0, dp(14), 0, dp(2));
            discoveryList.addView(boxLabel);

            if (lastDiscoveredBoxes.isEmpty()) {
                TextView waiting = Ui.text(this, "Searching for Android TV box...", 12, getColor(R.color.muted), Typeface.NORMAL);
                waiting.setPadding(dp(4), dp(4), dp(4), dp(8));
                discoveryList.addView(waiting);
            } else {
                List<AndroidTvRemote.DiscoveredBox> sorted = new ArrayList<>(lastDiscoveredBoxes);
                sorted.sort((a, b) -> {
                    boolean aMatch = getMatchingTvInputLabel(a) != null;
                    boolean bMatch = getMatchingTvInputLabel(b) != null;
                    if (aMatch != bMatch) return aMatch ? -1 : 1;
                    boolean aPaired = SecureStore.isBoxPaired(this) && a.host.equals(SecureStore.getBoxHost(this));
                    boolean bPaired = SecureStore.isBoxPaired(this) && b.host.equals(SecureStore.getBoxHost(this));
                    if (aPaired != bPaired) return aPaired ? -1 : 1;
                    return (a.name != null ? a.name : "").compareToIgnoreCase(b.name != null ? b.name : "");
                });
                for (AndroidTvRemote.DiscoveredBox box : sorted) {
                    discoveryList.addView(boxRow(box));
                }
            }
        } else if (mode == SecureStore.MODE_LG_TV) {
            totalFound = lastDiscoveredTvs.size();
            if (totalFound == 0) {
                setupStatus.setText(R.string.no_tvs_found);
                return;
            }
            setupStatus.setText(getString(R.string.tv_found, totalFound));
            for (TvDiscovery.Tv tv : lastDiscoveredTvs) {
                discoveryList.addView(tvRow(tv));
            }
        } else if (mode == SecureStore.MODE_ANDROID_TV) {
            totalFound = lastDiscoveredBoxes.size();
            if (totalFound == 0) {
                setupStatus.setText(R.string.box_not_found);
                return;
            }
            setupStatus.setText(getString(R.string.tv_found, totalFound));
            for (AndroidTvRemote.DiscoveredBox box : lastDiscoveredBoxes) {
                discoveryList.addView(boxRow(box));
            }
        }
    }

    private String getMatchingTvInputLabel(AndroidTvRemote.DiscoveredBox box) {
        if (box == null || box.name == null) return null;
        String normBox = box.name.trim().toLowerCase(Locale.ROOT);
        List<LgTvClient.TvInput> inputs = service != null ? service.getInputs() : null;
        if (inputs != null) {
            for (LgTvClient.TvInput in : inputs) {
                if (in == null || in.label == null) continue;
                String normInput = in.label.trim().toLowerCase(Locale.ROOT);
                if (normInput.isEmpty() || normInput.startsWith("hdmi") || normInput.equals("av") || normInput.equals("component")) {
                    continue;
                }
                if (normBox.equals(normInput) || normBox.contains(normInput) || normInput.contains(normBox)) {
                    return in.label;
                }
            }
        }
        String savedLabel = SecureStore.getBoxInputLabel(this);
        if (savedLabel != null && !savedLabel.isEmpty()) {
            String normSaved = savedLabel.trim().toLowerCase(Locale.ROOT);
            if (normBox.equals(normSaved) || normBox.contains(normSaved) || normSaved.contains(normBox)) {
                return savedLabel;
            }
        }
        return null;
    }

    private AndroidTvRemote.DiscoveredBox findBoxMatchingTvInput(List<AndroidTvRemote.DiscoveredBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        String savedHost = SecureStore.getBoxHost(this);
        if (savedHost != null && !savedHost.isEmpty()) {
            for (AndroidTvRemote.DiscoveredBox box : boxes) {
                if (box != null && savedHost.equals(box.host)) {
                    return box;
                }
            }
        }
        List<LgTvClient.TvInput> inputs = service != null ? service.getInputs() : null;
        if (inputs == null || inputs.isEmpty()) {
            String savedLabel = SecureStore.getBoxInputLabel(this);
            if (savedLabel != null && !savedLabel.isEmpty()) {
                for (AndroidTvRemote.DiscoveredBox box : boxes) {
                    if (box.name != null && box.name.equalsIgnoreCase(savedLabel.trim())) {
                        return box;
                    }
                }
            }
            return null;
        }

        for (LgTvClient.TvInput in : inputs) {
            if (in == null || in.label == null) continue;
            String normInput = in.label.trim().toLowerCase(Locale.ROOT);
            if (normInput.isEmpty() || normInput.startsWith("hdmi") || normInput.equals("av") || normInput.equals("component")) {
                continue;
            }
            for (AndroidTvRemote.DiscoveredBox box : boxes) {
                if (box == null || box.name == null) continue;
                String normBox = box.name.trim().toLowerCase(Locale.ROOT);
                if (normBox.equals(normInput) || normBox.contains(normInput) || normInput.contains(normBox)) {
                    if (in.id != null) {
                        SecureStore.setBoxInput(this, in.id, in.label);
                    }
                    return box;
                }
            }
        }
        return null;
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

        boolean isSavedTv = tv.ip.equals(SecureStore.getSavedIp(this));
        if (isSavedTv) {
            TextView badgePaired = Ui.text(this, "SAVED", 10, getColor(R.color.accent), Typeface.BOLD);
            badgePaired.setBackground(Ui.round(this, getColor(R.color.accent_dim), 8));
            badgePaired.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.addView(badgePaired);
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 18));
        }

        row.setOnClickListener(v -> {
            clickHaptic(v);
            int mode = SecureStore.getDeviceMode(this);
            if (mode == SecureStore.MODE_ANDROID_TV) {
                startBoxPairing(tv.ip, false);
            } else {
                connectTo(tv.ip, tv.name);
            }
        });
        return row;
    }

    private View boxRow(AndroidTvRemote.DiscoveredBox box) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 16,
                getColor(R.color.stroke), 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(box.name + ", " + box.host);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.round(this, getColor(R.color.accent_dim), 12));
        ImageView icon = Ui.icon(this, R.drawable.ic_home, getColor(R.color.accent), 20);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        row.addView(badge, Ui.dpParams(this, 40, 40));

        LinearLayout text = Ui.column(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(11);
        row.addView(text, textParams);
        text.addView(Ui.text(this, box.name != null ? box.name : "Android TV", 14, getColor(R.color.text), Typeface.BOLD));

        boolean isPaired = SecureStore.isBoxPaired(this) && box.host.equals(SecureStore.getBoxHost(this));
        String matchedInput = getMatchingTvInputLabel(box);
        String sub = box.host;
        if (matchedInput != null) {
            sub += "  ·  TV Input: " + matchedInput;
        } else if (isPaired) {
            sub += "  ·  Paired";
        } else {
            sub += "  ·  Tap to pair";
        }
        TextView subView = Ui.text(this, sub, 11, getColor(R.color.muted), Typeface.NORMAL);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(3);
        text.addView(subView, subParams);

        if (matchedInput != null) {
            TextView badgeTvInput = Ui.text(this, "TV INPUT", 10, getColor(R.color.accent), Typeface.BOLD);
            badgeTvInput.setBackground(Ui.round(this, getColor(R.color.accent_dim), 8));
            badgeTvInput.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.addView(badgeTvInput);
        } else if (isPaired) {
            TextView badgePaired = Ui.text(this, "PAIRED", 10, getColor(R.color.accent), Typeface.BOLD);
            badgePaired.setBackground(Ui.round(this, getColor(R.color.accent_dim), 8));
            badgePaired.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.addView(badgePaired);
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 18));
        }

        row.setOnClickListener(v -> {
            clickHaptic(v);
            int mode = SecureStore.getDeviceMode(this);
            if (isPaired) {
                SecureStore.setBoxHost(this, box.host);
                if (box.name != null) SecureStore.setBoxName(this, box.name);
                if (mode == SecureStore.MODE_ANDROID_TV) {
                    RemoteService.start(this, null, null);
                    showRemote();
                } else {
                    toast(getString(R.string.box_paired) + " (" + box.host + ")");
                    if (service != null) service.updateActiveControllers();
                    renderDiscovery();
                }
            } else {
                startBoxPairing(box.host, false);
            }
        });
        row.setOnLongClickListener(v -> {
            clickHaptic(v);
            startBoxPairing(box.host, false);
            return true;
        });
        return row;
    }

    private void connectTo(String ip, String name) {
        hideKeyboard();
        dismiss(pairingPinDialog);
        manuallyDisconnected = false;
        RemoteService.start(this, ip, name);
        headerTitle.setText(name == null || name.isEmpty() ? getString(R.string.device_unknown) : name);
        headerSubtitle.setText(getString(R.string.connecting_to, ip));
        showRemote();
        setupStatus.setText(R.string.searching);
    }

    // ------------------------------------------------------------------ remote screen

    private View buildRemote() {
        if (isLandscape()) {
            return buildLandscapeRemote();
        }
        return buildPortraitRemote();
    }

    private ImageButton cornerToggleBtn;
    private FrameLayout rightDeckContainer;
    private ImageButton powerBtn;

    private View buildPortraitRemote() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(16), dp(10), dp(16), dp(10));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Header: [App Settings Tuner] --- [Device / Input Pill ▾] --- [Dynamic Power Button]
        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int headerBtnSize = dp(42);

        // Left: App & Connection Settings (tuner icon)
        ImageButton appSettings = new ImageButton(this);
        appSettings.setImageResource(R.drawable.ic_tv_settings);
        appSettings.setColorFilter(getColor(R.color.text));
        appSettings.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        appSettings.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        appSettings.setContentDescription(getString(R.string.settings));
        appSettings.setOnClickListener(v -> {
            clickHaptic(v);
            showSettings();
        });
        header.addView(appSettings, new LinearLayout.LayoutParams(headerBtnSize, headerBtnSize));

        Space leftSpace = new Space(this);
        header.addView(leftSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        // Center: Device / Input selector dropdown pill
        LinearLayout devicePill = Ui.row(this);
        devicePill.setGravity(Gravity.CENTER);
        devicePill.setPadding(dp(14), dp(8), dp(14), dp(8));
        devicePill.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 24, getColor(R.color.stroke), 1));
        devicePill.setOnClickListener(v -> {
            clickHaptic(v);
            showInputs();
        });
        devicePill.setOnLongClickListener(v -> {
            clickHaptic(v);
            showDeviceModeDialog();
            return true;
        });

        statusDot = new View(this);
        statusDot.setBackground(Ui.round(this, getColor(R.color.amber), 99));
        LinearLayout.LayoutParams dotParams = Ui.dpParams(this, 7, 7);
        dotParams.rightMargin = dp(6);
        devicePill.addView(statusDot, dotParams);

        ImageView inputIcon = Ui.icon(this, R.drawable.ic_input, getColor(R.color.text), 15);
        LinearLayout.LayoutParams inputIconParams = new LinearLayout.LayoutParams(dp(15), dp(15));
        inputIconParams.rightMargin = dp(6);
        devicePill.addView(inputIcon, inputIconParams);

        headerTitle = Ui.text(this, savedName(), 13.5f, getColor(R.color.text), Typeface.BOLD);
        headerTitle.setSingleLine(true);
        headerTitle.setMaxWidth(dp(125));
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        devicePill.addView(headerTitle);

        ImageView chevron = Ui.icon(this, R.drawable.ic_chevron_down, getColor(R.color.muted), 14);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        chevronParams.leftMargin = dp(5);
        devicePill.addView(chevron, chevronParams);

        header.addView(devicePill);

        Space rightSpace = new Space(this);
        header.addView(rightSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        // Right: Power button (green = on, orange = standby, red = off)
        powerBtn = new ImageButton(this);
        powerBtn.setImageResource(R.drawable.ic_power);
        powerBtn.setColorFilter(Color.WHITE);
        powerBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        powerBtn.setBackground(Ui.circle(this, getColor(R.color.danger), getColor(R.color.danger)));
        powerBtn.setContentDescription(getString(R.string.power));
        powerBtn.setOnClickListener(v -> {
            clickHaptic(v);
            powerPressed();
        });
        powerBtn.setOnLongClickListener(v -> {
            clickHaptic(v);
            wakeBoxCec(true);
            return true;
        });
        header.addView(powerBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        updatePowerButton();

        content.addView(header);

        // Hidden aux views for renderConnection() compatibility
        headerSubtitle = Ui.text(this, "", 0, 0, Typeface.NORMAL);
        headerSubtitle.setVisibility(View.GONE);
        content.addView(headerSubtitle);

        modeBadge = Ui.text(this, "", 0, 0, Typeface.NORMAL);
        modeBadge.setVisibility(View.GONE);
        content.addView(modeBadge);

        powerBadge = Ui.text(this, "", 0, 0, Typeface.NORMAL);
        powerBadge.setVisibility(View.GONE);
        content.addView(powerBadge);

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
        bannerParams.topMargin = dp(6);
        content.addView(banner, bannerParams);

        // 2. TV Text Keyboard Bar with Voice Mic (MOVED TO TOP UNDER HEADER)
        content.addView(buildTvKeyboardBar());

        // Flexible top spacer (flex 0.12f)
        Space topSpacer = new Space(this);
        content.addView(topSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.12f));

        // 3. Top Row above D-pad: [Apps on Left] --- [123 Numbers in Middle] --- [Device Settings on Right]
        // (Like Onn remote top 3 buttons: profile, mic, settings)
        int navBtnSize = dp(56);
        LinearLayout topNavRow = Ui.row(this);
        topNavRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topNavParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navBtnSize);
        content.addView(topNavRow, topNavParams);

        // Top-Left: Apps List
        FrameLayout appsBtn = new FrameLayout(this);
        appsBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        ImageView appsIcon = Ui.icon(this, R.drawable.ic_apps, getColor(R.color.text), 26);
        appsBtn.addView(appsIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        appsBtn.setContentDescription(getString(R.string.apps));
        appsBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showApps();
        });
        topNavRow.addView(appsBtn, new LinearLayout.LayoutParams(navBtnSize, navBtnSize));

        Space topNavSpace1 = new Space(this);
        topNavRow.addView(topNavSpace1, new LinearLayout.LayoutParams(0, 1, 1f));

        // Top-Middle: 123 Numbers & Color Keys
        FrameLayout numpadBtn = new FrameLayout(this);
        numpadBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        LinearLayout numpadInner = Ui.column(this);
        numpadInner.setGravity(Gravity.CENTER);
        TextView numText = Ui.text(this, "123", 13f, getColor(R.color.text), Typeface.BOLD);
        numpadInner.addView(numText);
        LinearLayout colorDots = Ui.row(this);
        colorDots.setGravity(Gravity.CENTER);
        colorDots.setPadding(0, dp(1), 0, 0);
        int[] colors = {0xFFF44336, 0xFF4CAF50, 0xFFFFEB3B, 0xFF2196F3};
        for (int c : colors) {
            View dot = new View(this);
            dot.setBackground(Ui.round(this, c, 99));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(4), dp(4));
            dp.setMargins(dp(1), 0, dp(1), 0);
            colorDots.addView(dot, dp);
        }
        numpadInner.addView(colorDots);
        numpadBtn.addView(numpadInner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        numpadBtn.setContentDescription("123 and Color Keys");
        numpadBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showNumberPadDialog();
        });
        topNavRow.addView(numpadBtn, new LinearLayout.LayoutParams(navBtnSize, navBtnSize));

        Space topNavSpace2 = new Space(this);
        topNavRow.addView(topNavSpace2, new LinearLayout.LayoutParams(0, 1, 1f));

        // Top-Right: Device Settings (gear icon)
        FrameLayout devSettingsBtn = new FrameLayout(this);
        devSettingsBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        ImageView devSettingsIcon = Ui.icon(this, R.drawable.ic_gear, getColor(R.color.text), 26);
        devSettingsBtn.addView(devSettingsIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        devSettingsBtn.setContentDescription("Device Settings");
        devSettingsBtn.setOnClickListener(v -> {
            clickHaptic(v);
            deviceSettingsPressed();
        });
        topNavRow.addView(devSettingsBtn, new LinearLayout.LayoutParams(navBtnSize, navBtnSize));

        // Flexible spacer between Top Row and D-Pad
        Space stageSpacer1 = new Space(this);
        content.addView(stageSpacer1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.12f));

        // 4. Center Stage: Wide Rectangular Trackpad / Centered D-Pad
        int stageHeight = dp(250);
        FrameLayout stage = new FrameLayout(this);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, stageHeight);
        stageParams.topMargin = dp(2);
        stageParams.bottomMargin = dp(2);
        content.addView(stage, stageParams);

        // Circular D-Pad in center
        dpad = new DpadView(this);
        dpad.setHaptics(haptics);
        dpad.setListener(new DpadView.Listener() {
            @Override
            public void onButton(String name) {
                sendButton(name);
            }

            @Override
            public void onButtonLongPress(String name) {
                sendButtonLong(name);
            }
        });
        FrameLayout.LayoutParams dpadParams = new FrameLayout.LayoutParams(stageHeight, stageHeight, Gravity.CENTER);
        stage.addView(dpad, dpadParams);
        keyControls.add(dpad);

        // Wide Rectangular Trackpad
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
                pointerClickPressed();
            }

            @Override
            public void onScroll(int dx, int dy) {
                if (service != null) {
                    service.sendScroll(dx, dy);
                }
            }

            @Override
            public void onSecondaryClick() {
                if (service != null) {
                    service.sendSecondaryClick();
                }
            }
        });
        trackpad.setVisibility(View.GONE);
        stage.addView(trackpad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keyControls.add(trackpad);

        // Flexible spacer between D-Pad and Bottom Row
        Space stageSpacer2 = new Space(this);
        content.addView(stageSpacer2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.12f));

        // 5. Row below D-pad: [Back on Left] --- [Home in Middle] --- [Toggle on Right]
        // (Like Onn remote row: Back arrow, Home button, Live TV/Toggle)
        LinearLayout midNavRow = Ui.row(this);
        midNavRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams midNavParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navBtnSize);
        content.addView(midNavRow, midNavParams);

        // Bottom-Left: Back Button
        FrameLayout backBtn = new FrameLayout(this);
        backBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        ImageView backIcon = Ui.icon(this, R.drawable.ic_back, getColor(R.color.text), 26);
        backBtn.addView(backIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        backBtn.setContentDescription(getString(R.string.back));
        attachRepeat(backBtn, () -> sendButton("BACK"), 400, 180);
        midNavRow.addView(backBtn, new LinearLayout.LayoutParams(navBtnSize, navBtnSize));
        keyControls.add(backBtn);

        Space midNavSpace1 = new Space(this);
        midNavRow.addView(midNavSpace1, new LinearLayout.LayoutParams(0, 1, 1f));

        // Bottom-Middle: Home Button
        FrameLayout homeBtn = new FrameLayout(this);
        homeBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        ImageView homeIcon = Ui.icon(this, R.drawable.ic_home, getColor(R.color.text), 26);
        homeBtn.addView(homeIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        homeBtn.setContentDescription(getString(R.string.home));
        homeBtn.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        homeBtn.setOnLongClickListener(v -> {
            clickHaptic(v);
            lgHomePressed();
            return true;
        });
        midNavRow.addView(homeBtn, new LinearLayout.LayoutParams(navBtnSize, navBtnSize));
        keyControls.add(homeBtn);

        Space midNavSpace2 = new Space(this);
        midNavRow.addView(midNavSpace2, new LinearLayout.LayoutParams(0, 1, 1f));

        // Bottom-Right: Trackpad / D-Pad Toggle Button
        cornerToggleBtn = new ImageButton(this);
        cornerToggleBtn.setImageResource(R.drawable.ic_pointer);
        cornerToggleBtn.setColorFilter(getColor(R.color.accent));
        cornerToggleBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        cornerToggleBtn.setPadding(dp(15), dp(15), dp(15), dp(15));
        cornerToggleBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        cornerToggleBtn.setContentDescription(getString(R.string.trackpad));
        cornerToggleBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showMode(!buttonsMode);
        });
        midNavRow.addView(cornerToggleBtn, new LinearLayout.LayoutParams(navBtnSize, navBtnSize));

        // Flexible spacer before Rockers deck
        Space deckSpacer = new Space(this);
        content.addView(deckSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.15f));

        // 6. Bottom Rockers Deck: [Volume Rocker (+/VOL/-)] --- [Mute Button] --- [Channel/Media Rocker]
        // (Like Onn remote bottom: Volume pill on left, Mute circular button in center, Channel pill on right)
        LinearLayout volChannelDeck = buildLowerDeck();
        content.addView(volChannelDeck, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Bottom spacer
        Space bottomSpacer = new Space(this);
        content.addView(bottomSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.1f));

        return scroll;
    }

    private LinearLayout buildLowerDeck() {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(4), 0, dp(4), 0);

        int rockerWidth = dp(82);
        int deckHeight = dp(168);

        // 1. Left: Volume Rocker (+ / VOL / −) - 82dp width, 168dp height
        LinearLayout volRocker = Ui.column(this);
        volRocker.setGravity(Gravity.CENTER);
        volRocker.setBackground(Ui.round(this, getColor(R.color.surface_2), 38, getColor(R.color.stroke), 1));

        FrameLayout volUpBtn = new FrameLayout(this);
        volUpBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 38));
        ImageView volUpIcon = Ui.icon(this, R.drawable.ic_vol_up, getColor(R.color.text), 26);
        volUpBtn.addView(volUpIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        attachRepeat(volUpBtn, () -> sendButton("VOLUMEUP"));
        volRocker.addView(volUpBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        volumeLabel = Ui.text(this, "VOL", 13f, getColor(R.color.muted), Typeface.BOLD);
        volumeLabel.setGravity(Gravity.CENTER);
        volRocker.addView(volumeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        FrameLayout volDownBtn = new FrameLayout(this);
        volDownBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 38));
        ImageView volDownIcon = Ui.icon(this, R.drawable.ic_vol_down, getColor(R.color.text), 26);
        volDownBtn.addView(volDownIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        attachRepeat(volDownBtn, () -> sendButton("VOLUMEDOWN"));
        volRocker.addView(volDownBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        row.addView(volRocker, new LinearLayout.LayoutParams(rockerWidth, deckHeight));

        // Space between Left Rocker and Mute Button
        Space leftMidSpace = new Space(this);
        row.addView(leftMidSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        // 2. Middle: Mute Button (Circular button between volume and channel rockers)
        int muteBtnSize = dp(60);
        ImageButton muteBtn = new ImageButton(this);
        muteBtn.setImageResource(R.drawable.ic_mute);
        muteBtn.setColorFilter(getColor(R.color.text));
        muteBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        muteBtn.setPadding(dp(17), dp(17), dp(17), dp(17));
        muteBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        muteBtn.setContentDescription(getString(R.string.mute));
        muteBtn.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("MUTE");
        });
        muteIcons.add(muteBtn);
        keyControls.add(muteBtn);
        row.addView(muteBtn, new LinearLayout.LayoutParams(muteBtnSize, muteBtnSize));

        // Space between Mute Button and Right Rocker
        Space rightMidSpace = new Space(this);
        row.addView(rightMidSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        // 3. Right: Contextual Channel or Media Rocker - 82dp width, 168dp height
        rightDeckContainer = new FrameLayout(this);
        row.addView(rightDeckContainer, new LinearLayout.LayoutParams(rockerWidth, deckHeight));
        updateRightDeckRocker();

        return row;
    }

    private void updateRightDeckRocker() {
        if (rightDeckContainer == null) return;
        rightDeckContainer.removeAllViews();
        boolean isLiveTv = isLiveTvActive();

        LinearLayout rocker = Ui.column(this);
        rocker.setGravity(Gravity.CENTER);
        rocker.setBackground(Ui.round(this, getColor(R.color.surface_2), 38, getColor(R.color.stroke), 1));

        if (isLiveTv) {
            // Channel Rocker: [▲] / [CH] / [▼]
            FrameLayout chUpBtn = new FrameLayout(this);
            chUpBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 38));
            ImageView chUpIcon = Ui.icon(this, R.drawable.ic_chevron_up, getColor(R.color.text), 26);
            chUpBtn.addView(chUpIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            attachRepeat(chUpBtn, () -> sendButton("CHANNELUP"));
            rocker.addView(chUpBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            TextView chLabel = Ui.text(this, "CH", 13f, getColor(R.color.muted), Typeface.BOLD);
            chLabel.setGravity(Gravity.CENTER);
            chLabel.setOnClickListener(v -> {
                clickHaptic(v);
                sendButton("GUIDE");
            });
            rocker.addView(chLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

            FrameLayout chDownBtn = new FrameLayout(this);
            chDownBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 38));
            ImageView chDownIcon = Ui.icon(this, R.drawable.ic_chevron_down, getColor(R.color.text), 26);
            chDownBtn.addView(chDownIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            attachRepeat(chDownBtn, () -> sendButton("CHANNELDOWN"));
            rocker.addView(chDownBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            // Media Transport Rocker: [►►] / [▶||] / [◄◄]
            FrameLayout fwdBtn = new FrameLayout(this);
            fwdBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 38));
            ImageView fwdIcon = Ui.icon(this, R.drawable.ic_forward, getColor(R.color.text), 24);
            fwdBtn.addView(fwdIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            attachRepeat(fwdBtn, () -> sendButton("FASTFORWARD"));
            rocker.addView(fwdBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            FrameLayout playBtn = new FrameLayout(this);
            playBtn.setBackground(Ui.pressable(this, getColor(R.color.secondary_container), 18));
            ImageView playIcon = Ui.icon(this, R.drawable.ic_play_pause, getColor(R.color.accent), 26);
            playBtn.addView(playIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            playBtn.setOnClickListener(v -> {
                clickHaptic(v);
                sendButton("PLAY");
            });
            LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                    dp(58), dp(44));
            playParams.gravity = Gravity.CENTER_HORIZONTAL;
            rocker.addView(playBtn, playParams);

            FrameLayout rwdBtn = new FrameLayout(this);
            rwdBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 38));
            ImageView rwdIcon = Ui.icon(this, R.drawable.ic_rewind, getColor(R.color.text), 24);
            rwdBtn.addView(rwdIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            attachRepeat(rwdBtn, () -> sendButton("REWIND"));
            rocker.addView(rwdBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        rightDeckContainer.addView(rocker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildLandscapeRemote() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(14), dp(4), dp(14), dp(6));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int circleBtnSize = dp(36);

        ImageButton gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_gear);
        gear.setColorFilter(getColor(R.color.text));
        gear.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        gear.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        gear.setContentDescription(getString(R.string.settings));
        gear.setOnClickListener(v -> {
            clickHaptic(v);
            showSettings();
        });
        header.addView(gear, new LinearLayout.LayoutParams(circleBtnSize, circleBtnSize));

        Space leftSpace = new Space(this);
        header.addView(leftSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout devicePill = Ui.row(this);
        devicePill.setGravity(Gravity.CENTER);
        devicePill.setPadding(dp(10), dp(6), dp(10), dp(6));
        devicePill.setBackground(Ui.pressable(this, getColor(R.color.green), 24));
        devicePill.setOnClickListener(v -> {
            clickHaptic(v);
            showDeviceModeDialog();
        });
        devicePill.setOnLongClickListener(v -> {
            clickHaptic(v);
            showInputs();
            return true;
        });

        statusDot = new View(this);
        statusDot.setBackground(Ui.round(this, getColor(R.color.amber), 99));
        LinearLayout.LayoutParams dotParams = Ui.dpParams(this, 7, 7);
        dotParams.rightMargin = dp(4);
        devicePill.addView(statusDot, dotParams);

        ImageView tvIcon = Ui.icon(this, R.drawable.ic_tv, getColor(R.color.on_accent), 16);
        LinearLayout.LayoutParams tvIconParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        tvIconParams.rightMargin = dp(5);
        devicePill.addView(tvIcon, tvIconParams);

        headerTitle = Ui.text(this, savedName(), 13, getColor(R.color.on_accent), Typeface.BOLD);
        headerTitle.setSingleLine(true);
        headerTitle.setMaxWidth(dp(105));
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        devicePill.addView(headerTitle);

        ImageView chevron = Ui.icon(this, R.drawable.ic_chevron_down, getColor(R.color.on_accent), 14);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        chevronParams.leftMargin = dp(4);
        devicePill.addView(chevron, chevronParams);

        header.addView(devicePill);

        headerSubtitle = Ui.text(this, "", 0, 0, Typeface.NORMAL);
        headerSubtitle.setVisibility(View.GONE);
        header.addView(headerSubtitle);

        modeBadge = Ui.text(this, "", 0, 0, Typeface.NORMAL);
        modeBadge.setVisibility(View.GONE);
        header.addView(modeBadge);

        powerBadge = Ui.text(this, "", 0, 0, Typeface.NORMAL);
        powerBadge.setVisibility(View.GONE);
        header.addView(powerBadge);

        Space rightSpace = new Space(this);
        header.addView(rightSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        ImageButton appsBtn = new ImageButton(this);
        appsBtn.setImageResource(R.drawable.ic_apps);
        appsBtn.setColorFilter(getColor(R.color.text));
        appsBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        appsBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        appsBtn.setContentDescription(getString(R.string.apps));
        appsBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showApps();
        });
        LinearLayout.LayoutParams appsParams = new LinearLayout.LayoutParams(circleBtnSize, circleBtnSize);
        appsParams.rightMargin = dp(4);
        header.addView(appsBtn, appsParams);

        ImageButton inputsBtn = new ImageButton(this);
        inputsBtn.setImageResource(R.drawable.ic_input);
        inputsBtn.setColorFilter(getColor(R.color.text));
        inputsBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        inputsBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        inputsBtn.setContentDescription(getString(R.string.inputs));
        inputsBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showInputs();
        });
        LinearLayout.LayoutParams inputsParams = new LinearLayout.LayoutParams(circleBtnSize, circleBtnSize);
        inputsParams.rightMargin = dp(4);
        header.addView(inputsBtn, inputsParams);

        ImageButton power = new ImageButton(this);
        power.setImageResource(R.drawable.ic_power);
        power.setColorFilter(getColor(R.color.danger));
        power.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        power.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        power.setContentDescription(getString(R.string.power));
        power.setOnClickListener(v -> {
            clickHaptic(v);
            powerPressed();
        });
        power.setOnLongClickListener(v -> {
            clickHaptic(v);
            wakeBoxCec(true);
            return true;
        });
        header.addView(power, new LinearLayout.LayoutParams(circleBtnSize, circleBtnSize));

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
        bannerParams.topMargin = dp(6);
        content.addView(banner, bannerParams);

        LinearLayout tabs = Ui.row(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(Ui.round(this, getColor(R.color.surface), 16,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(dp(220), dp(28));
        tabsParams.gravity = Gravity.CENTER_HORIZONTAL;
        tabsParams.topMargin = dp(6);
        content.addView(tabs, tabsParams);
        buttonsTab = tabLabel(getString(R.string.buttons));
        trackpadTab = tabLabel(getString(R.string.trackpad));
        tabs.addView(buttonsTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabs.addView(trackpadTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
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
                landscapeBoardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        modesParams.topMargin = dp(6);
        modesParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(modes, modesParams);

        buttonsPanel = buildButtonsPanel();
        modes.addView(buttonsPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        trackpadPanel = buildTrackpadPanel();
        modes.addView(trackpadPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        trackpadPanel.setVisibility(View.GONE);

        styleTabs();
        content.addView(buildTvKeyboardBar());

        return scroll;
    }

    private View buildBroadcastPage() {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(4), 0, dp(4), 0);

        // 1. VOL Rocker (Left)
        View volRocker = buildVerticalRocker(
                R.drawable.ic_vol_up, "VOL", R.drawable.ic_vol_down,
                () -> sendButton("VOLUMEUP"),
                () -> sendButton("VOLUMEDOWN"),
                () -> sendButton("MUTE")
        );
        LinearLayout.LayoutParams volParams = new LinearLayout.LayoutParams(dp(82), dp(142));
        volParams.rightMargin = dp(10);
        row.addView(volRocker, volParams);

        // 2. Center Stack: 123 (Numpad) on top, GUIDE on bottom
        LinearLayout centerStack = Ui.column(this);
        centerStack.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(dp(105), dp(142));
        centerParams.rightMargin = dp(10);
        row.addView(centerStack, centerParams);

        // Top: 123 button with color dots
        LinearLayout numpadBtn = Ui.column(this);
        numpadBtn.setGravity(Gravity.CENTER);
        numpadBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 22, getColor(R.color.stroke), 1));
        numpadBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showNumberPadDialog();
        });

        TextView numText = Ui.text(this, "123", 15, getColor(R.color.text), Typeface.BOLD);
        numpadBtn.addView(numText);

        LinearLayout colorDots = Ui.row(this);
        colorDots.setGravity(Gravity.CENTER);
        colorDots.setPadding(0, dp(3), 0, 0);

        int dotSize = dp(5);
        int[] colors = {0xFFF44336, 0xFF4CAF50, 0xFFFFEB3B, 0xFF2196F3}; // Red, Green, Yellow, Blue
        for (int c : colors) {
            View dot = new View(this);
            dot.setBackground(Ui.round(this, c, 99));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dp.setMargins(dp(1.5f), 0, dp(1.5f), 0);
            colorDots.addView(dot, dp);
        }
        numpadBtn.addView(colorDots);

        LinearLayout.LayoutParams numpadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        numpadParams.bottomMargin = dp(6);
        centerStack.addView(numpadBtn, numpadParams);

        // Bottom: GUIDE button
        LinearLayout guideBtn = Ui.row(this);
        guideBtn.setGravity(Gravity.CENTER);
        guideBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 22, getColor(R.color.stroke), 1));
        guideBtn.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("GUIDE");
        });

        ImageView guideIcon = Ui.icon(this, R.drawable.ic_guide, getColor(R.color.muted), 16);
        guideBtn.addView(guideIcon);

        TextView guideText = Ui.text(this, " GUIDE", 11.5f, getColor(R.color.text), Typeface.BOLD);
        guideBtn.addView(guideText);

        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        guideParams.topMargin = dp(6);
        centerStack.addView(guideBtn, guideParams);

        // 3. CH Rocker (Right)
        View chRocker = buildVerticalRocker(
                R.drawable.ic_chevron_up, "CH", R.drawable.ic_chevron_down,
                () -> sendButton("CHANNELUP"),
                () -> sendButton("CHANNELDOWN"),
                () -> sendButton("INFO")
        );
        LinearLayout.LayoutParams chParams = new LinearLayout.LayoutParams(dp(82), dp(142));
        row.addView(chRocker, chParams);

        return row;
    }

    private View buildVerticalRocker(int upIcon, String label, int downIcon,
                                     Runnable onUp, Runnable onDown, Runnable onCenter) {
        LinearLayout rocker = Ui.column(this);
        rocker.setGravity(Gravity.CENTER);
        rocker.setBackground(Ui.round(this, getColor(R.color.surface_2), 24, getColor(R.color.stroke), 1));

        // Up button
        FrameLayout upBtn = new FrameLayout(this);
        upBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 24));
        ImageView upImg = Ui.icon(this, upIcon, getColor(R.color.text), 20);
        upBtn.addView(upImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        attachRepeat(upBtn, onUp);
        rocker.addView(upBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Center label (clickable e.g. for MUTE or INFO)
        TextView lbl = Ui.text(this, label, 11f, getColor(R.color.muted), Typeface.BOLD);
        lbl.setGravity(Gravity.CENTER);
        lbl.setPadding(0, dp(2), 0, dp(2));
        if (onCenter != null) {
            lbl.setClickable(true);
            lbl.setOnClickListener(v -> {
                clickHaptic(v);
                onCenter.run();
            });
        }
        if ("VOL".equals(label)) {
            volumeLabel = lbl;
        }
        rocker.addView(lbl);

        // Down button
        FrameLayout downBtn = new FrameLayout(this);
        downBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 24));
        ImageView downImg = Ui.icon(this, downIcon, getColor(R.color.text), 20);
        downBtn.addView(downImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        attachRepeat(downBtn, onDown);
        rocker.addView(downBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return rocker;
    }

    private View buildMediaPage() {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(4), 0, dp(4), 0);

        // 1. VOL Rocker on left
        View volRocker = buildVerticalRocker(
                R.drawable.ic_vol_up, "VOL", R.drawable.ic_vol_down,
                () -> sendButton("VOLUMEUP"),
                () -> sendButton("VOLUMEDOWN"),
                () -> sendButton("MUTE")
        );
        LinearLayout.LayoutParams volParams = new LinearLayout.LayoutParams(dp(82), dp(142));
        volParams.rightMargin = dp(12);
        row.addView(volRocker, volParams);

        // 2. Media transport controls on right
        LinearLayout mediaStack = Ui.column(this);
        mediaStack.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(dp(200), dp(142));
        row.addView(mediaStack, mediaParams);

        // Top: Play / Pause wide pill
        FrameLayout playPauseBtn = new FrameLayout(this);
        playPauseBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 22, getColor(R.color.stroke), 1));
        ImageView ppIcon = Ui.icon(this, R.drawable.ic_play_pause, getColor(R.color.text), 24);
        playPauseBtn.addView(ppIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        playPauseBtn.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("PLAY");
        });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        playParams.bottomMargin = dp(6);
        mediaStack.addView(playPauseBtn, playParams);

        // Bottom: Rewind & Fast Forward wide pill
        LinearLayout rwdFwdPill = Ui.row(this);
        rwdFwdPill.setGravity(Gravity.CENTER);
        rwdFwdPill.setBackground(Ui.round(this, getColor(R.color.surface_2), 22, getColor(R.color.stroke), 1));

        FrameLayout rwdBtn = new FrameLayout(this);
        rwdBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 22));
        ImageView rwdIcon = Ui.icon(this, R.drawable.ic_rewind, getColor(R.color.text), 22);
        rwdBtn.addView(rwdIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        rwdBtn.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("REWIND");
        });
        rwdFwdPill.addView(rwdBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View sep = new View(this);
        sep.setBackgroundColor(getColor(R.color.stroke));
        rwdFwdPill.addView(sep, new LinearLayout.LayoutParams(dp(1), dp(24)));

        FrameLayout fwdBtn = new FrameLayout(this);
        fwdBtn.setBackground(Ui.pressable(this, Color.TRANSPARENT, 22));
        ImageView fwdIcon = Ui.icon(this, R.drawable.ic_forward, getColor(R.color.text), 22);
        fwdBtn.addView(fwdIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        fwdBtn.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("FASTFORWARD");
        });
        rwdFwdPill.addView(fwdBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout.LayoutParams rfParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rfParams.topMargin = dp(6);
        mediaStack.addView(rwdFwdPill, rfParams);

        return row;
    }

    private View buildQuickAppsPage() {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(12), dp(4), dp(12), dp(4));

        LinearLayout row1 = Ui.row(this);
        row1.setGravity(Gravity.CENTER);
        panel.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row2 = Ui.row(this);
        row2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams r2Params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        r2Params.topMargin = dp(6);
        panel.addView(row2, r2Params);

        row1.addView(quickAppTile(R.drawable.ic_tv, "Live TV", () -> {
            if (service != null) service.launchApp("com.webos.app.livetv");
        }), weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 4));

        row1.addView(quickAppTile(R.drawable.ic_play_pause, "YouTube", () -> {
            if (service != null) service.launchApp("youtube.leanback.v4");
        }), weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 4, 4));

        row1.addView(quickAppTile(R.drawable.ic_input, "Den", () -> {
            String boxId = SecureStore.getBoxInputId(this);
            if (service != null && boxId != null && !boxId.isEmpty()) {
                service.switchInput(boxId);
            } else if (service != null) {
                service.switchInput("HDMI_1");
            }
        }), weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 4, 4));

        row1.addView(quickAppTile(R.drawable.ic_apps, "All Apps", this::showApps),
                weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 4, 0));

        row2.addView(quickAppTile(R.drawable.ic_home, "LG Home", this::lgHomePressed),
                weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 4));

        row2.addView(quickAppTile(R.drawable.ic_mic, "Voice", () -> {
            if (service != null) service.sendButton("MENU");
        }), weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 4, 4));

        row2.addView(quickAppTile(R.drawable.ic_tv_settings, "TV Menu", () -> {
            if (service != null) service.sendLgButton("MENU");
        }), weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 4, 4));

        row2.addView(quickAppTile(R.drawable.ic_gear, "Settings", this::showSettings),
                weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 4, 0));

        return panel;
    }

    private View quickAppTile(int iconRes, String title, Runnable action) {
        LinearLayout tile = Ui.column(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 16, getColor(R.color.stroke), 1));
        tile.setPadding(dp(4), dp(8), dp(4), dp(8));

        ImageView icon = Ui.icon(this, iconRes, getColor(R.color.text), 22);
        tile.addView(icon);

        TextView label = Ui.text(this, title, 10.5f, getColor(R.color.muted), Typeface.BOLD);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        tile.addView(label, lp);

        tile.setOnClickListener(v -> {
            clickHaptic(v);
            action.run();
        });
        return tile;
    }

    private LinearLayout buildCarouselDots(int count) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        carouselDots.clear();
        for (int i = 0; i < count; i++) {
            final int pageIndex = i;
            View dot = new View(this);
            dot.setBackground(Ui.round(this, i == 0 ? getColor(R.color.accent) : getColor(R.color.stroke), 99));
            int w = (i == 0 ? dp(14) : dp(6));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, dp(6));
            p.setMargins(dp(3), 0, dp(3), 0);
            dot.setLayoutParams(p);
            dot.setOnClickListener(v -> {
                if (carousel != null) {
                    carousel.setPage(pageIndex, true);
                }
            });
            carouselDots.add(dot);
            row.addView(dot);
        }
        return row;
    }

    private void updateCarouselDots(int activeIndex) {
        for (int i = 0; i < carouselDots.size(); i++) {
            View dot = carouselDots.get(i);
            boolean active = i == activeIndex;
            dot.setBackground(Ui.round(this, active ? getColor(R.color.accent) : getColor(R.color.stroke), 99));
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) dot.getLayoutParams();
            p.width = active ? dp(14) : dp(6);
            dot.setLayoutParams(p);
        }
    }

    private View buildLowerStage() {
        FrameLayout stage = new FrameLayout(this);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int dpadSize = Math.min(dp(290), Math.max(dp(240), screenWidth - dp(70)));

        LinearLayout stageRow = Ui.row(this);
        stageRow.setGravity(Gravity.CENTER_VERTICAL);
        stage.addView(stageRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Side action column on left
        LinearLayout sideCol = Ui.column(this);
        sideCol.setGravity(Gravity.CENTER_HORIZONTAL);
        stageRow.addView(sideCol, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton muteSideBtn = new ImageButton(this);
        muteSideBtn.setImageResource(R.drawable.ic_mute);
        muteSideBtn.setColorFilter(getColor(R.color.text));
        muteSideBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        muteSideBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        muteSideBtn.setContentDescription(getString(R.string.mute));
        muteSideBtn.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("MUTE");
        });
        sideCol.addView(muteSideBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));
        muteIcons.add(muteSideBtn);

        Space sideSpacer = new Space(this);
        sideCol.addView(sideSpacer, new LinearLayout.LayoutParams(1, dp(130)));

        ImageButton pointerToggleBtn = new ImageButton(this);
        pointerToggleBtn.setImageResource(R.drawable.ic_pointer);
        pointerToggleBtn.setColorFilter(getColor(R.color.text));
        pointerToggleBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pointerToggleBtn.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        pointerToggleBtn.setContentDescription(getString(R.string.trackpad));
        pointerToggleBtn.setOnClickListener(v -> {
            clickHaptic(v);
            toggleControlMode();
        });
        sideCol.addView(pointerToggleBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));

        // Center Stage: Holds D-Pad and Trackpad
        FrameLayout centerStage = new FrameLayout(this);
        stageRow.addView(centerStage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        dpad = new DpadView(this);
        dpad.setHaptics(haptics);
        dpad.setListener(new DpadView.Listener() {
            @Override
            public void onButton(String name) {
                sendButton(name);
            }

            @Override
            public void onButtonLongPress(String name) {
                sendButtonLong(name);
            }
        });
        FrameLayout.LayoutParams dpadParams = new FrameLayout.LayoutParams(dpadSize, dpadSize, Gravity.CENTER);
        centerStage.addView(dpad, dpadParams);
        keyControls.add(dpad);

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
                pointerClickPressed();
            }

            @Override
            public void onScroll(int dx, int dy) {
                if (service != null) {
                    service.sendScroll(dx, dy);
                }
            }

            @Override
            public void onSecondaryClick() {
                if (service != null) {
                    service.sendSecondaryClick();
                }
            }
        });
        trackpad.setVisibility(View.GONE);
        FrameLayout.LayoutParams trackpadParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        centerStage.addView(trackpad, trackpadParams);
        keyControls.add(trackpad);

        return stage;
    }

    private void toggleControlMode() {
        showMode(!buttonsMode);
    }

    private View buildBottomNav() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(4), dp(8), dp(4), dp(4));

        int btnHeight = dp(52);

        // 1. Back button (Pill on left)
        FrameLayout backBtn = new FrameLayout(this);
        backBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 26, getColor(R.color.stroke), 1));
        ImageView backImg = Ui.icon(this, R.drawable.ic_back, getColor(R.color.text), 22);
        backBtn.addView(backImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        attachRepeat(backBtn, () -> sendButton("BACK"), 400, 180);
        bar.addView(backBtn, weighted(1f, btnHeight, 0, 6));
        keyControls.add(backBtn);

        // 2. Home button (Pill in center)
        FrameLayout homeBtn = new FrameLayout(this);
        homeBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 26, getColor(R.color.stroke), 1));
        ImageView homeImg = Ui.icon(this, R.drawable.ic_home, getColor(R.color.text), 22);
        homeBtn.addView(homeImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        homeBtn.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        homeBtn.setOnLongClickListener(v -> {
            clickHaptic(v);
            lgHomePressed();
            return true;
        });
        bar.addView(homeBtn, weighted(1.15f, btnHeight, 6, 6));
        keyControls.add(homeBtn);

        // 3. Apps / Voice button (Pill on right)
        FrameLayout appBtn = new FrameLayout(this);
        appBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 26, getColor(R.color.stroke), 1));
        ImageView appImg = Ui.icon(this, R.drawable.ic_apps, getColor(R.color.text), 22);
        appBtn.addView(appImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        appBtn.setOnClickListener(v -> {
            clickHaptic(v);
            showApps();
        });
        attachVoicePushToTalk(appBtn);
        bar.addView(appBtn, weighted(1f, btnHeight, 6, 0));
        keyControls.add(appBtn);

        return bar;
    }

    private void showNumberPadDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, "Number Pad");

        // Color keys row
        LinearLayout colorRow = Ui.row(this);
        colorRow.setGravity(Gravity.CENTER);
        colorRow.setPadding(0, dp(4), 0, dp(14));

        int[] colors = {0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5};
        String[] colorNames = {"RED", "GREEN", "YELLOW", "BLUE"};

        for (int i = 0; i < colors.length; i++) {
            final String cName = colorNames[i];
            int color = colors[i];
            FrameLayout colorBtn = new FrameLayout(this);
            colorBtn.setBackground(Ui.pressable(this, color, 14));
            colorBtn.setOnClickListener(v -> {
                clickHaptic(v);
                sendButton(cName);
                toast(cName);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            cp.setMargins(dp(3), 0, dp(3), 0);
            colorRow.addView(colorBtn, cp);
        }
        panel.addView(colorRow);

        // 3x4 numeric keypad
        String[][] keyLayout = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"-", "0", "✓"}
        };

        for (int r = 0; r < keyLayout.length; r++) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            rowParams.topMargin = dp(6);
            panel.addView(row, rowParams);

            for (int c = 0; c < keyLayout[r].length; c++) {
                final String key = keyLayout[r][c];
                FrameLayout keyBtn = new FrameLayout(this);
                keyBtn.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 16, getColor(R.color.stroke), 1));

                TextView tv = Ui.text(this, key, 18, getColor(R.color.text), Typeface.BOLD);
                keyBtn.addView(tv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

                keyBtn.setOnClickListener(v -> {
                    clickHaptic(v);
                    if ("-".equals(key)) {
                        sendButton("DASH");
                    } else if ("✓".equals(key)) {
                        sendButton("ENTER");
                    } else {
                        sendButton(key);
                    }
                });

                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                kp.setMargins(dp(4), 0, dp(4), 0);
                row.addView(keyBtn, kp);
            }
        }

        showSheet(dialog, panel);
    }

    /** Persistent text entry anchored at the bottom of the remote. */
    private View buildTvKeyboardBar() {
        LinearLayout card = Ui.column(this);
        tvKeyboardBar = card;
        card.setVisibility(View.VISIBLE);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(Ui.round(this, getColor(R.color.surface_2), 18,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        cardParams.bottomMargin = dp(4);
        card.setLayoutParams(cardParams);

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        tvTextField = new RemoteEditText(this);
        tvTextField.setBackspaceListener(this::backspaceEmptyDraft);
        tvTextField.setHint(R.string.tv_keyboard_hint);
        tvTextField.setHintTextColor(getColor(R.color.muted));
        tvTextField.setTextColor(getColor(R.color.text));
        tvTextField.setTextSize(14);
        tvTextField.setSingleLine(true);
        // Plain text, never a composing/autocorrect word: the phone must not rewrite a character
        // after it has already been sent to the device.
        tvTextField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Keep the remote visible in landscape: without these, Gboard replaces the whole screen
        // with its own enlarged text editor as soon as the keyboard opens.
        tvTextField.setImeOptions(EditorInfo.IME_ACTION_SEND
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        tvTextField.setPadding(dp(14), 0, dp(14), 0);
        tvTextField.setBackground(Ui.round(this, getColor(R.color.surface), 14,
                getColor(R.color.stroke), 1));
        tvTextField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendTvTextAndEnter();
                hideKeyboard();
                return true;
            }
            return false;
        });
        tvTextField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!changingTvText) {
                    scheduleAutoSend();
                }
            }
        });
        row.addView(tvTextField, weighted(1f, 46, 0, 8));
        View textMic = circularButton(R.drawable.ic_mic, getString(R.string.voice), 46, getColor(R.color.accent));
        attachVoicePushToTalk(textMic);
        row.addView(textMic, new LinearLayout.LayoutParams(dp(46), dp(46)));
        textControls.add(tvTextField);
        textControls.add(textMic);

        card.addView(row);
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

    private boolean isLiveTvActive() {
        if (service == null) return false;
        String fg = service.getForegroundApp();
        if (fg != null) {
            String lower = fg.toLowerCase(Locale.ROOT);
            if (lower.contains("livetv") || lower.contains("tv.channel") || lower.contains("antenna")) {
                return true;
            }
        }
        LgTvClient.TvInput active = service.getActiveInput();
        if (active != null && active.label != null) {
            String lower = active.label.toLowerCase(Locale.ROOT);
            if (lower.contains("live tv") || lower.contains("antenna")) {
                return true;
            }
        }
        return false;
    }



    private LinearLayout buildButtonsPanel() {
        return buildLandscapeButtonsPanel();
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
        dpad.setListener(new DpadView.Listener() {
            @Override
            public void onButton(String name) {
                sendButton(name);
            }

            @Override
            public void onButtonLongPress(String name) {
                sendButtonLong(name);
            }
        });
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

        LinearLayout volUp = iconButton(R.drawable.ic_vol_up, getString(R.string.vol_up));
        LinearLayout volDown = iconButton(R.drawable.ic_vol_down, getString(R.string.vol_down));
        LinearLayout mute = iconButton(R.drawable.ic_mute, getString(R.string.mute));
        volume.addView(volUp, volumeButtonParams(6));
        volume.addView(volDown, volumeButtonParams(6));
        volume.addView(mute, volumeButtonParams(0));
        keyControls.add(volUp);
        keyControls.add(volDown);
        keyControls.add(mute);
        muteIcons.add((ImageView) mute.getChildAt(0));
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
        power.setOnLongClickListener(v -> {
            clickHaptic(v);
            wakeBoxCec(true);
            return true;
        });
        View inputs = actionTile(R.drawable.ic_input, getString(R.string.inputs),
                getColor(R.color.accent), this::showInputs);
        View apps = tvAppsTile();
        quick.addView(power, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(inputs, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(apps, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        controls.addView(quick, landscapeRowParams(0));
        commandControls.add(inputs);
        commandControls.add(apps);

        LinearLayout nav = Ui.row(this);
        View back = wideButton(R.drawable.ic_back, getString(R.string.back), 13);
        View mic = wideButton(R.drawable.ic_mic, getString(R.string.voice), 13);
        View home = wideButton(R.drawable.ic_home, getString(R.string.home), 13);
        nav.addView(back, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        nav.addView(mic, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        nav.addView(home, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        attachRepeat(back, () -> sendButton("BACK"), 400, 180);
        attachVoicePushToTalk(mic);
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        home.setOnLongClickListener(v -> {
            clickHaptic(v);
            lgHomePressed();
            return true;
        });
        controls.addView(nav, landscapeRowParams(8));
        keyControls.add(back);
        keyControls.add(mic);
        keyControls.add(home);

        LinearLayout transport = Ui.row(this);
        View rewind = wideButton(R.drawable.ic_rewind, getString(R.string.rewind), null, 0);
        View play = wideButton(R.drawable.ic_play_pause, getString(R.string.play_pause), null, 0);
        View forward = wideButton(R.drawable.ic_forward, getString(R.string.forward), null, 0);
        transport.addView(rewind, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        transport.addView(play, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        transport.addView(forward, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        attachRepeat(rewind, () -> sendButton("REWIND"));
        play.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("PLAY");
        });
        attachRepeat(forward, () -> sendButton("FASTFORWARD"));
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
                pointerClickPressed();
            }

            @Override
            public void onScroll(int dx, int dy) {
                if (service != null) {
                    service.sendScroll(dx, dy);
                }
            }

            @Override
            public void onSecondaryClick() {
                if (service != null) {
                    service.sendSecondaryClick();
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
        power.setOnLongClickListener(v -> {
            clickHaptic(v);
            wakeBoxCec(true);
            return true;
        });
        View inputs = actionTile(R.drawable.ic_input, getString(R.string.inputs),
                getColor(R.color.accent), this::showInputs);
        View apps = tvAppsTile();
        quick.addView(power, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(inputs, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 8));
        quick.addView(apps, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        controls.addView(quick, landscapeRowParams(0));
        commandControls.add(inputs);
        commandControls.add(apps);

        LinearLayout nav = Ui.row(this);
        View back = wideButton(R.drawable.ic_back, getString(R.string.back), 12);
        View click = wideButton(R.drawable.ic_play_pause, getString(R.string.click), 12);
        View mic = wideButton(R.drawable.ic_mic, getString(R.string.voice), 12);
        View home = wideButton(R.drawable.ic_home, getString(R.string.home), 12);
        nav.addView(back, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 5));
        nav.addView(click, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 5));
        nav.addView(mic, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 5));
        nav.addView(home, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        attachRepeat(back, () -> sendButton("BACK"), 400, 180);
        attachClickHold(click);
        attachVoicePushToTalk(mic);
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        home.setOnLongClickListener(v -> {
            clickHaptic(v);
            lgHomePressed();
            return true;
        });
        controls.addView(nav, landscapeRowParams(8));
        keyControls.add(back);
        keyControls.add(click);
        keyControls.add(mic);
        keyControls.add(home);

        LinearLayout volume = Ui.row(this);
        LinearLayout volUp = iconButton(R.drawable.ic_vol_up, getString(R.string.vol_up));
        LinearLayout volDown = iconButton(R.drawable.ic_vol_down, getString(R.string.vol_down));
        LinearLayout mute = iconButton(R.drawable.ic_mute, getString(R.string.mute));
        volume.addView(volUp, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
        volume.addView(volDown, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
        volume.addView(mute, weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        muteIcons.add((ImageView) mute.getChildAt(0));
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
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        // Top flexible spacer
        Space topSpacer = new Space(this);
        panel.addView(topSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.2f));

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int height = Math.max(dp(240), Math.min(dp(290), (int) (screenHeight * 0.36f)));
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
                pointerClickPressed();
            }

            @Override
            public void onScroll(int dx, int dy) {
                if (service != null) {
                    service.sendScroll(dx, dy);
                }
            }

            @Override
            public void onSecondaryClick() {
                if (service != null) {
                    service.sendSecondaryClick();
                }
            }
        });
        panel.addView(trackpad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        keyControls.add(trackpad);

        // Flexible middle spacer pushing controls down into thumb zone
        Space midSpacer = new Space(this);
        panel.addView(midSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));

        // Row 1: Back (circle), Home (stadium pill), Mic (circle)
        LinearLayout nav = Ui.row(this);
        nav.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navParams.bottomMargin = dp(18);
        panel.addView(nav, navParams);

        View back = circularButton(R.drawable.ic_back, getString(R.string.back), 58);
        View home = stadiumButtonWithText(R.drawable.ic_home, getString(R.string.home), 29);
        View mic = circularButton(R.drawable.ic_mic, getString(R.string.voice), 58, getColor(R.color.green));

        nav.addView(back, new LinearLayout.LayoutParams(dp(58), dp(58)));
        nav.addView(home, weighted(1f, 58, 16, 16));
        nav.addView(mic, new LinearLayout.LayoutParams(dp(58), dp(58)));

        attachRepeat(back, () -> sendButton("BACK"), 400, 180);
        attachVoicePushToTalk(mic);
        home.setOnClickListener(v -> {
            clickHaptic(v);
            homePressed();
        });
        home.setOnLongClickListener(v -> {
            clickHaptic(v);
            lgHomePressed();
            return true;
        });
        keyControls.add(back);
        keyControls.add(mic);
        keyControls.add(home);

        // Row 2: Mute (stadium), Vol- (stadium), Vol+ (stadium)
        LinearLayout volumeRow = Ui.row(this);
        volumeRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.addView(volumeRow, volumeParams);

        View mute = stadiumButton(R.drawable.ic_mute, getString(R.string.mute), 27);
        View volDown = stadiumButtonWithText(R.drawable.ic_vol_down, "VOL −", 27);
        View volUp = stadiumButtonWithText(R.drawable.ic_vol_up, "VOL +", 27);

        volumeRow.addView(mute, new LinearLayout.LayoutParams(dp(64), dp(54)));
        volumeRow.addView(volDown, weighted(1f, 54, 12, 6));
        volumeRow.addView(volUp, weighted(1f, 54, 6, 0));

        keyControls.add(mute);
        keyControls.add(volDown);
        keyControls.add(volUp);
        if (mute instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) mute;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof ImageView) {
                    muteIcons.add((ImageView) g.getChildAt(i));
                }
            }
        }
        mute.setOnClickListener(v -> {
            clickHaptic(v);
            sendButton("MUTE");
        });
        attachRepeat(volDown, () -> sendButton("VOLUMEDOWN"));
        attachRepeat(volUp, () -> sendButton("VOLUMEUP"));

        // Bottom flexible spacer
        Space bottomSpacer = new Space(this);
        panel.addView(bottomSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f));

        return panel;
    }

    private void showMode(boolean buttons) {
        buttonsMode = buttons;
        if (dpad != null) dpad.setVisibility(buttons ? View.VISIBLE : View.GONE);
        if (trackpad != null) trackpad.setVisibility(buttons ? View.GONE : View.VISIBLE);
        if (cornerToggleBtn != null) {
            cornerToggleBtn.setImageResource(buttons ? R.drawable.ic_pointer : R.drawable.ic_dpad);
            cornerToggleBtn.setContentDescription(getString(buttons ? R.string.trackpad : R.string.buttons));
        }
        if (isLandscape()) {
            if (buttonsPanel != null) buttonsPanel.setVisibility(buttons ? View.VISIBLE : View.GONE);
            if (trackpadPanel != null) trackpadPanel.setVisibility(buttons ? View.GONE : View.VISIBLE);
        }
        styleTabs();
        if (!buttons && trackpad != null) {
            trackpad.setPointerSpeed(SecureStore.getPointerSpeed(this));
            trackpad.setHaptics(SecureStore.getHaptics(this));
            ensureBluetoothPermission();
            updateTrackpadLabels();
            if (service != null && service.isBoxActive() && !service.isBoxMouseConnected() && service.getBoxMouse() != null) {
                service.getBoxMouse().autoConnect();
            }
        }
    }

    private void styleTabs() {
        if (buttonsTab == null) {
            return;
        }
        buttonsTab.setBackground(buttonsMode ? Ui.round(this, getColor(R.color.secondary_container), 14) : null);
        buttonsTab.setTextColor(buttonsMode ? getColor(R.color.accent) : getColor(R.color.muted));
        trackpadTab.setBackground(buttonsMode ? null : Ui.round(this, getColor(R.color.secondary_container), 14));
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

    /**
     * The TV app shortcut is deliberately not owned by the active-screen controller: even while
     * the box input is on screen this tile opens the LG TV's app list, not the box's apps.
     */
    private View tvAppsTile() {
        View tile = actionTile(R.drawable.ic_apps, getString(R.string.apps),
                getColor(R.color.accent), this::showApps);
        appsTiles.add(tile);
        return tile;
    }

    private void updateTvAppsLabels() {
        boolean boxActive = service != null && service.isBoxActive();
        String label = getString(boxActive ? R.string.tv_apps : R.string.apps);
        for (View tile : appsTiles) {
            tile.setContentDescription(label);
            if (tile instanceof LinearLayout) {
                LinearLayout layout = (LinearLayout) tile;
                if (layout.getChildCount() > 1) {
                    View child = layout.getChildAt(1);
                    if (child instanceof TextView) {
                        ((TextView) child).setText(label);
                    }
                }
            }
        }
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

    private View circularButton(int iconRes, String description, int sizeDp) {
        return circularButton(iconRes, description, sizeDp, getColor(R.color.text));
    }

    private View circularButton(int iconRes, String description, int sizeDp, int iconColor) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(Ui.circle(this, getColor(R.color.surface_2), getColor(R.color.stroke)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(description);
        ImageView icon = Ui.icon(this, iconRes, iconColor, 22);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER);
        button.addView(icon, params);
        return button;
    }

    private View stadiumButton(int iconRes, String description, float radiusDp) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(Ui.pressable(this, getColor(R.color.surface_2), radiusDp,
                getColor(R.color.stroke), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(description);
        ImageView icon = Ui.icon(this, iconRes, getColor(R.color.text), 22);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER);
        button.addView(icon, params);
        return button;
    }

    private View stadiumButtonWithText(int iconRes, String label, float radiusDp) {
        LinearLayout button = Ui.row(this);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.pressable(this, getColor(R.color.surface_2), radiusDp,
                getColor(R.color.stroke), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        if (iconRes != 0) {
            ImageView icon = Ui.icon(this, iconRes, getColor(R.color.text), 18);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
            iconParams.rightMargin = dp(6);
            button.addView(icon, iconParams);
        }
        TextView text = Ui.text(this, label, 12.5f, getColor(R.color.text), Typeface.BOLD);
        button.addView(text);
        return button;
    }

    private LinearLayout iconButton(int iconRes, String description) {
        LinearLayout button = Ui.column(this);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 14,
                getColor(R.color.stroke), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(description);
        button.addView(Ui.icon(this, iconRes, getColor(R.color.text), 22));
        return button;
    }

    private View wideButton(int iconRes, String description, String label, float textSize) {
        LinearLayout button = Ui.row(this);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.pressable(this, getColor(R.color.surface), 16,
                getColor(R.color.stroke), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(description);
        int iconSize = (label == null || label.isEmpty()) ? 22 : 18;
        button.addView(Ui.icon(this, iconRes, getColor(R.color.text), iconSize));
        if (label != null && !label.isEmpty()) {
            TextView text = Ui.text(this, label, textSize, getColor(R.color.text), Typeface.BOLD);
            text.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.leftMargin = dp(7);
            button.addView(text, textParams);
        }
        return button;
    }

    private View wideButton(int iconRes, String label, float textSize) {
        return wideButton(iconRes, label, label, textSize);
    }

    // ------------------------------------------------------------------ state

    private String savedName() {
        String name = SecureStore.getSavedName(this);
        return name == null || name.isEmpty() ? getString(R.string.device_unknown) : cleanName(name);
    }

    private static String cleanName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String cleaned = name.trim();
        if (cleaned.startsWith("[LG] webOS TV ")) {
            return "LG " + cleaned.substring("[LG] webOS TV ".length());
        }
        if (cleaned.startsWith("[LG] ")) {
            return "LG " + cleaned.substring("[LG] ".length());
        }
        return cleaned;
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
        updateModeBadge();
        int mode = SecureStore.getDeviceMode(this);
        if (mode == SecureStore.MODE_ANDROID_TV) {
            boolean paired = AndroidTvRemote.isPaired(this);
            boolean ready = AndroidTvRemote.isMonitorReady();
            String boxName = SecureStore.getBoxName(this);
            headerTitle.setText((boxName != null && !boxName.isEmpty()) ? boxName : getString(R.string.mode_android_tv));
            if (!paired) {
                styleDot(getColor(R.color.danger));
                headerSubtitle.setText(R.string.tv_keyboard_box_unpaired);
                tintBanner(getColor(R.color.amber));
                showBanner(getString(R.string.box_not_paired), getString(R.string.box_pair), this::showBoxSetupPrompt);
            } else if (ready) {
                styleDot(getColor(R.color.green));
                headerSubtitle.setText(R.string.connected);
                hideBanner();
            } else {
                styleDot(getColor(R.color.amber));
                headerSubtitle.setText(R.string.connecting);
                hideBanner();
            }
            setPowerBadge(ready ? "ON" : "…",
                    ready ? getColor(R.color.green) : getColor(R.color.muted),
                    getColor(R.color.surface_2), getColor(R.color.stroke));
            updatePowerButton();
            refreshEnabled();
            return;
        }
        if (service == null) {
            String ip = SecureStore.getSavedIp(this);
            headerTitle.setText(savedName());
            headerSubtitle.setText(R.string.connecting);
            styleDot(getColor(R.color.amber));
            setPowerBadge("…", getColor(R.color.muted),
                    getColor(R.color.surface_2), getColor(R.color.stroke));
            updatePowerButton();
            refreshEnabled();
            return;
        }
        String ip = service.getIp();
        if (service.isBoxActive()) {
            String boxName = SecureStore.getBoxName(this);
            headerTitle.setText((boxName != null && !boxName.isEmpty()) ? boxName : "Den");
        } else if (foregroundApp != null && foregroundApp.contains("livetv")) {
            headerTitle.setText("Live TV");
        } else if (service.getActiveInput() != null && service.getActiveInput().label != null) {
            headerTitle.setText(service.getActiveInput().label);
        } else {
            String name = service.displayName();
            headerTitle.setText(cleanName(name));
        }
        LgTvClient.State state = service.getState();
        String detail = service.getDetail();
        updatePowerBadge(state);
        updatePowerButton();
        updateRightDeckRocker();
        switch (state) {
            case READY:
                styleDot(getColor(R.color.green));
                headerSubtitle.setText(readyDetail());
                hideBanner();
                break;
            case CONNECTING:
                styleDot(getColor(R.color.amber));
                headerSubtitle.setText(R.string.connecting);
                hideBanner();
                break;
            case PAIRING:
                styleDot(getColor(R.color.violet));
                tintBanner(getColor(R.color.violet));
                if (service.isPairingPinRequired()) {
                    headerSubtitle.setText(R.string.pairing_pin_header);
                    showBanner(getString(R.string.pairing_pin_banner),
                            getString(R.string.pairing_pin_open), this::showPairingPinDialog);
                } else {
                    headerSubtitle.setText(R.string.approve_tv);
                    showBanner(getString(R.string.pairing_banner), null, null);
                }
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
                headerSubtitle.setText(R.string.not_connected);
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
        if (volumeLabel != null && service != null) {
            int vol = service.getVolume();
            boolean muted = service.isMuted();
            if (muted) {
                volumeLabel.setText(R.string.muted);
                volumeLabel.setTextColor(getColor(R.color.danger));
            } else if (vol >= 0) {
                volumeLabel.setText(String.format(java.util.Locale.US, "VOL %d", vol));
                volumeLabel.setTextColor(getColor(R.color.accent));
            }
        }
        if (!setupVisible) {
            remoteView.setVisibility(View.VISIBLE);
            setupView.setVisibility(View.GONE);
        }
    }

    private String readyDetail() {
        StringBuilder line = new StringBuilder(
                service == null ? getString(R.string.connected) : service.getTvPowerLabel());
        if (service != null && service.isBoxActive()) {
            String boxInputLabel = SecureStore.getBoxInputLabel(this);
            line.append(" · ").append(boxInputLabel != null && !boxInputLabel.isEmpty() ? boxInputLabel : getString(R.string.android_tv_box));
        } else if (service != null) {
            String appTitle = service.getActiveAppTitle();
            if (appTitle != null && !appTitle.isEmpty()) {
                line.append(" · ").append(appTitle);
            }
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

    private void updatePowerButton() {
        if (powerBtn == null) return;
        int color;
        if (service != null && service.isConnected()) {
            if (service.isScreenOff() || service.isTvOff()) {
                color = getColor(R.color.amber); // Standby: Orange
            } else {
                color = getColor(R.color.green); // On: Green
            }
        } else if (service != null && (service.getState() == LgTvClient.State.CONNECTING || service.getState() == LgTvClient.State.PAIRING)) {
            color = getColor(R.color.amber); // Standby / Connecting: Orange
        } else {
            color = getColor(R.color.danger); // Off / Disconnected: Red
        }
        powerBtn.setBackground(Ui.circle(this, color, color));
    }

    private void setPowerBadge(String text, int textColor, int fill, int stroke) {
        powerBadge.setText(text);
        powerBadge.setTextColor(textColor);
        powerBadge.setBackground(Ui.round(this, fill, 99, stroke, 1));
    }

    private void styleDot(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        if (color == getColor(R.color.green)) {
            dot.setColor(getColor(R.color.on_accent));
        } else {
            dot.setColor(color);
        }
        statusDot.setBackground(dot);
    }

    private String modeBadgeText() {
        int mode = SecureStore.getDeviceMode(this);
        if (mode == SecureStore.MODE_ANDROID_TV) {
            return getString(R.string.mode_android_tv);
        } else if (mode == SecureStore.MODE_LG_TV) {
            return getString(R.string.mode_lg_tv);
        }
        return getString(R.string.mode_unified);
    }

    private void updateModeBadge() {
        if (modeBadge != null) {
            modeBadge.setText(modeBadgeText());
        }
    }

    private View buildOptionCard(int iconRes, String title, String subtitle, boolean selected, Runnable onClick) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setBackground(Ui.pressable(this,
                selected ? getColor(R.color.secondary_container) : getColor(R.color.surface_2),
                16,
                selected ? getColor(R.color.accent) : getColor(R.color.stroke),
                selected ? 1.5f : 1f));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(Ui.circle(this,
                selected ? getColor(R.color.accent_dim) : getColor(R.color.surface),
                getColor(R.color.stroke)));
        ImageView icon = Ui.icon(this, iconRes,
                selected ? getColor(R.color.accent) : getColor(R.color.text), 20);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        iconBox.addView(icon, iconParams);
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout textCol = Ui.column(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(14);
        row.addView(textCol, textParams);

        TextView titleView = Ui.text(this, title, 15f,
                selected ? getColor(R.color.accent) : getColor(R.color.text), Typeface.BOLD);
        textCol.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = Ui.text(this, subtitle, 12f, getColor(R.color.muted), Typeface.NORMAL);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(2);
            textCol.addView(subView, subParams);
        }

        if (selected) {
            TextView check = Ui.text(this, "✓", 16f, getColor(R.color.accent), Typeface.BOLD);
            row.addView(check);
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 16));
        }

        row.setOnClickListener(v -> {
            clickHaptic(v);
            onClick.run();
        });
        return row;
    }

    private void showDeviceModeDialog() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, getString(R.string.choose_remote_mode));
        int current = SecureStore.getDeviceMode(this);

        panel.addView(buildOptionCard(R.drawable.ic_tv, getString(R.string.mode_unified),
                getString(R.string.mode_unified_desc), current == SecureStore.MODE_AUTO, () -> {
                    dialog.dismiss();
                    if (current != SecureStore.MODE_AUTO) setDeviceModeAndRefresh(SecureStore.MODE_AUTO);
                }));

        panel.addView(buildOptionCard(R.drawable.ic_apps, getString(R.string.mode_android_tv),
                getString(R.string.mode_android_tv_desc), current == SecureStore.MODE_ANDROID_TV, () -> {
                    dialog.dismiss();
                    if (current != SecureStore.MODE_ANDROID_TV) setDeviceModeAndRefresh(SecureStore.MODE_ANDROID_TV);
                }));

        panel.addView(buildOptionCard(R.drawable.ic_tv, getString(R.string.mode_lg_tv),
                getString(R.string.mode_lg_tv_desc), current == SecureStore.MODE_LG_TV, () -> {
                    dialog.dismiss();
                    if (current != SecureStore.MODE_LG_TV) setDeviceModeAndRefresh(SecureStore.MODE_LG_TV);
                }));

        showSheet(dialog, panel);
    }

    private void setDeviceModeAndRefresh(int mode) {
        SecureStore.setDeviceMode(this, mode);
        updateModeBadge();
        if (service != null) {
            service.onModeChanged();
        }
        if (mode == SecureStore.MODE_ANDROID_TV && !AndroidTvRemote.isPaired(this)) {
            showBoxSetupPrompt();
        } else if (mode == SecureStore.MODE_LG_TV && SecureStore.getSavedIp(this) == null) {
            showSetup(true);
        }
        renderConnection();
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
        boolean boxActive = service != null && service.isBoxActive();
        // The box carries its own keys, so box navigation must not wait for the TV pointer
        // socket. In TV mode the pointer is still what makes the navigation buttons usable.
        boolean keysReady = connected && (boxActive || pointer);
        for (View view : keyControls) {
            view.setEnabled(keysReady);
            view.setAlpha(keysReady ? 1f : 0.42f);
        }
        for (View view : commandControls) {
            view.setEnabled(connected);
            view.setAlpha(connected ? 1f : 0.42f);
        }
        refreshTextBar(connected);
        updateTvAppsLabels();
        updateTrackpadLabels();
    }

    private void refreshTextBar(boolean connected) {
        if (tvTextField == null) {
            return;
        }
        boolean boxActive = service != null && service.isBoxActive();
        boolean boxReady = boxActive && AndroidTvRemote.isPaired(this);
        boolean ready = connected && (!boxActive || boxReady);
        for (View view : textControls) {
            view.setEnabled(ready);
            view.setAlpha(ready ? 1f : 0.42f);
        }
        if (tvTextField != null) {
            tvTextField.setHint(boxActive
                    ? R.string.tv_keyboard_box_hint : R.string.tv_keyboard_hint);
        }
        if (tvKeyboardLabel != null) {
            tvKeyboardLabel.setText(getString(boxActive
                    ? R.string.tv_keyboard_box_section : R.string.tv_keyboard_section));
        }
        if (tvKeyboardHint != null) {
            if (!connected) {
                tvKeyboardHint.setText(R.string.tv_keyboard_offline);
                tvKeyboardHint.setVisibility(View.VISIBLE);
            } else if (boxActive && !boxReady) {
                tvKeyboardHint.setText(R.string.tv_keyboard_box_unpaired);
                tvKeyboardHint.setVisibility(View.VISIBLE);
            } else {
                tvKeyboardHint.setVisibility(View.GONE);
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
            boxTextAutoOpened = false;
        }
        if (state != LgTvClient.State.PAIRING) {
            dismiss(pairingPinDialog);
        }
        renderConnection();
        if (state == LgTvClient.State.READY) {
            maybeOpenTextKeyboard();
        }
    }

    @Override
    public void onPairingPin(boolean required, boolean submitting, String error) {
        if (!required) {
            dismiss(pairingPinDialog);
            return;
        }
        showPairingPinDialog(submitting, error);
    }

    @Override
    public void onPointer(boolean ready) {
        renderConnection();
    }

    @Override
    public void onVolume(int volume, boolean muted) {
        if (volumeLabel != null) {
            if (muted) {
                volumeLabel.setText(R.string.muted);
                volumeLabel.setTextColor(getColor(R.color.danger));
            } else {
                volumeLabel.setText(volume >= 0 ? String.format(Locale.US, "VOL %d", volume) : "VOL");
                volumeLabel.setTextColor(getColor(R.color.muted));
            }
        }
        for (ImageView icon : muteIcons) {
            icon.setColorFilter(getColor(muted ? R.color.danger : R.color.text));
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
        String next = appId == null ? "" : appId;
        if (!next.equals(foregroundApp)) {
            resetTextSession();
        }
        foregroundApp = next;
        if (service != null) {
            renderConnection();
        }
        maybeOpenTextKeyboard();
        updateTrackpadLabels();
        updateRightDeckRocker();

        if (carousel != null) {
            if (service != null && service.isBoxActive()) {
                if (carousel.getCurrentPage() == 0) {
                    carousel.setPage(1, true);
                }
            } else if (foregroundApp.contains("livetv")) {
                if (carousel.getCurrentPage() == 1) {
                    carousel.setPage(0, true);
                }
            }
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
        Log.d("LgRemote", "TV text field focused=" + focused + " hidden=" + hidden
                + " type=" + contentType);
        if (focused && !hidden) {
            setTvKeyboardBarVisible(true);
            maybeOpenTextKeyboard();
        } else {
            textAutoOpened = false;
            if (service == null || !service.isBoxActive()) {
                resetTextSession();
                hideKeyboard();
                setTvKeyboardBarVisible(false);
            }
        }
    }

    @Override
    public void onBoxTextField(boolean focused, String label) {
        if (tvTextField == null) {
            return;
        }
        if (focused) {
            setTvKeyboardBarVisible(true);
            maybeOpenTextKeyboard();
        } else {
            boxTextAutoOpened = false;
            if (service != null && service.isBoxActive()) {
                resetTextSession();
                hideKeyboard();
                setTvKeyboardBarVisible(false);
            }
        }
    }

    private void setTvKeyboardBarVisible(boolean visible) {
        runOnUiThread(() -> {
            if (tvKeyboardBar != null) {
                tvKeyboardBar.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Opens the phone keyboard when the active device reports a text field. A focus event can
     * arrive while the activity is in the background, so this is also retried when the window
     * comes back to the front instead of permanently consuming the one automatic attempt.
     */
    private void maybeOpenTextKeyboard() {
        if (tvTextField == null || service == null || !service.isConnected()
                || remoteView == null || remoteView.getVisibility() != View.VISIBLE
                || !hasWindowFocus()) {
            return;
        }
        final boolean box = service.isBoxActive();
        if (box) {
            if (!service.isBoxTextFieldFocused() || boxTextAutoOpened) {
                return;
            }
            boxTextAutoOpened = true;
        } else {
            if (!service.isTextFieldFocused() || textAutoOpened) {
                return;
            }
            textAutoOpened = true;
        }
        tvTextField.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || tvTextField == null) {
                return;
            }
            if (!hasWindowFocus() || remoteView == null
                    || remoteView.getVisibility() != View.VISIBLE
                    || service == null || !service.isConnected()) {
                if (box) {
                    boxTextAutoOpened = false;
                } else {
                    textAutoOpened = false;
                }
                return;
            }
            if (box) {
                if (!service.isBoxActive() || !service.isBoxTextFieldFocused()) {
                    boxTextAutoOpened = false;
                    return;
                }
            } else if (service.isBoxActive() || !service.isTextFieldFocused()) {
                textAutoOpened = false;
                return;
            }
            showTvKeyboard();
        }, 220);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            maybeOpenTextKeyboard();
        } else {
            textAutoOpened = false;
            boxTextAutoOpened = false;
        }
    }

    private void showTvKeyboard() {
        if (tvTextField == null) {
            return;
        }
        setTvKeyboardBarVisible(true);
        tvTextField.requestFocus();
        // In landscape the bar sits below the controls inside a ScrollView. Make sure
        // focusing it from an on-screen text field brings it into view before the IME
        // resizes the window.
        Runnable reveal = () -> {
            if (tvTextField == null) {
                return;
            }
            tvTextField.requestRectangleOnScreen(new Rect(0, 0,
                    tvTextField.getWidth(), tvTextField.getHeight()), true);
        };
        tvTextField.post(reveal);
        // The IME resize lands after the first scroll request; nudge again so the bar
        // stays above the keyboard on short landscape screens.
        tvTextField.postDelayed(reveal, 350);
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.showSoftInput(tvTextField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onBoxMouseStatus() {
        updateTrackpadLabels();
    }

    private void updateTrackpadLabels() {
        if (trackpad == null) {
            return;
        }
        if (service != null && service.isBoxActive()) {
            boolean mouseConnected = service.isBoxMouseConnected();
            if (mouseConnected) {
                trackpad.setLabels("BOX MOUSE", getString(R.string.trackpad_hint), "Tap to click · two fingers scroll");
            } else {
                trackpad.setLabels("BOX MOUSE", "Bluetooth Mouse Not Connected", "Tap to pair with Android TV box");
            }
        } else {
            trackpad.setLabels("TRACKPAD", getString(R.string.trackpad_hint), "Tap to click");
        }
    }

    /** A trackpad/click tap clicks the on-screen pointer or opens pair dialog if unlinked. */
    private void pointerClickPressed() {
        if (service == null) {
            return;
        }
        clickHaptic(trackpad);
        if (service.isBoxActive() && !service.isBoxMouseConnected()) {
            String savedMac = SecureStore.getBoxBtMac(this);
            if (savedMac != null && !savedMac.isEmpty() && service.getBoxMouse() != null) {
                toast("Connecting mouse to TV box...");
                service.getBoxMouse().autoConnect();
                return;
            }
            showBluetoothMousePairDialog();
            return;
        }
        service.sendClick();
    }

    @SuppressWarnings("MissingPermission")
    private void showBluetoothMousePairDialog() {
        ensureBluetoothPermission();
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null || !bt.isEnabled()) {
            toast("Bluetooth is turned off");
            return;
        }

        // Request discoverability immediately so Android TV sees "LG Remote Mouse" right away
        BoxBluetoothMouse.requestDiscoverable(this);

        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, "Pair Android TV Mouse");

        TextView info = Ui.text(this,
                "1. Keep TV on 'Searching for accessories'.\n"
                + "2. Tap your TV box below to initiate pairing.\n"
                + "3. Accept 'Pair' on your TV screen.",
                12, getColor(R.color.muted), Typeface.NORMAL);
        info.setPadding(0, dp(4), 0, dp(12));
        panel.addView(info);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = Ui.column(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        TextView status = Ui.text(this, "Scanning for nearby devices...", 12, getColor(R.color.muted), Typeface.NORMAL);
        status.setPadding(dp(4), dp(4), dp(4), dp(8));
        list.addView(status);

        List<BluetoothDevice> deviceList = new ArrayList<>();

        Runnable[] renderItems = new Runnable[1];
        renderItems[0] = () -> {
            list.removeAllViews();
            if (deviceList.isEmpty()) {
                list.addView(status);
                return;
            }
            for (BluetoothDevice dev : deviceList) {
                int bond = BluetoothDevice.BOND_NONE;
                String name = null;
                try {
                    bond = dev.getBondState();
                    name = dev.getName();
                } catch (SecurityException ignored) {
                }
                boolean isBonded = bond == BluetoothDevice.BOND_BONDED;
                String title = (name != null ? name : dev.getAddress());
                String sub = isBonded ? "Paired" : dev.getAddress();
                View row = buildOptionCard(isBonded ? R.drawable.ic_tv : R.drawable.ic_input,
                        title, sub, isBonded, () -> {
                            if (isBonded) {
                                if (service != null && service.getBoxMouse() != null) {
                                    service.getBoxMouse().connectWithRetry(dev);
                                    toast("Connecting mouse to " + title + "...");
                                }
                                dialog.dismiss();
                            } else {
                                try {
                                    SecureStore.setBoxBtMac(MainActivity.this, dev.getAddress());
                                    dev.createBond();
                                    toast("Pairing request sent! Accept prompt on your TV screen...");
                                } catch (SecurityException e) {
                                    toast("Pairing failed: " + e.getMessage());
                                }
                            }
                        });
                list.addView(row);
            }
        };

        // Pre-populate already bonded devices
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                for (BluetoothDevice dev : bt.getBondedDevices()) {
                    if (dev != null && !deviceList.contains(dev)) {
                        deviceList.add(dev);
                    }
                }
            } catch (SecurityException ignored) {
            }
        }
        renderItems[0].run();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null && !deviceList.contains(dev)) {
                        deviceList.add(dev);
                        renderItems[0].run();
                    }
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (dev != null && service != null && service.getBoxMouse() != null) {
                            String name = null;
                            try {
                                name = dev.getName();
                            } catch (SecurityException ignored) {
                            }
                            String displayName = (name != null ? name : dev.getAddress());
                            toast("Paired! Connecting mouse to " + displayName + "...");
                            service.getBoxMouse().connectWithRetry(dev);
                            dialog.dismiss();
                        }
                    } else {
                        renderItems[0].run();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver, filter);

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                bt.startDiscovery();
            } catch (SecurityException ignored) {
            }
        }

        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, dp(12), 0, 0);

        TextView settingsBtn = settingsRow("BT Settings", false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.rightMargin = dp(6);
        actions.addView(settingsBtn, sp);
        settingsBtn.setOnClickListener(v -> {
            clickHaptic(v);
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        });

        TextView discBtn = settingsRow("Discoverable", false);
        LinearLayout.LayoutParams dpLayout = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dpLayout.leftMargin = dp(6);
        actions.addView(discBtn, dpLayout);
        discBtn.setOnClickListener(v -> {
            clickHaptic(v);
            BoxBluetoothMouse.requestDiscoverable(this);
        });

        panel.addView(actions);

        dialog.setOnDismissListener(d -> {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            try {
                bt.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
        });

        showSheet(dialog, panel);
    }

    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), 102);
            }
        }
    }

    private void sendButton(String name) {
        if (service == null) {
            toast(R.string.tv_not_ready);
            return;
        }
        int mode = SecureStore.getDeviceMode(this);
        if (mode == SecureStore.MODE_ANDROID_TV) {
            service.activeRemote().sendNavigation(name, this::onRemoteAction);
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

    private void sendButtonLong(String name) {
        if (service == null) {
            toast(R.string.tv_not_ready);
            return;
        }
        int mode = SecureStore.getDeviceMode(this);
        if (mode == SecureStore.MODE_ANDROID_TV) {
            service.activeRemote().sendNavigationLong(name, this::onRemoteAction);
            return;
        }
        if ("VOLUMEUP".equals(name) || "VOLUMEDOWN".equals(name) || "MUTE".equals(name)) {
            sendButton(name);
            return;
        }
        service.activeRemote().sendNavigationLong(name, this::onRemoteAction);
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

    private void lgHomePressed() {
        if (service == null || !service.isConnected()) {
            toast(R.string.tv_not_ready);
            return;
        }
        if (service.sendLgButton("HOME")) {
            toast(R.string.lg_home_sent);
        } else {
            toast(R.string.tv_not_ready);
        }
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
        sendTvText(enter, false);
    }

    /** Sends pending text after a short typing pause, with no Enter press. */
    private void autoSendText() {
        if (remoteView == null || remoteView.getVisibility() != View.VISIBLE
                || !hasWindowFocus()) {
            return;
        }
        sendTvText(false, true);
    }

    private void scheduleAutoSend() {
        main.removeCallbacks(autoSendRunnable);
        if (changingTvText || tvTextField == null) {
            return;
        }
        if (tvTextField.getText().toString().equals(sentText)) {
            return;
        }
        main.postDelayed(autoSendRunnable, AUTO_SEND_DELAY_MS);
    }

    private void sendTvText(boolean enter, boolean automatic) {
        if (tvTextField == null) {
            return;
        }
        if (automatic) {
            main.removeCallbacks(autoSendRunnable);
        }
        if (textSendInFlight) {
            if (automatic) {
                scheduleAutoSend();
            }
            return;
        }
        if (service == null || !service.isConnected()) {
            if (!automatic) {
                toast(R.string.tv_not_ready);
            }
            return;
        }
        boolean box = service.isBoxActive();
        if (box && !AndroidTvRemote.isPaired(this)) {
            if (!automatic) {
                showBoxSetupPrompt();
            }
            return;
        }
        if (automatic) {
            boolean focused = box ? service.isBoxTextFieldFocused()
                    : service.isTextFieldFocused();
            if (!focused) {
                return;
            }
        }
        RemoteController remote = service.activeRemote();
        String current = tvTextField.getText().toString();
        if (current.isEmpty() && sentText.isEmpty()) {
            if (enter) {
                sendRemoteEnter(remote);
            } else if (!automatic) {
                toast(R.string.tv_keyboard_empty);
            }
            return;
        }
        if (current.equals(sentText)) {
            if (enter) {
                sendRemoteEnter(remote);
            }
            return;
        }
        textSendInFlight = true;
        syncTextStep(remote, box, textSession, automatic, enter);
    }

    /**
     * Sends only the difference between the draft and what the active screen already has. Keeping
     * the draft intact while the keyboard is composing avoids clearing a character that the IME
     * has not committed yet.
     */
    private void syncTextStep(RemoteController remote, boolean box, int session,
                              boolean automatic, boolean enter) {
        if (session != textSession) {
            return;
        }
        if (tvTextField == null) {
            textSendInFlight = false;
            return;
        }
        String current = tvTextField.getText().toString();
        if (current.equals(sentText)) {
            finishTextSync(remote, box, session, enter);
            return;
        }
        if (TextDiff.deleteCount(current, sentText) > 0) {
            remote.sendDelete((ok, error) -> {
                if (session != textSession) {
                    return;
                }
                if (!ok) {
                    failTextSync(automatic, error);
                    return;
                }
                sentText = TextDiff.dropLastCodePoint(sentText);
                syncTextStep(remote, box, session, automatic, enter);
            });
            return;
        }
        String added = TextDiff.appendText(current, sentText);
        if (added.isEmpty()) {
            finishTextSync(remote, box, session, enter);
            return;
        }
        remote.sendText(added, (ok, error) -> {
            if (session != textSession) {
                return;
            }
            if (!ok) {
                failTextSync(automatic, error);
                return;
            }
            sentText = sentText + added;
            syncTextStep(remote, box, session, automatic, enter);
        });
    }

    private void finishTextSync(RemoteController remote, boolean box, int session, boolean enter) {
        if (session != textSession) {
            return;
        }
        textSendInFlight = false;
        if (tvTextField == null) {
            return;
        }
        if (!tvTextField.getText().toString().equals(sentText)) {
            // More letters arrived while the previous request was in flight.
            scheduleAutoSend();
            return;
        }
        if (enter) {
            if (box) {
                main.postDelayed(() -> sendRemoteEnter(remote), 260);
            } else {
                sendRemoteEnter(remote);
            }
        }
        // Keep the draft in the field. This lets the IME keep composing and lets the next
        // backspace delete locally before it reaches the device, without losing characters.
    }

    private void failTextSync(boolean automatic, String error) {
        textSendInFlight = false;
        if (!automatic) {
            toast(error == null ? getString(R.string.tv_keyboard_failed) : error);
        }
    }

    private void resetTextSession() {
        textSession++;
        main.removeCallbacks(autoSendRunnable);
        textSendInFlight = false;
        sentText = "";
        if (tvTextField != null && tvTextField.length() > 0) {
            replaceTvText("");
        }
    }

    private void sendRemoteEnter(RemoteController remote) {
        remote.sendEnter((ok, error) -> {
            if (!ok) {
                toast(error == null ? getString(R.string.tv_keyboard_failed) : error);
            }
        });
    }

    /** The delete button follows the same rule as the keyboard: draft first, then the device. */
    private void deleteDraftOrRemote() {
        if (tvTextField != null && tvTextField.isEnabled() && tvTextField.length() > 0) {
            int start = tvTextField.getSelectionStart();
            int end = tvTextField.getSelectionEnd();
            if (start >= 0 && end >= 0) {
                if (start != end) {
                    tvTextField.getText().delete(Math.min(start, end), Math.max(start, end));
                } else if (start > 0) {
                    tvTextField.getText().delete(start - 1, start);
                }
            }
            return;
        }
        deleteTvText();
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
        service.activeRemote().sendDelete((ok, error) -> {
            if (!ok) {
                toast(error == null ? getString(R.string.tv_keyboard_failed) : error);
            }
        });
    }

    /** Backspace on an empty draft: flush pending deletes or reach past what this session sent. */
    private void backspaceEmptyDraft() {
        if (!sentText.isEmpty()) {
            main.removeCallbacks(autoSendRunnable);
            sendTvText(false, false);
            return;
        }
        deleteTvText();
    }

    /** Replaces the field without treating the edit as typing (and re-triggering auto-send). */
    private void replaceTvText(String text) {
        if (tvTextField == null) {
            return;
        }
        changingTvText = true;
        try {
            tvTextField.setText(text == null ? "" : text);
            tvTextField.setSelection(tvTextField.length());
        } finally {
            changingTvText = false;
        }
    }

    private void voiceDictationPressed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        clickHaptic(tvTextField);
        if (inAppRecognizing) {
            stopInAppRecognition();
            return;
        }
        startInAppRecognition(null);
    }

    private void startInAppRecognition(View triggerButton) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            try {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening));
                startActivityForResult(intent, REQUEST_SPEECH_DICTATION);
            } catch (ActivityNotFoundException e) {
                toast("Speech recognition is not available on this phone.");
            }
            return;
        }

        if (inAppRecognizer != null) {
            try {
                inAppRecognizer.destroy();
            } catch (Exception ignored) {
            }
        }

        inAppRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        inAppRecognizing = true;
        showVoiceActiveHud(0, "Listening...");
        if (tvTextField != null) {
            tvTextField.setHint(R.string.voice_listening);
        }

        inAppRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "SpeechRecognizer: ready for speech");
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer: beginning of speech");
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer: end of speech");
            }

            @Override
            public void onError(int error) {
                Log.w(TAG, "SpeechRecognizer error: " + error);
                inAppRecognizing = false;
                dismissVoiceActiveHud();
                if (triggerButton != null) {
                    restoreVoiceButton(triggerButton);
                }
                if (tvTextField != null) {
                    tvTextField.setHint(R.string.tv_keyboard_hint);
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    toast("No speech detected. Please speak closer to phone.");
                }
            }

            @Override
            public void onResults(Bundle results) {
                inAppRecognizing = false;
                dismissVoiceActiveHud();
                if (triggerButton != null) {
                    restoreVoiceButton(triggerButton);
                }
                if (tvTextField != null) {
                    tvTextField.setHint(R.string.tv_keyboard_hint);
                }
                ArrayList<String> matches = results != null
                        ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                if (matches != null && !matches.isEmpty()) {
                    String spoken = matches.get(0);
                    Log.d(TAG, "SpeechRecognizer results: " + spoken);
                    boolean box = service != null && service.isBoxActive();
                    if (!box) {
                        String fgApp = service != null ? service.getForegroundApp() : null;
                        if (fgApp != null && fgApp.toLowerCase().contains("youtube")) {
                            try {
                                String queryUrl = "https://www.youtube.com/results?search_query="
                                        + java.net.URLEncoder.encode(spoken, "UTF-8");
                                service.launchApp("youtube.leanback.v4", queryUrl);
                                toast("Searching YouTube for \"" + spoken + "\"");
                                return;
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to launch YouTube search", e);
                            }
                        }
                    } else {
                        String boxApp = AndroidTvRemote.getLastBoxApp();
                        if (boxApp != null && boxApp.toLowerCase().contains("youtube")) {
                            try {
                                String queryUrl = "https://www.youtube.com/results?search_query="
                                        + java.net.URLEncoder.encode(spoken, "UTF-8");
                                AndroidTvRemote.openApp(queryUrl);
                                toast("Searching YouTube for \"" + spoken + "\"");
                                return;
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to launch YouTube search on box", e);
                            }
                        }
                    }
                    if (tvTextField != null) {
                        setTvKeyboardBarVisible(true);
                        tvTextField.setText(spoken);
                        tvTextField.setSelection(spoken.length());
                        sendTvText(true);
                    }
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults != null
                        ? partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                if (matches != null && !matches.isEmpty() && tvTextField != null) {
                    tvTextField.setText(matches.get(0));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        inAppRecognizer.startListening(intent);
    }

    private void stopInAppRecognition() {
        if (inAppRecognizer != null) {
            try {
                inAppRecognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
        inAppRecognizing = false;
        dismissVoiceActiveHud();
        if (tvTextField != null) {
            tvTextField.setHint(R.string.tv_keyboard_hint);
        }
    }

    private long voicePressDownTime;

    private void attachVoicePushToTalk(View button) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    voicePressDownTime = System.currentTimeMillis();
                    v.setPressed(true);
                    clickHaptic(v);
                    if (voiceStreamer.isRecording() || inAppRecognizing) {
                        stopVoicePushToTalk(v);
                    } else {
                        startVoicePushToTalk(v);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    long duration = System.currentTimeMillis() - voicePressDownTime;
                    if (duration >= 400) {
                        stopVoicePushToTalk(v);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stopVoicePushToTalk(v);
                    return true;
            }
            return false;
        });
    }

    private void startVoicePushToTalk(View button) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }

        voiceOriginalBackground = button.getBackground();
        button.setBackground(Ui.circle(this, getColor(R.color.accent), getColor(R.color.accent)));
        if (button instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) button;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ImageView) {
                    ((ImageView) child).setColorFilter(getColor(R.color.on_accent));
                }
            }
        }

        if (AndroidTvRemote.isVoiceSessionActive()) {
            voiceStreamer.start(new VoiceStreamer.Listener() {
                @Override
                public void onStarted() {
                    Log.d(TAG, "Voice: recording and streaming active");
                    showVoiceActiveHud(0, "Box Microphone");
                }

                @Override
                public void onStopped() {
                    restoreVoiceButton(button);
                    dismissVoiceActiveHud();
                }

                @Override
                public void onError(String message) {
                    restoreVoiceButton(button);
                    dismissVoiceActiveHud();
                    toast(message);
                }
            });
            return;
        }

        startInAppRecognition(button);
    }

    private void stopVoicePushToTalk(View button) {
        if (voiceStreamer.isRecording()) {
            voiceStreamer.stop();
        }
        if (inAppRecognizing) {
            stopInAppRecognition();
        }
        restoreVoiceButton(button);
        dismissVoiceActiveHud();
    }

    private void restoreVoiceButton(View button) {
        runOnUiThread(() -> {
            if (voiceOriginalBackground != null) {
                button.setBackground(voiceOriginalBackground);
            }
            if (button instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) button;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child instanceof ImageView) {
                        ((ImageView) child).setColorFilter(getColor(R.color.text));
                    }
                }
            }
        });
    }

    @Override
    public void onIncomingVoiceSession(int sessionId, String packageName) {
        Log.d(TAG, "MainActivity: onIncomingVoiceSession id=" + sessionId + " pkg=" + packageName);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        clickHaptic(remoteView);
        showVoiceActiveHud(sessionId, packageName);
        voiceStreamer.startForIncomingSession(sessionId, new VoiceStreamer.Listener() {
            @Override
            public void onStarted() {
                Log.d(TAG, "Auto Voice: recording and streaming active for TV (" + packageName + ")");
            }

            @Override
            public void onStopped() {
                dismissVoiceActiveHud();
            }

            @Override
            public void onError(String message) {
                dismissVoiceActiveHud();
                toast(message);
            }
        });
    }

    @Override
    public void onVoiceSessionEnded(int sessionId) {
        Log.d(TAG, "MainActivity: onVoiceSessionEnded id=" + sessionId);
        if (voiceStreamer.isRecording()) {
            voiceStreamer.stop();
        }
        dismissVoiceActiveHud();
    }

    private void showVoiceActiveHud(int sessionId, String packageName) {
        runOnUiThread(() -> {
            String app = (packageName != null && !packageName.isEmpty()) ? packageName : "TV";
            if (app.contains(".")) {
                app = app.substring(app.lastIndexOf('.') + 1);
            }
            tintBanner(getColor(R.color.accent));
            showBanner("🎙️ " + getString(R.string.voice_listening) + " · " + app,
                    getString(R.string.voice_done), () -> {
                        clickHaptic(banner);
                        if (voiceStreamer.isRecording()) {
                            voiceStreamer.stop();
                        }
                        dismissVoiceActiveHud();
                    });
        });
    }

    private void dismissVoiceActiveHud() {
        runOnUiThread(() -> {
            hideBanner();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("Microphone enabled. Hold the Voice button to speak.");
            } else {
                toast(getString(R.string.voice_permission_needed));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SPEECH_DICTATION && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String spokenText = matches.get(0);
                if (tvTextField != null) {
                    tvTextField.setText(spokenText);
                    tvTextField.setSelection(spokenText.length());
                    sendTvText();
                }
            }
        }
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
        if (service != null) {
            // The old certificate/session must not stay live while a new pairing code is issued.
            service.stopBoxSession();
        }
        discoverBoxForPairing();
    }

    private void discoverBoxForPairing() {
        showBoxProgress(R.string.box_finding);
        AndroidTvRemote.discoverAll(this, boxes -> {
            dismissBoxProgress();
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (boxes == null || boxes.isEmpty()) {
                toast(getString(R.string.box_not_found));
                showBoxIpDialog();
                return;
            }

            AndroidTvRemote.DiscoveredBox matched = findBoxMatchingTvInput(boxes);
            if (matched != null) {
                Log.d(TAG, "Pairing targeting box matching TV input: " + matched.name + " (" + matched.host + ")");
                startBoxPairing(matched.host, false);
                return;
            }

            if (boxes.size() == 1 && SecureStore.getDeviceMode(this) == SecureStore.MODE_ANDROID_TV) {
                startBoxPairing(boxes.get(0).host, false);
                return;
            }

            showBoxSelectionDialog(boxes);
        });
    }

    private void showBoxSelectionDialog(List<AndroidTvRemote.DiscoveredBox> boxes) {
        CharSequence[] items = new CharSequence[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            AndroidTvRemote.DiscoveredBox b = boxes.get(i);
            String matched = getMatchingTvInputLabel(b);
            String label = (b.name != null ? b.name : "Android TV") + " (" + b.host + ")";
            if (matched != null) {
                label += " [TV Input: " + matched + "]";
            }
            items[i] = label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.box_setup_title)
                .setItems(items, (dialog, which) -> {
                    startBoxPairing(boxes.get(which).host, false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
        } else {
            String tvIp = service != null ? service.getIp() : SecureStore.getSavedIp(this);
            if (tvIp != null && tvIp.contains(".")) {
                String prefix = tvIp.substring(0, tvIp.lastIndexOf('.') + 1);
                address.setText(prefix);
                address.setSelection(address.length());
            }
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
                int mode = SecureStore.getDeviceMode(MainActivity.this);
                if (mode == SecureStore.MODE_ANDROID_TV) {
                    RemoteService.start(MainActivity.this, null, null);
                    showRemote();
                } else if (service != null && service.isConnected()) {
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
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, getString(R.string.box_section));

        panel.addView(buildOptionCard(R.drawable.ic_power, getString(R.string.wake_box_cec),
                "Switch TV input to Android TV box", false, () -> {
                    dialog.dismiss();
                    settings.dismiss();
                    wakeBoxCec(true);
                }));

        boolean mouseConnected = service != null && service.isBoxMouseConnected();
        String mouseSub = mouseConnected
                ? "Connected (" + service.getBoxMouse().getConnectedDeviceName() + ")"
                : "Required for native cursor on TV box";
        panel.addView(buildOptionCard(R.drawable.ic_input, "Pair / Connect Bluetooth Mouse",
                mouseSub, mouseConnected, () -> {
                    dialog.dismiss();
                    settings.dismiss();
                    showBluetoothMousePairDialog();
                }));

        panel.addView(buildOptionCard(R.drawable.ic_gear, getString(R.string.box_pair_again),
                "Re-pair network remote over TLS", false, () -> {
                    dialog.dismiss();
                    settings.dismiss();
                    beginBoxPairing();
                }));

        panel.addView(buildOptionCard(R.drawable.ic_close, getString(R.string.forget_pairing),
                "Clear paired Android TV configuration", false, () -> {
                    dialog.dismiss();
                    settings.dismiss();
                    confirmForgetBox();
                }));

        showSheet(dialog, panel);
    }

    private void confirmForgetBox() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, getString(R.string.box_forget_title));

        TextView msg = Ui.text(this, getString(R.string.box_forget_body), 13, getColor(R.color.muted), Typeface.NORMAL);
        msg.setPadding(0, dp(4), 0, dp(14));
        panel.addView(msg);

        TextView forgetBtn = settingsRow(getString(R.string.box_forget_confirm), true);
        panel.addView(forgetBtn);
        forgetBtn.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            cancelBoxPairing();
            if (service != null) {
                service.stopBoxSession();
            }
            AndroidTvRemote.forget(this);
            renderConnection();
            toast(R.string.box_forgotten);
        });

        showSheet(dialog, panel);
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
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, getString(R.string.box_input_title));
        String currentId = SecureStore.getBoxInputId(this);

        for (LgTvClient.TvInput in : inputs) {
            boolean selected = currentId != null && currentId.equals(in.id);
            panel.addView(buildOptionCard(R.drawable.ic_input, in.label, in.id, selected, () -> {
                dialog.dismiss();
                service.linkBoxInput(in.id);
                toast(getString(R.string.box_input_linked, in.label));
                if (row != null) {
                    row.setText(boxInputLabel());
                }
            }));
        }

        panel.addView(buildOptionCard(R.drawable.ic_close, getString(R.string.box_input_clear),
                "Unlink box from TV HDMI inputs", false, () -> {
                    dialog.dismiss();
                    SecureStore.setBoxInput(this, "", "");
                    toast(R.string.box_input_cleared);
                    if (row != null) {
                        row.setText(boxInputLabel());
                    }
                }));

        showSheet(dialog, panel);
    }

    private void powerPressed() {
        int mode = SecureStore.getDeviceMode(this);
        if (mode == SecureStore.MODE_ANDROID_TV) {
            if (service != null) {
                service.activeRemote().sendNavigation("POWER", this::onRemoteAction);
            }
            return;
        }
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

    private void wakeBoxCec(boolean explicit) {
        String boxHost = SecureStore.getBoxHost(this);
        String boxMac = SecureStore.getBoxMac(this);
        boolean hasBoxMac = WakeOnLan.isValidMac(boxMac);
        if (hasBoxMac) {
            WakeOnLan.send(boxMac, boxHost);
        }
        if (!AndroidTvRemote.isPaired(this)) {
            if (explicit) {
                if (hasBoxMac) {
                    toast(R.string.box_wake_wol_sent);
                } else {
                    toast(R.string.box_not_paired);
                }
            }
            return;
        }
        if (explicit) {
            toast(R.string.wake_box_sending);
        }
        AndroidTvRemote.wakeBox(this, (ok, error) -> {
            if (ok) {
                toast(R.string.wake_box_sent);
                retry();
            } else if (explicit) {
                if (hasBoxMac) {
                    toast(R.string.box_wake_wol_sent);
                } else {
                    toast(error == null ? getString(R.string.wake_box_failed) : error);
                }
            }
        });
    }

    private void wakeTv() {
        String ip = service != null ? service.getIp() : SecureStore.getSavedIp(this);
        String mac = SecureStore.getMac(this);
        boolean hasMac = WakeOnLan.isValidMac(mac);
        boolean hasBox = AndroidTvRemote.isPaired(this);
        String boxMac = SecureStore.getBoxMac(this);
        boolean hasBoxMac = WakeOnLan.isValidMac(boxMac);
        if (!hasMac && !hasBox && !hasBoxMac) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.wake)
                    .setMessage(R.string.no_mac)
                    .setPositiveButton(R.string.wake_settings, (dialog, which) -> showSettings())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (hasMac) {
            WakeOnLan.send(mac, ip);
            toast(R.string.wake_sent);
        } else {
            toast(R.string.wake_box_sending);
        }
        if (hasBox || hasBoxMac) {
            wakeBoxCec(false);
        }
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

        content.addView(Ui.label(this, getString(R.string.choose_remote_mode)));
        LinearLayout modeRow = Ui.row(this);
        LinearLayout.LayoutParams modeRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        modeRowParams.topMargin = dp(8);
        modeRowParams.bottomMargin = dp(16);
        content.addView(modeRow, modeRowParams);

        int currentMode = SecureStore.getDeviceMode(this);
        TextView optBoth = pill(getString(R.string.mode_unified), currentMode == SecureStore.MODE_AUTO);
        TextView optBox = pill(getString(R.string.mode_android_tv), currentMode == SecureStore.MODE_ANDROID_TV);
        TextView optLg = pill(getString(R.string.mode_lg_tv), currentMode == SecureStore.MODE_LG_TV);
        modeRow.addView(optBoth, weighted(1f, 38, 0, 4));
        modeRow.addView(optBox, weighted(1f, 38, 0, 4));
        modeRow.addView(optLg, weighted(1f, 38, 0, 0));

        optBoth.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            setDeviceModeAndRefresh(SecureStore.MODE_AUTO);
        });
        optBox.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            setDeviceModeAndRefresh(SecureStore.MODE_ANDROID_TV);
        });
        optLg.setOnClickListener(v -> {
            clickHaptic(v);
            dialog.dismiss();
            setDeviceModeAndRefresh(SecureStore.MODE_LG_TV);
        });

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

        content.addView(Ui.label(this, getString(R.string.box_wake_mac)));
        LinearLayout boxMacRow = Ui.row(this);
        LinearLayout.LayoutParams boxMacRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxMacRowParams.topMargin = dp(8);
        content.addView(boxMacRow, boxMacRowParams);
        EditText boxMacField = new EditText(this);
        boxMacField.setHint(R.string.wake_mac_hint);
        boxMacField.setHintTextColor(getColor(R.color.muted));
        boxMacField.setTextColor(getColor(R.color.text));
        boxMacField.setTextSize(13);
        boxMacField.setSingleLine(true);
        boxMacField.setInputType(InputType.TYPE_CLASS_TEXT);
        boxMacField.setPadding(dp(12), 0, dp(12), 0);
        boxMacField.setBackground(Ui.round(this, getColor(R.color.surface_2), 12,
                getColor(R.color.stroke), 1));
        boxMacField.setText(SecureStore.getBoxMac(this));
        boxMacRow.addView(boxMacField, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView saveBoxMac = pill(getString(R.string.save), false);
        LinearLayout.LayoutParams saveBoxMacParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        saveBoxMacParams.leftMargin = dp(8);
        boxMacRow.addView(saveBoxMac, saveBoxMacParams);
        saveBoxMac.setOnClickListener(v -> {
            clickHaptic(v);
            String value = boxMacField.getText().toString().trim();
            if (value.isEmpty()) {
                SecureStore.setBoxMac(this, "");
                toast(R.string.mac_saved);
                return;
            }
            String normalized = WakeOnLan.normalize(value);
            if (normalized == null) {
                boxMacField.setError(getString(R.string.mac_invalid));
                return;
            }
            SecureStore.setBoxMac(this, normalized);
            boxMacField.setText(normalized);
            toast(R.string.mac_saved);
        });
        TextView boxMacHelp = Ui.text(this, getString(R.string.box_wake_mac_help), 11,
                getColor(R.color.muted), Typeface.NORMAL);
        boxMacHelp.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams boxMacHelpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxMacHelpParams.topMargin = dp(7);
        content.addView(boxMacHelp, boxMacHelpParams);

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

    private void showPairingPinDialog() {
        if (service != null) {
            showPairingPinDialog(service.isPinSubmitting(), service.getPinError());
        }
    }

    private void showPairingPinDialog(boolean submitting, String error) {
        if (isFinishing() || isDestroyed() || service == null
                || !service.isPairingPinRequired()) {
            return;
        }
        if (pairingPinDialog != null && pairingPinDialog.isShowing()) {
            renderPairingPinDialog(submitting, error);
            if (pairingPinField != null && !submitting) {
                pairingPinField.requestFocus();
            }
            return;
        }

        Dialog dialog = new Dialog(this);
        pairingPinDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        // Back must not silently drop the only way to enter the TV's PIN. The sheet has
        // an explicit Cancel action, which also ends the pairing session properly.
        dialog.setCancelable(false);

        LinearLayout panel = Ui.column(this);
        panel.setPadding(dp(24), dp(22), dp(24), dp(22));
        panel.setBackground(Ui.round(this, getColor(R.color.surface), 28,
                getColor(R.color.stroke), 1));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.round(this, getColor(R.color.accent_dim), 16,
                getColor(R.color.stroke), 1));
        badge.addView(Ui.icon(this, R.drawable.ic_pin, getColor(R.color.accent), 24),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        LinearLayout.LayoutParams badgeParams = Ui.dpParams(this, 48, 48);
        badgeParams.bottomMargin = dp(16);
        panel.addView(badge, badgeParams);

        TextView title = Ui.text(this, getString(R.string.pairing_pin_title), 24,
                getColor(R.color.text), Typeface.BOLD);
        panel.addView(title);

        TextView body = Ui.text(this, getString(R.string.pairing_pin_body), 13,
                getColor(R.color.muted), Typeface.NORMAL);
        body.setLineSpacing(dp(2), 1.3f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(9);
        panel.addView(body, bodyParams);

        TextView device = Ui.text(this, service.displayName(), 11,
                getColor(R.color.accent), Typeface.BOLD);
        device.setPadding(dp(11), dp(5), dp(11), dp(5));
        device.setBackground(Ui.round(this, getColor(R.color.accent_dim), 99,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceParams.topMargin = dp(15);
        panel.addView(device, deviceParams);

        pairingPinField = new EditText(this);
        pairingPinField.setHint(R.string.pairing_pin_hint);
        pairingPinField.setHintTextColor(getColor(R.color.muted));
        pairingPinField.setGravity(Gravity.CENTER);
        pairingPinField.setTextColor(getColor(R.color.text));
        pairingPinField.setTextSize(26);
        pairingPinField.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pairingPinField.setLetterSpacing(0.22f);
        pairingPinField.setSingleLine(true);
        pairingPinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairingPinField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        pairingPinField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pairingPinField.setPadding(dp(14), dp(12), dp(14), dp(12));
        pairingPinField.setBackground(Ui.round(this, getColor(R.color.surface_2), 18,
                getColor(R.color.accent), 1.5f));
        pairingPinField.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        fieldParams.topMargin = dp(18);
        panel.addView(pairingPinField, fieldParams);

        pairingPinError = Ui.text(this, "", 12, getColor(R.color.danger), Typeface.NORMAL);
        pairingPinError.setLineSpacing(0f, 1.25f);
        pairingPinError.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.topMargin = dp(9);
        panel.addView(pairingPinError, errorParams);

        TextView hint = Ui.text(this, getString(R.string.pairing_pin_hint_help), 11,
                getColor(R.color.muted), Typeface.NORMAL);
        hint.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(10);
        panel.addView(hint, hintParams);

        LinearLayout actions = Ui.row(this);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        actionsParams.topMargin = dp(22);
        panel.addView(actions, actionsParams);

        TextView cancel = pill(getString(R.string.cancel), false);
        cancel.setOnClickListener(v -> {
            clickHaptic(v);
            if (service != null) {
                service.cancelPairing();
            }
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1f));

        pairingPinAction = pill(getString(R.string.pairing_pin_confirm), true);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actionParams.leftMargin = dp(10);
        actions.addView(pairingPinAction, actionParams);
        pairingPinAction.setOnClickListener(v -> {
            clickHaptic(v);
            submitPinFromDialog();
        });

        pairingPinField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!pairingPinSubmitting && pairingPinAction != null) {
                    boolean ready = s != null && s.length() >= 4;
                    pairingPinAction.setEnabled(ready);
                    pairingPinAction.setAlpha(ready ? 1f : 0.45f);
                }
                if (pairingPinError != null && pairingPinError.getVisibility() == View.VISIBLE) {
                    pairingPinError.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        pairingPinField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPinFromDialog();
                return true;
            }
            return false;
        });

        dialog.setOnDismissListener(d -> {
            pairingPinDialog = null;
            pairingPinField = null;
            pairingPinError = null;
            pairingPinAction = null;
            pairingPinSubmitting = false;
        });

        // The PIN sheet is tall, and in landscape the IME leaves very little room.
        // A full-height transparent window plus a scrollable card keeps the field and
        // buttons reachable on short screens instead of clipping the bottom action.
        FrameLayout root = new FrameLayout(this);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        root.addView(scroll, scrollParams);
        dialog.setContentView(root);

        int width = Math.min(dp(452),
                getResources().getDisplayMetrics().widthPixels - dp(24));
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.68f);
            window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        dialog.show();
        renderPairingPinDialog(submitting, error);
        pairingPinField.postDelayed(this::showPinKeyboard, 180);
    }

    private void renderPairingPinDialog(boolean submitting, String error) {
        if (pairingPinDialog == null || !pairingPinDialog.isShowing()) {
            return;
        }
        pairingPinSubmitting = submitting;
        if (pairingPinField != null) {
            pairingPinField.setEnabled(!submitting);
            pairingPinField.setAlpha(submitting ? 0.65f : 1f);
        }
        if (pairingPinAction != null) {
            int length = pairingPinField == null || pairingPinField.getText() == null
                    ? 0 : pairingPinField.getText().length();
            boolean ready = !submitting && length >= 4;
            pairingPinAction.setText(submitting
                    ? R.string.pairing_pin_checking : R.string.pairing_pin_confirm);
            pairingPinAction.setEnabled(ready);
            pairingPinAction.setAlpha(ready ? 1f : 0.45f);
        }
        if (pairingPinError != null) {
            if (error != null && !error.isEmpty()) {
                // Clear first: the field's TextWatcher hides any visible error, and we
                // want the new message to stay on screen after the programmatic clear.
                if (!submitting && pairingPinField != null) {
                    pairingPinField.setText("");
                    pairingPinField.requestFocus();
                }
                pairingPinError.setText(error);
                pairingPinError.setVisibility(View.VISIBLE);
                if (!submitting && pairingPinField != null) {
                    pairingPinField.postDelayed(this::showPinKeyboard, 120);
                }
            } else if (!submitting) {
                pairingPinError.setVisibility(View.GONE);
            }
        }
    }

    private void showPinKeyboard() {
        if (pairingPinSubmitting || pairingPinField == null || pairingPinDialog == null
                || !pairingPinDialog.isShowing() || isFinishing() || isDestroyed()) {
            return;
        }
        pairingPinField.requestFocus();
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.showSoftInput(pairingPinField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void submitPinFromDialog() {
        if (service == null || pairingPinField == null || pairingPinSubmitting) {
            return;
        }
        String pin = pairingPinField.getText() == null
                ? "" : pairingPinField.getText().toString().trim();
        if (!Protocol.isValidPairingPin(pin)) {
            if (pairingPinError != null) {
                pairingPinError.setText(R.string.pairing_pin_invalid);
                pairingPinError.setVisibility(View.VISIBLE);
            }
            pairingPinField.requestFocus();
            return;
        }
        hideKeyboard();
        renderPairingPinDialog(true, null);
        if (!service.submitPairingPin(pin)) {
            renderPairingPinDialog(false, service.getPinError());
        }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_help_title)
                .setMessage(R.string.setup_help)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showApps() {
        dismiss(appsDialog);
        Dialog dialog = new Dialog(this);
        appsDialog = dialog;
        LinearLayout panel = sheet(dialog, "Apps & Channels");
        appsStatus = Ui.text(this, "Loading installed apps...", 12,
                getColor(R.color.muted), Typeface.NORMAL);
        appsStatus.setGravity(Gravity.CENTER);
        panel.addView(appsStatus);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int height = Math.min(dp(460), getResources().getDisplayMetrics().heightPixels / 2);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        appsList = Ui.column(this);
        scroll.addView(appsList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll);

        populateApps(service != null ? service.getApps() : null);
        showSheet(dialog, panel);
        if (service != null && service.isConnected()) {
            service.requestApps();
        }
    }

    private void populateApps(List<LgTvClient.TvApp> lgApps) {
        if (appsList == null) {
            return;
        }
        appsList.removeAllViews();
        if (appsStatus != null) {
            appsStatus.setVisibility(View.GONE);
        }

        appsList.addView(buildAppCard(R.drawable.ic_home, null, getString(R.string.lg_home), "webOS Home Dashboard", false, () -> {
            dismiss(appsDialog);
            lgHomePressed();
        }));

        if (lgApps != null && !lgApps.isEmpty()) {
            for (LgTvClient.TvApp app : lgApps) {
                appsList.addView(buildAppCard(0, null, app.title, app.id, false, () -> {
                    dismiss(appsDialog);
                    if (service != null) {
                        service.launchApp(app.id);
                    }
                    toast(getString(R.string.opening_app, app.title));
                }));
            }
        }
    }

    private View buildAppCard(int iconRes, String letter, String title, String subtitle, boolean isBox, Runnable onClick) {
        return buildAppCard(iconRes, letter, title, subtitle, isBox, onClick, null);
    }

    private View buildAppCard(int iconRes, String letter, String title, String subtitle, boolean isBox, Runnable onClick, Runnable onLongClick) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(Ui.pressable(this, getColor(R.color.surface_2), 14,
                getColor(R.color.stroke), 1));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(6);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(Ui.circle(this, getColor(R.color.surface), getColor(R.color.stroke)));
        if (iconRes > 0) {
            ImageView icon = Ui.icon(this, iconRes, getColor(isBox ? R.color.accent : R.color.text), 18);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER);
            iconBox.addView(icon, iconParams);
        } else {
            String initial = letter != null && !letter.isEmpty() ? letter
                    : (title != null && !title.isEmpty() ? title.substring(0, 1).toUpperCase(Locale.US) : "A");
            TextView txt = Ui.text(this, initial, 13, getColor(R.color.accent), Typeface.BOLD);
            txt.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams txtParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            iconBox.addView(txt, txtParams);
        }
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout textCol = Ui.column(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(12);
        row.addView(textCol, textParams);

        TextView titleView = Ui.text(this, title != null ? title : "", 14f, getColor(R.color.text), Typeface.BOLD);
        textCol.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = Ui.text(this, subtitle, 11f, getColor(R.color.muted), Typeface.NORMAL);
            textCol.addView(subView);
        }

        row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 16));

        row.setOnClickListener(v -> {
            clickHaptic(v);
            onClick.run();
        });
        if (onLongClick != null) {
            row.setOnLongClickListener(v -> {
                clickHaptic(v);
                onLongClick.run();
                return true;
            });
        }
        return row;
    }

    private View buildInputCard(int iconRes, String title, String subtitle, boolean active, Runnable onClick) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Ui.pressable(this,
                active ? getColor(R.color.secondary_container) : getColor(R.color.surface_2),
                16,
                active ? getColor(R.color.accent) : getColor(R.color.stroke),
                active ? 1.5f : 1f));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(Ui.circle(this,
                active ? getColor(R.color.accent_dim) : getColor(R.color.surface),
                getColor(R.color.stroke)));
        ImageView icon = Ui.icon(this, iconRes,
                active ? getColor(R.color.accent) : getColor(R.color.text), 20);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        iconBox.addView(icon, iconParams);
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout textCol = Ui.column(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(12);
        row.addView(textCol, textParams);

        TextView titleView = Ui.text(this, title, 14.5f,
                active ? getColor(R.color.accent) : getColor(R.color.text), Typeface.BOLD);
        textCol.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = Ui.text(this, subtitle, 11.5f, getColor(R.color.muted), Typeface.NORMAL);
            textCol.addView(subView);
        }

        if (active) {
            TextView badge = Ui.text(this, "ACTIVE", 10f, getColor(R.color.on_accent), Typeface.BOLD);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(Ui.round(this, getColor(R.color.green), 99));
            row.addView(badge);
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_chevron, getColor(R.color.muted), 16));
        }

        row.setOnClickListener(v -> {
            clickHaptic(v);
            onClick.run();
        });
        return row;
    }

    private void showInputs() {
        dismiss(inputsDialog);
        Dialog dialog = new Dialog(this);
        inputsDialog = dialog;
        LinearLayout panel = sheet(dialog, getString(R.string.inputs_title));
        inputsStatus = Ui.text(this, getString(R.string.loading_inputs), 12,
                getColor(R.color.muted), Typeface.NORMAL);
        inputsStatus.setGravity(Gravity.CENTER);
        inputsStatus.setVisibility(View.GONE);
        panel.addView(inputsStatus);
        LinearLayout list = Ui.column(this);
        inputsList = list;
        panel.addView(list);
        populateInputs(service != null ? service.getInputs() : null);
        showSheet(dialog, panel);
        if (service != null && service.isConnected()) {
            service.requestInputs();
        }
    }

    private void populateInputs(List<LgTvClient.TvInput> inputs) {
        if (inputsList == null) {
            return;
        }
        inputsList.removeAllViews();

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LgTvClient.TvInput activeInput = service != null ? service.getActiveInput() : null;
        String fgApp = service != null ? service.getForegroundApp() : "";

        // 1. Live TV Tile
        boolean isLiveTv = fgApp != null && fgApp.contains("livetv");
        row.addView(buildInputCircleTile(R.drawable.ic_tv, "Live TV", isLiveTv, () -> {
            if (service != null) service.launchApp("com.webos.app.livetv");
            toast(getString(R.string.switching_input, "Live TV"));
            if (inputsDialog != null) inputsDialog.dismiss();
        }));

        // 2. HDMI Inputs
        if (inputs != null && !inputs.isEmpty()) {
            for (LgTvClient.TvInput input : inputs) {
                boolean isActive = activeInput != null && (
                        (input.id != null && input.id.equalsIgnoreCase(activeInput.id))
                        || (input.appId != null && input.appId.equalsIgnoreCase(activeInput.appId)));
                row.addView(buildInputCircleTile(R.drawable.ic_input, input.label, isActive, () -> {
                    if (service != null) service.switchInput(input.id);
                    toast(getString(R.string.switching_input, input.label));
                    if (inputsDialog != null) inputsDialog.dismiss();
                }));
            }
        } else {
            for (int i = 1; i <= 4; i++) {
                final int port = i;
                final String id = "HDMI_" + port;
                final String label = "HDMI " + port;
                boolean isActive = activeInput != null && id.equalsIgnoreCase(activeInput.id);
                row.addView(buildInputCircleTile(R.drawable.ic_input, label, isActive, () -> {
                    if (service != null) service.switchInput(id);
                    toast(getString(R.string.switching_input, label));
                    if (inputsDialog != null) inputsDialog.dismiss();
                }));
            }
        }

        // 3. LG Home Dashboard Tile
        boolean isHome = (fgApp == null || fgApp.isEmpty() || fgApp.contains("home"));
        row.addView(buildInputCircleTile(R.drawable.ic_home, "LG Home", isHome, () -> {
            lgHomePressed();
            if (inputsDialog != null) inputsDialog.dismiss();
        }));

        inputsList.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View buildInputCircleTile(int iconRes, String label, boolean active, Runnable onClick) {
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(8), dp(4), dp(8), dp(4));

        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(Ui.circle(this,
                active ? getColor(R.color.accent_dim) : getColor(R.color.surface_2),
                active ? getColor(R.color.accent) : getColor(R.color.stroke)));
        ImageView icon = Ui.icon(this, iconRes,
                active ? getColor(R.color.accent) : getColor(R.color.text), 26);
        circle.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        circle.setOnClickListener(v -> {
            clickHaptic(v);
            onClick.run();
        });

        col.addView(circle, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView tv = Ui.text(this, label, 11.5f,
                active ? getColor(R.color.accent) : getColor(R.color.muted), Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(76));
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        col.addView(tv, tp);

        return col;
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
        attachRepeat(view, action, 400, 120);
    }

    private void attachRepeat(View view, Runnable action, long initialDelayMs, long intervalMs) {
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
                                handler.postDelayed(this, intervalMs);
                            }
                        }
                    };
                    handler.postDelayed(tick[0], initialDelayMs);
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

    private void attachClickHold(View view) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] longFired = new boolean[1];
        final Runnable longRunnable = () -> {
            longFired[0] = true;
            if (haptics) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            if (service != null && service.isBoxActive()) {
                sendButtonLong("ENTER");
            }
        };
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!v.isEnabled()) return false;
                    v.setPressed(true);
                    longFired[0] = false;
                    if (haptics) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    if (service != null && service.isBoxActive() && service.isBoxMouseConnected()) {
                        service.sendMove(0, 0, true);
                    }
                    handler.postDelayed(longRunnable, 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(longRunnable);
                    if (service != null && service.isBoxActive() && service.isBoxMouseConnected()) {
                        service.sendMove(0, 0, false);
                    }
                    if (!longFired[0]) {
                        pointerClickPressed();
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (focus instanceof EditText) {
                Rect rect = new Rect();
                View target = (focus == tvTextField && tvKeyboardBar != null) ? tvKeyboardBar : focus;
                target.getGlobalVisibleRect(rect);
                if (!rect.contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    hideKeyboard();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            View focus = getCurrentFocus();
            IBinder token = focus != null ? focus.getWindowToken() : null;
            if (token == null && tvTextField != null) {
                token = tvTextField.getWindowToken();
            }
            if (token == null && remoteView != null) {
                token = remoteView.getWindowToken();
            }
            if (token == null && getWindow() != null && getWindow().getDecorView() != null) {
                token = getWindow().getDecorView().getWindowToken();
            }
            if (token != null) {
                manager.hideSoftInputFromWindow(token, 0);
            }
        }
        if (tvTextField != null && tvTextField.hasFocus()) {
            tvTextField.clearFocus();
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
