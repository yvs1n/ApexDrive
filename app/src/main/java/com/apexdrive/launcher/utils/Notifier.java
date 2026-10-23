package com.apexdrive.launcher.utils;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.apexdrive.launcher.R;

/**
 * Shows one floating notification capsule at a time, replacing Toast.
 * A notification stays at least {@link #MIN_VISIBLE_MS} so a driver can read it,
 * and can always be dismissed with the close button.
 */
public final class Notifier {

    public enum Kind { INFO, OK, CAUTION, ALERT }

    private static final long MIN_VISIBLE_MS = 8000;
    private static final int TOP_MARGIN_DP = 64;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dismissRunnable = this::dismiss;
    @Nullable private View current;

    public Notifier(Activity activity) {
        this.activity = activity;
    }

    public void show(Kind kind, String title) {
        show(kind, title, null, null, null);
    }

    public void show(Kind kind, String title, @Nullable String detail) {
        show(kind, title, detail, null, null);
    }

    public void show(Kind kind, String title, @Nullable String detail,
                     @Nullable String actionLabel, @Nullable Runnable action) {
        if (activity.isFinishing()) return;
        ViewGroup host = activity.findViewById(android.R.id.content);
        if (host == null) return;

        dismiss();
        View view = LayoutInflater.from(activity).inflate(R.layout.view_notification, host, false);

        View badge = view.findViewById(R.id.notificationBadge);
        ImageView icon = view.findViewById(R.id.notificationIcon);
        switch (kind) {
            case OK:
                badge.setBackgroundResource(R.drawable.ad_bg_badge_ok);
                icon.setImageResource(R.drawable.ic_sym_check);
                icon.setColorFilter(activity.getResources().getColor(R.color.ad_on_ok));
                break;
            case CAUTION:
                badge.setBackgroundResource(R.drawable.ad_bg_badge_caution);
                icon.setImageResource(R.drawable.ic_sym_priority_high);
                icon.setColorFilter(activity.getResources().getColor(R.color.ad_on_caution));
                break;
            case ALERT:
                badge.setBackgroundResource(R.drawable.ad_bg_badge_alert);
                icon.setImageResource(R.drawable.ic_sym_priority_high);
                icon.setColorFilter(activity.getResources().getColor(R.color.ad_on_alert));
                break;
            default:
                badge.setBackgroundResource(R.drawable.ad_bg_badge_neutral);
                icon.setImageResource(R.drawable.ic_sym_info);
                icon.setColorFilter(activity.getResources().getColor(R.color.ad_label_1));
                break;
        }

        ((TextView) view.findViewById(R.id.notificationTitle)).setText(title);
        TextView detailView = view.findViewById(R.id.notificationDetail);
        if (detail == null || detail.isEmpty()) {
            detailView.setVisibility(View.GONE);
        } else {
            detailView.setText(detail);
        }

        TextView actionView = view.findViewById(R.id.notificationAction);
        if (actionLabel != null && action != null) {
            actionView.setText(actionLabel);
            actionView.setVisibility(View.VISIBLE);
            actionView.setOnClickListener(v -> {
                dismiss();
                action.run();
            });
        }
        view.findViewById(R.id.notificationClose).setOnClickListener(v -> dismiss());

        float density = activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.topMargin = (int) (TOP_MARGIN_DP * density);
        host.addView(view, lp);
        current = view;
        handler.postDelayed(dismissRunnable, MIN_VISIBLE_MS);
    }

    public void dismiss() {
        handler.removeCallbacks(dismissRunnable);
        if (current != null) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (parent != null) parent.removeView(current);
            current = null;
        }
    }
}
