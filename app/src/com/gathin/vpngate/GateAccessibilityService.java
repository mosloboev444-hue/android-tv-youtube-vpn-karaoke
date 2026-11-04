package com.gathin.vpngate;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.net.NetworkInterface;

/**
 * Turns the VPN on when YouTube is opened.
 *
 * <p>The TV has no usable always-on VPN and the Юбуст (Uboo) client exposes no intent to connect, only its
 * screen. So this accessibility service watches window changes: when YouTube comes to the front and no VPN
 * network exists, it opens Юбуст, presses its «Подключить» button, waits for the VPN network to appear and
 * then brings YouTube back to the front. It also re-connects if the tunnel drops while YouTube is open.
 *
 * <p>Юбуст is expected to be logged in and left in its «Только YouTube» mode; that mode is remembered by the
 * app, so the gate never has to touch the mode switch.
 */
public class GateAccessibilityService extends AccessibilityService {

    private static final String TAG = "VpnGate";

    private static final String PKG_YOUTUBE = "com.google.android.youtube.tv";
    private static final String PKG_VPN = "st.uboo.android.client";
    private static final String ACT_VPN = "online.vpnnaruzhu.client.android.SingleActivity";
    private static final String PKG_VPN_DIALOG = "com.android.vpndialogs";

    /** KaraokeActivity asks for "VPN first, then YouTube karaoke". */
    static final String ACTION_KARAOKE = "com.gathin.vpngate.KARAOKE";
    /** The YouTube TV app opens this URL as a search for караоке (checked on the TV). */
    static final String KARAOKE_URL = "https://www.youtube.com/results?search_query=%D0%BA%D0%B0%D1%80%D0%B0%D0%BE%D0%BA%D0%B5";

    /** How often the flow re-checks the screen while connecting. */
    private static final long POLL_MS = 400L;
    /** How often an idle service looks for "YouTube in front, VPN down". */
    private static final long IDLE_MS = 3000L;
    /** Give up on one connection attempt after this long. */
    private static final long CONNECT_TIMEOUT_MS = 30000L;
    /** Press «Подключить» again if the tunnel has not come up by then. */
    private static final long RETRY_MS = 7000L;
    private static final int MAX_ATTEMPTS = 3;
    /** After giving up, do not try again at once (the user may be fixing it by hand). */
    private static final long FAIL_COOLDOWN_MS = 20000L;
    /** Do not re-trigger in the same instant the returned-to YouTube window appears. */
    private static final long RELAUNCH_GRACE_MS = 4000L;

    private static final int IDLE = 0;
    private static final int OPENING = 1;
    private static final int CLICKED = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int state = IDLE;
    private long deadline;
    private long lastClickAt;
    private long blockedUntil;
    private long lastRelaunchAt;
    private int attempts;
    /** A karaoke tile asked for the tunnel; after it is up YouTube opens on its karaoke search. */
    private boolean karaokePending;
    /** YouTube was opened by the user without a tunnel, so the return has to restart it, not resume it. */
    private boolean freshLaunch;
    private BroadcastReceiver karaokeReceiver;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected");
        registerKaraokeReceiver();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = event.getPackageName().toString();
        if (PKG_YOUTUBE.equals(pkg) || PKG_VPN.equals(pkg) || PKG_VPN_DIALOG.equals(pkg)) {
            Log.i(TAG, "window: " + pkg);
        }
        if (PKG_YOUTUBE.equals(pkg)) {
            if (karaokePending && isVpnUp() && state == IDLE) {
                karaokePending = false;
                lastRelaunchAt = SystemClock.elapsedRealtime();
                openYouTube(true);
            } else {
                maybeStart();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: every wait is a Handler message.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (karaokeReceiver != null) {
            try {
                unregisterReceiver(karaokeReceiver);
            } catch (RuntimeException ignored) {
                // Already gone with the process.
            }
            karaokeReceiver = null;
        }
        super.onDestroy();
    }

    /** KaraokeActivity (same app) asks for the tunnel and the karaoke search. */
    private void registerKaraokeReceiver() {
        if (karaokeReceiver != null) return;
        karaokeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_KARAOKE.equals(intent.getAction())) return;
                Log.i(TAG, "karaoke requested");
                karaokePending = true;
                startKaraokeFlow();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_KARAOKE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(karaokeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(karaokeReceiver, filter);
        }
    }

