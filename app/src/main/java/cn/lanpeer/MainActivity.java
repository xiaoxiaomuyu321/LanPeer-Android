package cn.lanpeer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int CAPTURE = 10, CAMERA = 11, SCAN = 12, NOTIFICATIONS = 13;
    private static final int INK = Color.rgb(24, 47, 43), GREEN = Color.rgb(20, 108, 98);
    private PeerApp app;
    private LinearLayout root;
    private TextView status;
    private String screenKey = "";
    private Session shownSession;
    private Session promptedSession;
    private AlertDialog approval;
    private SurfaceViewRenderer renderer;
    private RtcEngine rendererEngine;
    private boolean resumed;
    private final Runnable uiListener = this::refresh;
    private final ArrayList<float[]> points = new ArrayList<>();
    private long touchStart;
    private boolean touchPortrait;
    private boolean streamActive;
    private float[] lastSentPoint;
    private long lastSentAt;
    private Session touchSession;
    private final Runnable holdTick = new Runnable() {
        @Override public void run() {
            if (touchSession == null || points.isEmpty() || touchSession.rtc == null) return;
            float[] latest = points.get(points.size() - 1);
            if (!streamActive) startStroke(touchSession, latest);
            else sendStroke(touchSession, "move", lastSentPoint, latest);
            app.main.postDelayed(this, 100);
        }
    };
    private boolean immersive;
    private FrameLayout videoPane;
    private FrameLayout videoStage;
    private LinearLayout viewerTools;
    private TextView metrics;
    private TextView showTools;
    private TextView controlHint;
    private int homeTab;
    private VideoQuality selectedQuality = VideoQuality.BALANCED;
    private final Runnable statsTicker = new Runnable() {
        @Override public void run() {
            if (!resumed || rendererEngine == null) return;
            rendererEngine.pollStats();
            if (metrics != null) metrics.setText(rendererEngine.liveStats);
            app.main.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (PeerApp) getApplication();
    }

    @Override public void onResume() {
        super.onResume(); resumed = true;
        app.listener = uiListener;
        refresh();
        if (immersive) applyImmersive();
    }

    @Override public void onStop() {
        resumed = false;
        if (app.listener == uiListener) app.listener = null;
        releaseRenderer(); screenKey = "";
        app.main.removeCallbacks(statsTicker);
        finishStroke();
        if (approval != null) { approval.dismiss(); approval = null; promptedSession = null; }
        super.onStop();
    }

    @Override public void onDestroy() {
        releaseRenderer();
        if (isFinishing() && app.session != null && !app.session.host && shownSession == app.session) app.disconnect();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (resumed && (app.session == null || app.session.host || app.session.rtc == null)) {
            screenKey = "";
            refresh();
        } else if (videoPane != null) videoPane.post(() -> fitVideo(videoPane));
    }

    private void refresh() {
        if (!resumed) return;
        Session session = app.session;
        getWindow().setFlags(session != null && !session.closed ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String key = session == null || session.closed ? "home:" + homeTab + ":" + ControlService.available() : session.host
                ? "host:" + (session.identity != null) + ":" + session.approved + ":" + ControlService.available()
                : "viewer:" + (session.rtc != null);
        if (!key.equals(screenKey) || shownSession != session) {
            releaseRenderer(); screenKey = key; shownSession = session;
            boolean liveViewer = session != null && !session.host && session.rtc != null;
            if (!liveViewer) {
                if (immersive) { immersive = false; applyImmersive(); }
            }
            root = column(); root.setBackgroundColor(liveViewer ? Color.BLACK : Color.rgb(244, 247, 245));
            int side = liveViewer ? 0 : dp(20), vertical = liveViewer ? 0 : dp(12);
            root.setPadding(side, vertical, side, vertical);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int left = immersive ? 0 : insets.getSystemWindowInsetLeft();
                int top = immersive ? 0 : insets.getSystemWindowInsetTop();
                int right = immersive ? 0 : insets.getSystemWindowInsetRight();
                int bottom = immersive ? 0 : insets.getSystemWindowInsetBottom();
                v.setPadding(side + left, vertical + top, side + right, vertical + bottom);
                return insets.consumeSystemWindowInsets();
            });
            setContentView(root);
            root.requestApplyInsets();
            if (!liveViewer) {
                LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
                TextView mark = label("●", 28); mark.setTextColor(GREEN); mark.setPadding(0, 0, dp(10), 0); header.addView(mark);
                LinearLayout branding = column();
                TextView title = label("局域互控", 23); title.setTypeface(null, Typeface.BOLD); branding.addView(title);
                branding.addView(label("LANPEER  ·  本地直连", 11));
                header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
                root.addView(header);
                status = label(app.status, 13); status.setTextColor(GREEN);
                status.setPadding(dp(12), dp(8), dp(12), dp(8));
                GradientDrawable statusBackground = new GradientDrawable();
                statusBackground.setColor(0xFFE3F1ED); statusBackground.setCornerRadius(dp(12));
                status.setBackground(statusBackground);
                LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
                statusLayout.topMargin = dp(10); statusLayout.bottomMargin = dp(12);
                root.addView(status, statusLayout);
            } else status = null;
            if (key.startsWith("home:")) home();
            else if (session.host) host(session);
            else viewer(session);
        }
        if (status != null) status.setText(app.status);
        if (controlHint != null) controlHint.setText(app.status);
        if (session != null && session.pending && promptedSession != session) {
            promptedSession = session;
            approval = new AlertDialog.Builder(this).setTitle("允许这台设备控制？")
                    .setMessage("局域网设备 " + session.link.remoteAddress() + " 请求查看屏幕并操作本机。仅在确认是你的设备后允许。")
                    .setPositiveButton("允许本次连接", (dialog, which) -> session.approve())
                    .setNegativeButton("拒绝并停止", (dialog, which) -> app.disconnect())
                    .setOnCancelListener(dialog -> app.disconnect()).create();
            approval.show();
        }
        if (approval != null && (session == null || !session.pending)) { approval.dismiss(); approval = null; }
    }

    private void home() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (homeTab == 0) dashboard(body);
        else if (homeTab == 1) devicesPage(body);
        else settingsPage(body);
        LinearLayout tabs = new LinearLayout(this); tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(0, dp(5), 0, dp(5));
        for (int i = 0; i < 3; i++) {
            final int target = i;
            TextView tab = label(new String[]{"连接", "历史设备", "设置"}[i], 14);
            tab.setGravity(Gravity.CENTER); tab.setMinHeight(dp(44));
            tab.setTypeface(null, i == homeTab ? Typeface.BOLD : Typeface.NORMAL);
            tab.setTextColor(i == homeTab ? GREEN : 0xFF63736E);
            tab.setOnClickListener(v -> { if (homeTab != target) { homeTab = target; screenKey = ""; refresh(); } });
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabs);
    }

    private void dashboard(LinearLayout body) {
        TextView section = label("选择当前角色", 19); section.setTypeface(null, Typeface.BOLD); body.addView(section);
        body.addView(label("两台安卓设备使用同一个安装包，随时切换控制方向。", 13));
        LinearLayout actions = body;
        if (getResources().getConfiguration().screenWidthDp >= 600) {
            actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setTag("wide");
            body.addView(actions);
        }
        LinearLayout share = card(actions);
        TextView shareIcon = label("▣  被控端", 15); shareIcon.setTextColor(GREEN); share.addView(shareIcon);
        share.addView(label("共享本机屏幕", 21));
        share.addView(label("显示配对二维码；每次连接都要在本机确认。", 14));
        share.addView(button("开始共享", this::requestHost));
        LinearLayout control = card(actions);
        TextView controlIcon = label("⌁  主控端", 15); controlIcon.setTextColor(GREEN); control.addView(controlIcon);
        control.addView(label("控制另一台设备", 21));
        control.addView(label("扫码连接更方便，也支持手动输入。", 14));
        control.addView(button("扫码连接", this::scan));
        control.addView(outlinedButton("手动连接", GREEN, this::manual));
        List<KnownDevices.Device> recent = new KnownDevices(this).list();
        TextView recentTitle = label("最近连接", 19); recentTitle.setTypeface(null, Typeface.BOLD); body.addView(recentTitle);
        if (recent.isEmpty()) {
            LinearLayout empty = card(body);
            empty.addView(label("还没有历史设备", 17));
            empty.addView(label("首次连接成功后，这里会记录设备地址与身份。临时连接码不会保存。", 13));
        } else {
            for (int i = 0; i < Math.min(2, recent.size()); i++) deviceCard(body, recent.get(i), false);
            body.addView(outlinedButton("查看全部历史设备", GREEN, () -> { homeTab = 1; screenKey = ""; refresh(); }));
        }
    }

    private void devicesPage(LinearLayout body) {
        TextView title = label("历史设备", 20); title.setTypeface(null, Typeface.BOLD); body.addView(title);
        body.addView(label("仅保存连接成功设备的地址和校验身份。重新连接时请输入被控端当前显示的 8 位连接码。", 13));
        List<KnownDevices.Device> devices = new KnownDevices(this).list();
        if (devices.isEmpty()) {
            LinearLayout empty = card(body);
            empty.addView(label("暂无已连接设备", 17));
            empty.addView(label("请先在连接页扫码或手动连接。", 13));
        } else for (KnownDevices.Device device : devices) deviceCard(body, device, true);
    }

    private void deviceCard(LinearLayout body, KnownDevices.Device device, boolean removable) {
        LinearLayout card = card(body);
        TextView address = label(device.name.isEmpty() ? device.address() : device.name, 18);
        address.setTypeface(null, Typeface.BOLD); card.addView(address);
        card.addView(label(device.address() + "  ·  " + device.identityLabel(), 12));
        card.addView(button("使用当前连接码连接", () -> connectKnown(device)));
        if (removable) {
            card.addView(outlinedButton("重命名设备", GREEN, () -> renameDevice(device)));
            card.addView(outlinedButton("移除记录", 0xFFAD4141, () -> {
            new AlertDialog.Builder(this).setTitle("移除这台设备？")
                    .setMessage("只删除本机保存的地址和身份记录，不影响对方设备。")
                    .setPositiveButton("移除", (dialog, which) -> {
                        new KnownDevices(this).forget(device); screenKey = ""; refresh();
                    }).setNegativeButton("取消", null).show();
            }));
        }
    }

    private void renameDevice(KnownDevices.Device device) {
        EditText name = new EditText(this);
        name.setSingleLine(true); name.setHint("例如：我的手机"); name.setText(device.name);
        name.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this).setTitle("重命名历史设备").setView(name)
                .setPositiveButton("保存", (dialog, which) -> {
                    new KnownDevices(this).rename(device, name.getText().toString());
                    screenKey = ""; refresh();
                }).setNegativeButton("取消", null).show();
    }

    private void connectKnown(KnownDevices.Device device) {
        EditText code = new EditText(this);
        code.setHint("被控端当前显示的 8 位连接码");
        code.setSingleLine(true); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setPadding(dp(20), dp(12), dp(20), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接 " + device.address())
                .setMessage("历史记录只保存设备身份；连接码每次共享都会更新。")
                .setView(code).setPositiveButton("连接", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                connect(new Pairing(device.host, device.port, code.getText().toString().trim(), device.fingerprint));
                dialog.dismiss();
            } catch (Exception e) { code.setError("请输入当前显示的 8 位连接码"); }
        }));
        dialog.show();
    }

    private void settingsPage(LinearLayout body) {
        TextView title = label("权限与安全", 20); title.setTypeface(null, Typeface.BOLD); body.addView(title);
        LinearLayout touchCard = card(body);
        touchCard.addView(label("触控服务", 18));
        touchCard.addView(label(ControlService.available() ? "运行中 · 可执行远程触控" :
                "未运行 · 共享画面可用，但远程点击和滑动不可用", 14));
        touchCard.addView(button(ControlService.available() ? "管理触控服务" : "开启触控服务", this::accessibility));
        touchCard.addView(label("如果系统设置显示已开启却无法触控，请先关闭再开启此服务，然后返回应用确认状态。", 12));
        LinearLayout privacy = card(body);
        privacy.addView(label("设备记录", 18));
        privacy.addView(label("连接成功后只保存地址和设备校验身份。临时连接码、二维码令牌和屏幕内容均不保存在本机。", 14));
        privacy.addView(outlinedButton("管理历史设备", GREEN, () -> { homeTab = 1; screenKey = ""; refresh(); }));
        LinearLayout network = card(body);
        network.addView(label("局域网连接", 18));
        network.addView(label("两台设备需位于同一 Wi-Fi 或热点网络。被控端每次开启共享后，需要重新确认连接。", 14));
    }

    private void requestHost() {
        if (app.session != null) return;
        if (!ControlService.available()) {
            new AlertDialog.Builder(this).setTitle("开启触控服务")
                    .setMessage("远程点击和滑动需要你在系统无障碍设置中开启“局域互控 · 触控服务”。未开启时仅可观看画面。")
                    .setPositiveButton("去开启", (d, w) -> accessibility())
                    .setNeutralButton("仅共享画面", (d, w) -> notificationsThenCapture())
                    .setNegativeButton("取消", null).show();
        } else notificationsThenCapture();
    }

    private void accessibility() {
        new AlertDialog.Builder(this).setTitle("触控服务用途")
                .setMessage("开启后，仅在你主动共享并确认连接期间，执行对方的点击、滑动、长按和导航操作。服务不会读取界面文字。你可在应用或通知中停止共享，也可在系统设置中关闭服务。")
                .setPositiveButton("前往系统设置", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("取消", null).show();
    }

    private void notificationsThenCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else capture();
    }

    private void capture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent intent = Build.VERSION.SDK_INT >= 34 ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(intent, CAPTURE);
    }

    private void host(Session session) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout panel = card(body);
        panel.addView(label(session.approved ? "正在共享本机屏幕" : "等待另一台设备连接", 21));
        if (session.identity != null && !session.approved) {
            panel.addView(label("选择对方能访问的本机地址；热点常有独立地址，无法连接时可切换。", 13));
            Spinner addresses = new Spinner(this);
            addresses.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, session.addresses));
            panel.addView(addresses);
            ImageView qr = new ImageView(this); qr.setAdjustViewBounds(true);
            LinearLayout.LayoutParams qrLayout = new LinearLayout.LayoutParams(dp(260), dp(260));
            qrLayout.gravity = Gravity.CENTER_HORIZONTAL; panel.addView(qr, qrLayout);
            TextView details = label("", 17); details.setTextIsSelectable(true); panel.addView(details);
            addresses.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    Pairing pairing = new Pairing(session.addresses.get(position), 8765, session.token, session.identity.fingerprint);
                    qr.setImageBitmap(qr(pairing.invitation()));
                    details.setText("地址：" + pairing.host + ":8765\n连接码：" + session.pin + "\n设备校验码：" + Pairing.deviceCode(session.identity.fingerprint));
                }
            });
        }
        panel.addView(label("连接请求需在本机确认。共享期间可返回桌面、打开其他应用；对方会看到屏幕内容。开启共享后屏幕保持点亮，手动按电源键仍可锁屏。", 14));
        panel.addView(label(ControlService.available() ? "触控服务运行中：对方可点击和滑动。"
                : "触控服务未运行：对方目前只能观看画面。", 14));
        panel.addView(outlinedButton("停止共享 / 切换角色", Color.rgb(169, 55, 55), app::disconnect));
        panel.addView(outlinedButton("触控服务设置", GREEN, this::accessibility));
    }

    private void viewer(Session session) {
        if (session.rtc != null) {
            rendererEngine = session.rtc;
            videoPane = new FrameLayout(this);
            videoPane.setBackgroundColor(Color.BLACK);
            renderer = new SurfaceViewRenderer(this);
            renderer.init(rendererEngine.egl.getEglBaseContext(), new RendererCommon.RendererEvents() {
                @Override public void onFirstFrameRendered() { }
                @Override public void onFrameResolutionChanged(int width, int height, int rotation) {
                     runOnUiThread(() -> fitVideo(videoPane));
                }
            });
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            renderer.setEnableHardwareScaler(false);
            // An opaque View background can cover the SurfaceView's separate video surface.
            renderer.setBackgroundColor(Color.TRANSPARENT);
            videoPane.addView(renderer, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            videoStage = new FrameLayout(this);
            videoStage.setBackgroundColor(Color.BLACK);
            FrameLayout.LayoutParams videoLayout = new FrameLayout.LayoutParams(-1, -1);
            videoLayout.rightMargin = dp(64);
            videoStage.addView(videoPane, videoLayout);
            root.addView(videoStage, new LinearLayout.LayoutParams(-1, 0, 1));
            videoPane.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> fitVideo(videoPane));
            rendererEngine.attachRenderer(renderer);
            renderer.setOnTouchListener((view, event) -> touch(session, event));
            makeViewerTools(session);
            app.main.removeCallbacks(statsTicker);
            app.main.post(statsTicker);
        } else {
            TextView hint = label("请在对方设备上确认本次连接。", 18);
            root.addView(hint, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(outlinedButton("取消连接", GREEN, app::disconnect));
        }
    }

    private void makeViewerTools(Session session) {
        viewerTools = column();
        viewerTools.setPadding(dp(10), dp(6), dp(10), dp(6));
        viewerTools.setBackgroundColor(0xFF14211F);
        root.addView(viewerTools, 0, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL); viewerTools.addView(line);
        TextView name = lightLabel("局域互控  ·  已连接", 15); name.setTypeface(null, Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(chip("画质 " + selectedQuality.title, () -> qualityMenu(session)));
        line.addView(chip("全屏", () -> { immersive = true; applyImmersive(); updateTools(); }));
        metrics = lightLabel(rendererEngine.liveStats, 12); viewerTools.addView(metrics);
        controlHint = lightLabel(app.status, 12); viewerTools.addView(controlHint);
        FrameLayout navigation = new FrameLayout(this);
        navigation.setBackgroundColor(0xFF14211F);
        LinearLayout keys = new LinearLayout(this); keys.setGravity(Gravity.CENTER);
        navigation.addView(keys, new FrameLayout.LayoutParams(dp(216), dp(52), Gravity.CENTER));
        String[][] actions = {{"◀", "返回", "back"}, {"○", "主页", "home"}, {"□", "最近任务", "recents"}};
        for (String[] action : actions) {
            keys.addView(navKey(action[0], action[1], () -> session.rtc.send(Session.json(action[2]))),
                    new LinearLayout.LayoutParams(dp(72), dp(48)));
        }
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(56)));
        showTools = lightLabel("工具", 13);
        showTools.setGravity(Gravity.CENTER);
        showTools.setPadding(dp(10), 0, dp(10), 0);
        showTools.setOnClickListener(v -> { immersive = false; applyImmersive(); updateTools(); });
        FrameLayout.LayoutParams toolsLayout = new FrameLayout.LayoutParams(-2, dp(48), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        toolsLayout.leftMargin = dp(8);
        navigation.addView(showTools, toolsLayout);
        TextView disconnect = lightLabel("断开", 13);
        disconnect.setGravity(Gravity.CENTER); disconnect.setContentDescription("断开远程连接");
        GradientDrawable floatingBackground = new GradientDrawable();
        floatingBackground.setColor(0xFFAD4141); floatingBackground.setCornerRadius(dp(28));
        disconnect.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF), floatingBackground, null));
        disconnect.setElevation(dp(8));
        disconnect.setOnClickListener(v -> { immersive = false; applyImmersive(); app.disconnect(); });
        FrameLayout.LayoutParams floatingLayout = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.RIGHT | Gravity.BOTTOM);
        floatingLayout.rightMargin = dp(6); floatingLayout.bottomMargin = dp(16);
        videoStage.addView(disconnect, floatingLayout);
        updateTools();
    }

    private TextView navKey(String symbol, String description, Runnable action) {
        TextView key = lightLabel(symbol, 24);
        key.setGravity(Gravity.CENTER); key.setPadding(0, 0, 0, 0);
        key.setContentDescription(description); key.setOnClickListener(v -> action.run());
        return key;
    }

    private void qualityMenu(Session session) {
        String[] options = {"流畅 · 最高 45 帧，1280 长边", "均衡 · 最高 30 帧，1600 长边", "清晰 · 最高 30 帧，1920 长边"};
        new AlertDialog.Builder(this).setTitle("视频画质").setSingleChoiceItems(options, selectedQuality.ordinal(), (d, which) -> {
            selectedQuality = VideoQuality.values()[which];
            if (session.rtc != null) session.rtc.requestQuality(selectedQuality);
            d.dismiss();
            if (viewerTools != null) ((TextView)((LinearLayout)viewerTools.getChildAt(0)).getChildAt(1)).setText("画质 " + selectedQuality.title);
        }).setNegativeButton("取消", null).show();
    }

    private void updateTools() {
        if (viewerTools == null) return;
        viewerTools.setVisibility(immersive ? View.GONE : View.VISIBLE);
        showTools.setVisibility(immersive ? View.VISIBLE : View.GONE);
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (immersive) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else getWindow().getDecorView().setSystemUiVisibility(immersive
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
        if (root != null) root.requestApplyInsets();
    }

    private void fitVideo(FrameLayout pane) {
        if (renderer == null || rendererEngine == null || renderer.getParent() != pane) return;
        int vw = rendererEngine.videoWidth, vh = rendererEngine.videoHeight;
        if (vw <= 0 || vh <= 0 || pane.getWidth() <= 0 || pane.getHeight() <= 0) return;
        float scale = Math.min((float) pane.getWidth() / vw, (float) pane.getHeight() / vh);
        int width = Math.max(1, Math.round(vw * scale)), height = Math.max(1, Math.round(vh * scale));
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) renderer.getLayoutParams();
        if (layout.width != width || layout.height != height) {
            renderer.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        }
    }

    private boolean touch(Session session, MotionEvent event) {
        if (renderer == null || session.rtc == null) return true;
        if (event.getPointerCount() != 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            finishStroke(); return true;
        }
        float[] p = TouchGeometry.normalize(event.getX(), event.getY(), renderer.getWidth(), renderer.getHeight(),
                        session.rtc.videoWidth, session.rtc.videoHeight);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                app.main.removeCallbacks(holdTick);
                points.clear(); touchStart = SystemClock.uptimeMillis();
                touchPortrait = session.rtc.videoHeight >= session.rtc.videoWidth;
                touchSession = p == null ? null : session;
                streamActive = false; lastSentPoint = null; lastSentAt = touchStart;
                if (p != null) { points.add(p); app.main.postDelayed(holdTick, 120); }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!points.isEmpty() && p != null) {
                    float[] initial = points.get(0);
                    if (!streamActive && SystemClock.uptimeMillis() - touchStart >= 24
                            && Math.hypot(p[0] - initial[0], p[1] - initial[1]) > 0.006) startStroke(session, p);
                    else if (streamActive && SystemClock.uptimeMillis() - lastSentAt >= 32) sendStroke(session, "move", lastSentPoint, p);
                    if (points.size() < 79) points.add(p);
                    else points.set(points.size() - 1, p);
                }
                return true;
            case MotionEvent.ACTION_UP:
                app.main.removeCallbacks(holdTick);
                if (points.isEmpty() || p == null || touchPortrait != (session.rtc.videoHeight >= session.rtc.videoWidth)) { finishStroke(); return true; }
                if (streamActive) { sendStroke(session, "end", lastSentPoint, p); streamActive = false; finishStroke(); return true; }
                points.add(p);
                JSONArray path = new JSONArray();
                for (float[] point : points) path.put(new JSONArray(java.util.Arrays.asList(point[0], point[1])));
                long duration = Math.max(1, Math.min(8000, SystemClock.uptimeMillis() - touchStart));
                session.rtc.send(Session.json("gesture", "points", path, "duration", duration, "portrait", touchPortrait));
                finishStroke(); return true;
            default: return true;
        }
    }

    private void startStroke(Session session, float[] current) {
        if (points.isEmpty() || current == null) return;
        streamActive = true;
        sendStroke(session, "start", points.get(0), current);
    }

    private void sendStroke(Session session, String phase, float[] from, float[] to) {
        if (from == null || to == null || session.rtc == null) return;
        long now = SystemClock.uptimeMillis();
        long duration = Math.max(1, Math.min(250, now - lastSentAt));
        session.rtc.send(Session.json("stroke", "phase", phase, "from", new JSONArray(java.util.Arrays.asList(from[0], from[1])),
                "to", new JSONArray(java.util.Arrays.asList(to[0], to[1])), "duration", duration, "portrait", touchPortrait));
        lastSentAt = now; lastSentPoint = to;
    }

    private void finishStroke() {
        app.main.removeCallbacks(holdTick);
        if (streamActive && touchSession != null && lastSentPoint != null)
            sendStroke(touchSession, "end", lastSentPoint, lastSentPoint);
        streamActive = false; touchSession = null; lastSentPoint = null; points.clear();
    }

    private void manual() {
        LinearLayout fields = column(); fields.setPadding(dp(24), 0, dp(24), 0);
        EditText address = input(fields, "地址，例如 192.168.43.1:8765");
        EditText code = input(fields, "8 位连接码"); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText fingerprint = input(fields, "16 位设备校验码");
        fingerprint.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("输入对方显示的连接信息").setView(fields)
                .setPositiveButton("连接", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String normalizedAddress = java.text.Normalizer.normalize(address.getText().toString(), java.text.Normalizer.Form.NFKC).replace('。', '.').trim();
                String[] endpoint = normalizedAddress.split(":", -1);
                if (endpoint.length > 2) throw new IllegalArgumentException("地址格式不正确");
                Pairing pairing = new Pairing(endpoint[0], endpoint.length == 2 ? Integer.parseInt(endpoint[1]) : 8765,
                        code.getText().toString().trim(), fingerprint.getText().toString().replace(" ", "").trim().toLowerCase(java.util.Locale.ROOT));
                connect(pairing); dialog.dismiss();
            } catch (Exception e) { Toast.makeText(this, e.getMessage() == null ? "连接信息无效" : e.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        dialog.show();
    }

    private void connect(Pairing pairing) {
        app.disconnect();
        Session session = new Session(app, false); app.session = session;
        session.startClient(pairing); refresh();
    }

    private void scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA);
        else startActivityForResult(new Intent(this, ScanActivity.class), SCAN);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATIONS) capture();
        if (request == CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scan();
            else app.publish("未开启相机权限，可使用手动输入连接。");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CAPTURE && result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, ShareService.class).putExtra("permission", data));
        } else if (request == CAPTURE) app.publish("屏幕共享未授权。");
        else if (request == SCAN && result == RESULT_OK && data != null) {
            try { connect(Pairing.parse(data.getStringExtra("invitation"))); }
            catch (Exception e) { app.publish(e.getMessage()); }
        }
    }

    private void releaseRenderer() {
        if (rendererEngine != null) rendererEngine.attachRenderer(null);
        if (renderer != null) { renderer.release(); renderer = null; }
        rendererEngine = null;
        videoPane = null; videoStage = null; viewerTools = null; metrics = null; showTools = null; controlHint = null;
    }

    private Bitmap qr(String value) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888);
        } catch (Exception e) { return null; }
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(INK);
        view.setPadding(0, dp(4), 0, dp(6)); view.setLineSpacing(dp(3), 1); return view;
    }
    private Button button(String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setTextColor(Color.WHITE); view.setAllCaps(false);
        GradientDrawable background = new GradientDrawable(); background.setColor(GREEN); background.setCornerRadius(dp(12));
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44FFFFFF), background, null));
        view.setElevation(dp(2)); view.setMinHeight(dp(52));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(52));
        layout.topMargin = dp(5); layout.bottomMargin = dp(5); view.setLayoutParams(layout);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private Button outlinedButton(String text, int color, Runnable action) {
        Button view = button(text, action); view.setTextColor(color); view.setElevation(0);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE);
        background.setCornerRadius(dp(12)); background.setStroke(dp(1), color);
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22146C62), background, null));
        return view;
    }
    private TextView lightLabel(String text, int size) {
        TextView value = new TextView(this); value.setText(text); value.setTextSize(size); value.setTextColor(Color.WHITE);
        value.setPadding(dp(6), dp(4), dp(6), dp(4)); return value;
    }
    private TextView chip(String text, Runnable action) {
        TextView value = lightLabel(text, 14); value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(52)); value.setOnClickListener(v -> action.run()); return value;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = column(); view.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(18)); view.setBackground(background);
        LinearLayout.LayoutParams layout = "wide".equals(parent.getTag())
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(14);
        if ("wide".equals(parent.getTag())) layout.rightMargin = dp(12);
        parent.addView(view, layout); return view;
    }
    private EditText input(LinearLayout parent, String hint) {
        EditText view = new EditText(this); view.setHint(hint); view.setTextSize(15); view.setSingleLine(true);
        // ASCII connection credentials must not be composed into Chinese words or punctuation by an IME.
        view.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(view, new LinearLayout.LayoutParams(-1, dp(64))); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
