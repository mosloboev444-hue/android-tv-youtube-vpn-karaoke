package com.gathin.vpngate;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

/**
 * The «Караоке!!!» button's screen. It is opened by the launcher tile, asks the gate for the tunnel, and
 * shows fire while Юбуст connects. The gate then opens YouTube on its karaoke search, over this screen.
 *
 * <p>If the gate is not running (accessibility off), the fallback opens the same YouTube search after a
 * few seconds, so the button still does something useful.
 */
public class KaraokeActivity extends Activity {

    private static final long FALLBACK_MS = 6000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean left;
    private View title;

    private final Runnable fallback = new Runnable() {
        @Override
        public void run() {
            if (left) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GateAccessibilityService.KARAOKE_URL));
            intent.setPackage("com.google.android.youtube.tv");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            try {
                startActivity(intent);
            } catch (RuntimeException ignored) {
                // No YouTube: nothing else to do from here.
            }
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_karaoke);
        title = findViewById(R.id.title);

        title.setScaleX(0.92f);
        title.setScaleY(0.92f);
        title.animate().scaleX(1.04f).scaleY(1.04f).setDuration(900)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        title.animate().scaleX(0.98f).scaleY(0.98f).setDuration(900)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(this).start();
                    }
                }).start();

        findViewById(R.id.root).setOnClickListener(v -> requestKaraoke());
        requestKaraoke();
        handler.postDelayed(fallback, FALLBACK_MS);
    }

    private void requestKaraoke() {
        Intent intent = new Intent(GateAccessibilityService.ACTION_KARAOKE);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        TextView status = findViewById(R.id.status);
        status.setText(R.string.karaoke_wait);
    }

    @Override
    protected void onStop() {
        left = true;
        handler.removeCallbacks(fallback);
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }
}