    private void startKaraokeFlow() {
        if (isVpnUp()) {
            karaokePending = false;
            lastRelaunchAt = SystemClock.elapsedRealtime();
            openYouTube(true);
            return;
        }
        if (state != IDLE) return;
        long now = SystemClock.elapsedRealtime();
        if (now < blockedUntil) return;

        Log.i(TAG, "karaoke: opening " + PKG_VPN);
        state = OPENING;
        attempts = 0;
        deadline = now + CONNECT_TIMEOUT_MS;
        freshLaunch = false;
        // Let the fire screen draw for a moment before Юбуст covers it.
        handler.postDelayed(this::openVpnApp, 500);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 300);
    }

    // ---- state machine -------------------------------------------------------------------------

    private void step() {
        long now = SystemClock.elapsedRealtime();
        try {
            if (state == IDLE) {
                maybeStart();
            } else if (isVpnUp()) {
                // tun0 appears a moment before the tunnel is usable; give it a beat before returning.
                if (now - lastClickAt >= 1200 || karaokePending) finish(now);
            } else if (state == OPENING) {
                tryClick();
                if (now > deadline) giveUp();
            } else if (state == CLICKED) {
                if (now - lastClickAt > RETRY_MS && attempts < MAX_ATTEMPTS) tryClick();
                if (now > deadline) giveUp();
            }
        } catch (RuntimeException e) {
            // A stale node or a window that went away must never kill the service.
            Log.w(TAG, "step failed: " + e);
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, state == IDLE ? IDLE_MS : POLL_MS);
    }

    /** YouTube is in front and the tunnel is down: start one connection attempt. */
    private void maybeStart() {
        if (state != IDLE) return;
        if (isVpnUp()) return;
        long now = SystemClock.elapsedRealtime();
        if (now < blockedUntil || now - lastRelaunchAt < RELAUNCH_GRACE_MS) return;
        if (!PKG_YOUTUBE.equals(currentPackage())) return;

        Log.i(TAG, "YouTube in front, no VPN: opening " + PKG_VPN);
        state = OPENING;
        attempts = 0;
        deadline = now + CONNECT_TIMEOUT_MS;
        freshLaunch = true;
        openVpnApp();
    }

    /** Press «Подключить» (or the VPN permission dialog's positive button) on the screen in front. */
    private void tryClick() {
        String pkg = currentPackage();
        if (PKG_VPN_DIALOG.equals(pkg)) {
            if (clickAnyText("OK", "Разрешить", "Allow")) {
                Log.i(TAG, "allowed the VPN permission dialog");
                lastClickAt = SystemClock.elapsedRealtime();
                state = CLICKED;
            }
            return;
        }
        if (!PKG_VPN.equals(pkg)) return;
        if (clickConnectButton()) {
            state = CLICKED;
            attempts++;
            lastClickAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "pressed connect (attempt " + attempts + ")");
        }
    }

    /** The tunnel is up: go back to YouTube, on the karaoke search when a karaoke tile asked for it. */
    private void finish(long now) {
        boolean karaoke = karaokePending;
        karaokePending = false;
        Log.i(TAG, "VPN is up" + (karaoke ? " (karaoke)" : ""));

        state = IDLE;
        deadline = 0;
        String pkg = currentPackage();
        boolean waitingHere = PKG_VPN.equals(pkg) || getPackageName().equals(pkg);
        if (karaoke || waitingHere || freshLaunch) {
            lastRelaunchAt = now;
            openYouTube(karaoke);
        }
        freshLaunch = false;
    }

    private void giveUp() {
        Log.w(TAG, "no VPN after " + attempts + " attempt(s)");
        state = IDLE;
        deadline = 0;
        karaokePending = false;
        blockedUntil = SystemClock.elapsedRealtime() + FAIL_COOLDOWN_MS;
        try {
            Toast.makeText(this, R.string.vpn_failed, Toast.LENGTH_LONG).show();
        } catch (RuntimeException ignored) {
            // A toast is a nicety; never let it break the gate.
        }
    }

    // ---- screen actions ------------------------------------------------------------------------

    private boolean clickAnyText(String... needles) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        for (String needle : needles) {
            AccessibilityNodeInfo node = findText(root, needle);
            if (node == null) continue;
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
            AccessibilityNodeInfo press = clickable != null ? clickable : node;
            if (press.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private boolean clickConnectButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        // Already connected or connecting: the button reads «Отключить»; never press that by mistake.
        if (findText(root, "ОТКЛЮЧИТЬ") != null || findText(root, "DISCONNECT") != null) {
            Log.i(TAG, "button already says disconnect, nothing to press");
            return false;
        }

        AccessibilityNodeInfo target = findText(root, "ПОДКЛЮЧИТЬ");
        if (target == null) target = findText(root, "ПОДКЛЮЧИТЬСЯ");
        if (target == null) target = findText(root, "CONNECT");
        if (target == null) target = findBigButton(root);
        if (target == null) {
            Log.w(TAG, "connect button not found");
            return false;
        }

        AccessibilityNodeInfo clickable = target;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();

        AccessibilityNodeInfo press = clickable != null ? clickable : target;
        press.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (press.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;

        Rect bounds = new Rect();
        press.getBoundsInScreen(bounds);
        return tap(bounds.centerX(), bounds.centerY());
    }

    /** Breadth-first search for a node whose text or content description contains one of the needles. */
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String needle) {
        try {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(needle);
            if (hits != null && !hits.isEmpty()) return hits.get(0);
        } catch (RuntimeException ignored) {
            // Fall through to the manual walk.
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 600) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (contains(text, needle) || contains(desc, needle)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private static boolean contains(CharSequence haystack, String needle) {
        return haystack != null && haystack.toString().toUpperCase().contains(needle.toUpperCase());
    }

    /**
     * Fallback for a Compose build whose button text does not reach accessibility: the round connect
     * button is the largest clickable thing in the right half of the screen.
     */
    private AccessibilityNodeInfo findBigButton(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.width() <= 0 || screen.height() <= 0) return null;

        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 600) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            if (node.isClickable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                long area = (long) r.width() * r.height();
                boolean rightHalf = r.centerX() > screen.centerX();
                if (rightHalf && area > bestArea) {
                    best = node;
                    bestArea = area;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        // The real button covers roughly a fifth of the screen; anything smaller is not it.
        return bestArea > (long) screen.width() * screen.height() / 10 ? best : null;
    }

    private boolean tap(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "tap failed: " + e);
            return false;
        }
    }

    // ---- app launching -------------------------------------------------------------------------

    private void openVpnApp() {
        Log.i(TAG, "starting Юбуст");
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(PKG_VPN, ACT_VPN));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            Log.i(TAG, "startActivity(Юбуст) ok");
            return;
        } catch (RuntimeException e) {
            Log.w(TAG, "explicit Юбуст launch failed: " + e);
        }
        launchPackage(PKG_VPN);
    }

    private void openYouTube(boolean karaoke) {
        Intent intent;
        if (karaoke) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(KARAOKE_URL));
            intent.setPackage(PKG_YOUTUBE);
        } else {
            intent = getPackageManager().getLeanbackLaunchIntentForPackage(PKG_YOUTUBE);
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(PKG_YOUTUBE);
        }
        if (intent == null) {
            Log.w(TAG, "YouTube has no launch intent");
            return;
        }
        // CLEAR_TASK restarts YouTube instead of resuming the "no connection" screen it drew before the tunnel.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(intent);
            Log.i(TAG, karaoke ? "YouTube karaoke opened" : "YouTube opened");
        } catch (RuntimeException e) {
            Log.w(TAG, "YouTube launch failed: " + e);
        }
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, "no launch intent for " + pkg);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "launch failed for " + pkg + ": " + e);
        }
    }

    // ---- device state --------------------------------------------------------------------------

    private String currentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }

    private boolean isVpnUp() {
        // A tunnel interface is the reliable sign here: Юбуст's «Только YouTube» mode routes just YouTube, so
        // ConnectivityManager hides its VPN network from this app (getAllNetworks() has no TRANSPORT_VPN for
        // us), while tun0 exists for the whole device.
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                String name = ni.getName();
                if (name == null) continue;
                if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("ppp")) {
                    if (ni.isUp()) return true;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the ConnectivityManager check.
        }

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network[] networks;
        try {
            networks = cm.getAllNetworks();
        } catch (RuntimeException e) {
            return false;
        }
        for (Network network : networks) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
}
