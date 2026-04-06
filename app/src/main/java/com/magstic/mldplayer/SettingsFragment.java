package com.magstic.mldplayer;

import android.app.Dialog;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.text.util.LinkifyCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class SettingsFragment extends Fragment implements PlayerController.UiListener {
    private static final float ABOUT_BLUR_RADIUS_DP = 14f;
    private static final float ABOUT_DIM_AMOUNT = 0.22f;

    private TextView sf2NameView;
    private TextView loopModeView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MainActivity activity = (MainActivity) requireActivity();
        View customSf2Row = view.findViewById(R.id.custom_sf2_row);
        View loopRow = view.findViewById(R.id.loop_row);
        View aboutRow = view.findViewById(R.id.about_row);

        sf2NameView = (TextView) view.findViewById(R.id.custom_sf2_value);
        loopModeView = (TextView) view.findViewById(R.id.loop_value);
        applyTopInset(view);

        customSf2Row.setOnClickListener(v -> activity.getController().onOpenSf2Requested());
        loopRow.setOnClickListener(v -> activity.getController().onLoopModeToggleRequested());
        aboutRow.setOnClickListener(v -> showAboutDialog());
    }

    @Override
    public void onStart() {
        super.onStart();
        ((MainActivity) requireActivity()).getController().addListener(this);
    }

    @Override
    public void onStop() {
        ((MainActivity) requireActivity()).getController().removeListener(this);
        super.onStop();
    }

    @Override
    public void onSettingsUiStateChanged(PlayerController.SettingsUiState state) {
        if (getView() == null) {
            return;
        }
        sf2NameView.setText(state.currentSf2Name);
        loopModeView.setText(state.currentLoopMode);
    }

    private void showAboutDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_about, null, false);
        int[] linkViewIds = new int[] {
                R.id.about_java_link,
                R.id.about_j2me_link,
                R.id.about_android_link,
                R.id.about_fluidsynth_link
        };

        for (int index = 0; index < linkViewIds.length; index++) {
            TextView linkView = (TextView) dialogView.findViewById(linkViewIds[index]);
            LinkifyCompat.addLinks(linkView, Linkify.WEB_URLS);
            linkView.setMovementMethod(LinkMovementMethod.getInstance());
        }

        Dialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        Window window = dialog.getWindow();

        if (window != null) {
            window.setDimAmount(ABOUT_DIM_AMOUNT);
        }
        applyAboutBackgroundFocus(true);
        dialog.setOnDismissListener(dialogInterface -> applyAboutBackgroundFocus(false));
    }

    private void applyTopInset(View view) {
        final int baseTopPadding = view.getPaddingTop();

        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            target.setPadding(
                    target.getPaddingLeft(),
                    baseTopPadding + systemBars.top,
                    target.getPaddingRight(),
                    target.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private void applyAboutBackgroundFocus(boolean enabled) {
        View contentView;

        if (!isAdded()) {
            return;
        }
        contentView = requireActivity().findViewById(android.R.id.content);
        if (contentView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (!enabled) {
            contentView.setRenderEffect(null);
            return;
        }
        float radius = dp(ABOUT_BLUR_RADIUS_DP);

        contentView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    private float dp(float value) {
        return value * requireContext().getResources().getDisplayMetrics().density;
    }
}
