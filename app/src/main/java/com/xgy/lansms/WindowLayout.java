package com.xgy.lansms;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** One inset owner for both screens, including Android 15/16 edge-to-edge. */
final class WindowLayout {
    private WindowLayout() {}

    static void apply(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT < 30) {
            root.setFitsSystemWindows(true);
            return;
        }
        activity.getWindow().setDecorFitsSystemWindows(false);
        root.setFitsSystemWindows(false);
        final int left = root.getPaddingLeft(), top = root.getPaddingTop();
        final int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            // Always derive from the original padding: keyboard/rotation updates
            // must not accumulate space or add navigation and IME heights twice.
            view.setPadding(left + safe.left, top + safe.top,
                    right + safe.right, bottom + safe.bottom);
            if (insets.isVisible(WindowInsets.Type.ime())) {
                view.post(() -> {
                    View focused = activity.getCurrentFocus();
                    if (focused == null) return;
                    Rect bounds = new Rect();
                    focused.getDrawingRect(bounds);
                    focused.requestRectangleOnScreen(bounds, true);
                });
            }
            return WindowInsets.CONSUMED;
        });
        WindowInsetsController controller = activity.getWindow().getInsetsController();
        if (controller != null) controller.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        root.requestApplyInsets();
    }
}
