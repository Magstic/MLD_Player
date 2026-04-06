package com.magstic.mldplayer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

public final class QueueBottomSheetBehavior<V extends View> extends BottomSheetBehavior<V> {
    public QueueBottomSheetBehavior() {
        super();
    }

    public QueueBottomSheetBehavior(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onStartNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View directTargetChild,
            @NonNull View target,
            int axes,
            int type) {
        if (isPlaylistTarget(target) && (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0) {
            return false;
        }
        return super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, axes, type);
    }

    @Override
    public void onNestedPreScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int dx,
            int dy,
            @NonNull int[] consumed,
            int type) {
        if (isPlaylistTarget(target)) {
            return;
        }
        super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type);
    }

    @Override
    public void onNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int dxConsumed,
            int dyConsumed,
            int dxUnconsumed,
            int dyUnconsumed,
            int type,
            @NonNull int[] consumed) {
        if (isPlaylistTarget(target)) {
            return;
        }
        super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed,
                dxUnconsumed,
                dyUnconsumed,
                type,
                consumed);
    }

    @Override
    public boolean onNestedPreFling(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            float velocityX,
            float velocityY) {
        if (isPlaylistTarget(target)) {
            return false;
        }
        return super.onNestedPreFling(coordinatorLayout, child, target, velocityX, velocityY);
    }

    @Override
    public void onStopNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int type) {
        if (isPlaylistTarget(target)) {
            return;
        }
        super.onStopNestedScroll(coordinatorLayout, child, target, type);
    }

    private boolean isPlaylistTarget(View target) {
        return target != null && target.getId() == R.id.playlist_view;
    }
}
