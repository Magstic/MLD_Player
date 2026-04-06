package com.magstic.mldplayer;

import android.content.res.ColorStateList;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

public final class PlayerFragment extends Fragment implements PlayerController.UiListener {
    private static final float QUEUE_HALF_EXPANDED_RATIO = 0.6f;
    private static final long SPECTRUM_FRAME_DELAY_MS = 48L;
    private static final float QUEUE_MAX_FOCUS_ALPHA = 0.18f;
    private static final float QUEUE_MAX_BLUR_RADIUS_DP = 14f;

    private TextView pageSubtitleView;
    private MaterialCardView statusPillView;
    private View statusDotView;
    private TextView statusTextView;
    private ImageView statusIconView;
    private PausedMarqueeTextView titleTextView;
    private TextView copyrightTextView;
    private TextView positionTextView;
    private TextView durationTextView;
    private TextView queueCountView;
    private TextView queueEmptyView;
    private SpectrumView spectrumView;
    private LinearProgressIndicator progressBar;
    private MaterialButton previousButton;
    private MaterialButton nextButton;
    private MaterialButton playPauseButton;
    private RecyclerView playlistView;
    private View queueHeaderView;
    private View queueFocusScrimView;
    private View playerScrollView;
    private PlaylistAdapter playlistAdapter;
    private LinearLayoutManager playlistLayoutManager;
    private BottomSheetBehavior<View> queueSheetBehavior;
    private boolean queueStateInitialized;
    private boolean lastQueueHadItems;
    private int lastQueuePeekHeight = -1;
    private float lastQueueFocusProgress = -1f;
    private boolean spectrumAnimating;
    private final Runnable spectrumFrameRunnable = new Runnable() {
        @Override
        public void run() {
            MainActivity activity;
            float[] levels;

            if (!spectrumAnimating || spectrumView == null || !isAdded()) {
                return;
            }
            activity = (MainActivity) requireActivity();
            levels = activity.getController().currentSpectrumLevels();
            if (levels == null) {
                spectrumView.showStaticBars();
            } else {
                spectrumView.updateLevels(levels);
            }
            spectrumView.removeCallbacks(this);
            spectrumView.postDelayed(this, SPECTRUM_FRAME_DELAY_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) requireActivity();
        View queueSheet = view.findViewById(R.id.queue_sheet);
        playerScrollView = view.findViewById(R.id.player_scroll);

        pageSubtitleView = (TextView) view.findViewById(R.id.player_page_subtitle);
        statusPillView = (MaterialCardView) view.findViewById(R.id.status_pill);
        statusDotView = view.findViewById(R.id.status_dot);
        statusTextView = (TextView) view.findViewById(R.id.status_text);
        statusIconView = (ImageView) view.findViewById(R.id.status_icon);
        titleTextView = (PausedMarqueeTextView) view.findViewById(R.id.title_text);
        copyrightTextView = (TextView) view.findViewById(R.id.copyright_text);
        positionTextView = (TextView) view.findViewById(R.id.position_text);
        durationTextView = (TextView) view.findViewById(R.id.duration_text);
        queueCountView = (TextView) view.findViewById(R.id.queue_count);
        queueEmptyView = (TextView) view.findViewById(R.id.queue_empty_text);
        spectrumView = (SpectrumView) view.findViewById(R.id.spectrum_view);
        progressBar = (LinearProgressIndicator) view.findViewById(R.id.progress_bar);
        previousButton = (MaterialButton) view.findViewById(R.id.previous_button);
        nextButton = (MaterialButton) view.findViewById(R.id.next_button);
        playPauseButton = (MaterialButton) view.findViewById(R.id.play_pause_button);
        playlistView = (RecyclerView) view.findViewById(R.id.playlist_view);
        queueHeaderView = view.findViewById(R.id.queue_header);
        queueFocusScrimView = view.findViewById(R.id.queue_focus_scrim);

        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Listener() {
            @Override
            public void onPlaylistEntryClicked(int position) {
                activity.getController().onPlaylistItemSelected(position);
            }
        });
        playlistLayoutManager = new LinearLayoutManager(view.getContext());
        playlistView.setLayoutManager(playlistLayoutManager);
        playlistView.setAdapter(playlistAdapter);
        playlistView.setNestedScrollingEnabled(true);
        playlistView.setHasFixedSize(true);
        playlistView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        applyTopInset(playerScrollView);
        applyQueueBottomInset(queueSheet);
        spectrumView.showStaticBars();

        previousButton.setOnClickListener(v -> activity.getController().onPreviousRequested());
        nextButton.setOnClickListener(v -> activity.getController().onNextRequested());
        playPauseButton.setOnClickListener(v -> activity.getController().onPlayPauseRequested());
        queueHeaderView.setOnClickListener(v -> toggleQueueSheet());
        queueFocusScrimView.setOnClickListener(v -> {
            if (queueSheetBehavior != null) {
                queueSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });

        queueSheetBehavior = BottomSheetBehavior.from(queueSheet);
        queueSheetBehavior.setFitToContents(false);
        queueSheetBehavior.setSkipCollapsed(false);
        queueSheetBehavior.setHideable(false);
        queueSheetBehavior.setHalfExpandedRatio(QUEUE_HALF_EXPANDED_RATIO);
        queueSheetBehavior.setPeekHeight(dp(120));
        queueSheetBehavior.setDraggable(true);
        queueSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (queueSheetBehavior == null) {
                    return;
                }
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    queueSheetBehavior.setState(lastQueueHadItems
                            ? BottomSheetBehavior.STATE_HALF_EXPANDED
                            : BottomSheetBehavior.STATE_COLLAPSED);
                }
                updateQueueFocusEffect(bottomSheet);
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                updateQueueFocusEffect(bottomSheet);
            }
        });
        queueSheet.post(new Runnable() {
            @Override
            public void run() {
                if (queueSheetBehavior != null) {
                    View parent = (View) queueSheet.getParent();
                    if (parent != null) {
                        queueSheetBehavior.setExpandedOffset((int) (parent.getHeight() * (1f - QUEUE_HALF_EXPANDED_RATIO)));
                    }
                    queueSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                    updateQueueFocusEffect(queueSheet);
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ((MainActivity) requireActivity()).getController().addListener(this);
    }

    @Override
    public void onStop() {
        stopSpectrumAnimation();
        if (spectrumView != null) {
            spectrumView.showStaticBars();
        }
        ((MainActivity) requireActivity()).getController().removeListener(this);
        super.onStop();
    }

    @Override
    public void onPlayerUiStateChanged(PlayerController.PlayerUiState state) {
        boolean showPageSubtitle;

        if (getView() == null) {
            return;
        }
        pageSubtitleView.setText(state.pageSubtitle);
        showPageSubtitle = !state.trackLoaded && state.pageSubtitle != null && state.pageSubtitle.length() > 0;
        pageSubtitleView.setVisibility(showPageSubtitle ? View.VISIBLE : View.GONE);
        statusTextView.setText(state.status);
        statusIconView.setImageResource(resolveStatusIcon(state));
        if (!sameText(titleTextView, state.title)) {
            titleTextView.setText(state.title);
        }
        if (state.copyright.length() == 0) {
            copyrightTextView.setText("");
        } else {
            String copyrightText = getString(R.string.player_copyright_format, state.copyright);
            if (!sameText(copyrightTextView, copyrightText)) {
                copyrightTextView.setText(copyrightText);
            }
        }
        copyrightTextView.setVisibility(state.copyright.length() == 0 ? View.GONE : View.VISIBLE);
        positionTextView.setText(formatTime(state.positionMs));
        durationTextView.setText(state.durationMs > 0 ? formatTime(state.durationMs) : getString(R.string.unknown_duration));
        progressBar.setProgressCompat(progressValue(state.positionMs, state.durationMs), true);
        playPauseButton.setIconResource(state.playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        playPauseButton.setEnabled(state.controlsEnabled);
        playPauseButton.setAlpha(state.controlsEnabled ? 1f : 0.45f);
        previousButton.setEnabled(state.previousEnabled);
        nextButton.setEnabled(state.nextEnabled);
        queueCountView.setText(getString(R.string.queue_count, Integer.valueOf(state.playlistEntries.size())));
        queueEmptyView.setVisibility(state.playlistEntries.isEmpty() ? View.VISIBLE : View.GONE);
        playlistView.setVisibility(state.playlistEntries.isEmpty() ? View.GONE : View.VISIBLE);
        playlistAdapter.submit(state.playlistEntries, state.currentIndex);

        applyChroming(state);
        syncQueueSheetState(!state.playlistEntries.isEmpty());
        syncSpectrumState(state);
    }

    private void applyChroming(PlayerController.PlayerUiState state) {
        int primary = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorPrimary, 0);
        int onPrimary = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnPrimary, 0);
        int primaryContainer = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorPrimaryContainer, 0);
        int onPrimaryContainer = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
        int secondary = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSecondary, 0);
        int onSecondary = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSecondary, onPrimary);
        int secondaryContainer = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSecondaryContainer, 0);
        int onSecondaryContainer = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSecondaryContainer, 0);
        int surfaceVariant = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSurfaceVariant, 0);
        int onSurface = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurface, 0);
        int onSurfaceVariant = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
        int outline = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOutline, onSurfaceVariant);
        int pillColor = surfaceVariant;
        int pillTextColor = onSurfaceVariant;
        int dotColor = outline;
        int playButtonColor = primary;
        int playButtonIconColor = onPrimary;
        int spectrumAccent = primary;

        if (state.loading) {
            pillColor = primaryContainer;
            pillTextColor = onPrimaryContainer;
            dotColor = primary;
        } else if (state.playing) {
            pillColor = secondaryContainer;
            pillTextColor = onSecondaryContainer;
            dotColor = secondary;
            playButtonColor = secondary;
            playButtonIconColor = onSecondary;
            spectrumAccent = secondary;
        } else if (state.paused) {
            pillColor = primaryContainer;
            pillTextColor = onPrimaryContainer;
            dotColor = primary;
        }

        statusPillView.setCardBackgroundColor(pillColor);
        statusTextView.setTextColor(pillTextColor);
        statusIconView.setColorFilter(pillTextColor);
        progressBar.setIndicatorColor(primary);
        progressBar.setTrackColor(surfaceVariant);
        playPauseButton.setBackgroundTintList(ColorStateList.valueOf(playButtonColor));
        playPauseButton.setIconTint(ColorStateList.valueOf(playButtonIconColor));
        previousButton.setBackgroundTintList(ColorStateList.valueOf(surfaceVariant));
        nextButton.setBackgroundTintList(ColorStateList.valueOf(surfaceVariant));
        previousButton.setIconTint(ColorStateList.valueOf(onSurface));
        nextButton.setIconTint(ColorStateList.valueOf(onSurface));
        queueCountView.setTextColor(onSurface);
        spectrumView.setPalette(spectrumAccent, surfaceVariant, outline);
        setDotColor(dotColor);
    }

    private void setDotColor(int color) {
        GradientDrawable drawable = new GradientDrawable();

        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        statusDotView.setBackground(drawable);
    }

    private void syncQueueSheetState(boolean hasItems) {
        int peekHeight;

        if (queueSheetBehavior == null) {
            return;
        }
        peekHeight = dp(hasItems ? 132 : 112);
        if (lastQueuePeekHeight != peekHeight) {
            lastQueuePeekHeight = peekHeight;
            queueSheetBehavior.setPeekHeight(peekHeight);
        }
        if (!queueStateInitialized) {
            queueStateInitialized = true;
            lastQueueHadItems = hasItems;
            queueSheetBehavior.setState(hasItems
                    ? BottomSheetBehavior.STATE_HALF_EXPANDED
                    : BottomSheetBehavior.STATE_COLLAPSED);
            View sheet = getView() == null ? null : getView().findViewById(R.id.queue_sheet);
            if (sheet != null) {
                updateQueueFocusEffect(sheet);
            }
            return;
        }
        if (lastQueueHadItems != hasItems) {
            lastQueueHadItems = hasItems;
            queueSheetBehavior.setState(hasItems
                    ? BottomSheetBehavior.STATE_HALF_EXPANDED
                    : BottomSheetBehavior.STATE_COLLAPSED);
            View sheet = getView() == null ? null : getView().findViewById(R.id.queue_sheet);
            if (sheet != null) {
                updateQueueFocusEffect(sheet);
            }
        }
    }

    private void toggleQueueSheet() {
        if (queueSheetBehavior == null) {
            return;
        }
        int state = queueSheetBehavior.getState();

        if (!lastQueueHadItems) {
            queueSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return;
        }
        if (state == BottomSheetBehavior.STATE_HALF_EXPANDED
                || state == BottomSheetBehavior.STATE_EXPANDED) {
            queueSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return;
        }
        queueSheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
    }

    private void syncSpectrumState(PlayerController.PlayerUiState state) {
        if (spectrumView == null) {
            return;
        }
        if (state.playing) {
            startSpectrumAnimation();
            return;
        }
        stopSpectrumAnimation();
        if (!state.playing) {
            spectrumView.showStaticBars();
        }
    }

    private void updateQueueFocusEffect(@NonNull View bottomSheet) {
        float progress = queueOpenProgress(bottomSheet);

        if (Math.abs(progress - lastQueueFocusProgress) < 0.01f) {
            return;
        }
        lastQueueFocusProgress = progress;
        if (queueFocusScrimView != null) {
            if (progress <= 0f) {
                queueFocusScrimView.setAlpha(0f);
                queueFocusScrimView.setVisibility(View.GONE);
                queueFocusScrimView.setClickable(false);
            } else {
                queueFocusScrimView.setVisibility(View.VISIBLE);
                queueFocusScrimView.setAlpha(progress * QUEUE_MAX_FOCUS_ALPHA);
                queueFocusScrimView.setClickable(true);
            }
        }
        applyPlayerBlur(progress);
    }

    private float queueOpenProgress(@NonNull View bottomSheet) {
        View parent = (View) bottomSheet.getParent();
        int collapsedTop;
        int halfExpandedTop;
        float progress;

        if (parent == null || queueSheetBehavior == null) {
            return 0f;
        }
        collapsedTop = Math.max(0, parent.getHeight() - queueSheetBehavior.getPeekHeight());
        halfExpandedTop = Math.max(0, (int) (parent.getHeight() * (1f - QUEUE_HALF_EXPANDED_RATIO)));
        if (collapsedTop <= halfExpandedTop) {
            return queueSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED ? 0f : 1f;
        }
        progress = (collapsedTop - bottomSheet.getTop()) / (float) (collapsedTop - halfExpandedTop);
        return Math.max(0f, Math.min(1f, progress));
    }

    private void applyPlayerBlur(float progress) {
        if (playerScrollView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (progress <= 0f) {
            playerScrollView.setRenderEffect(null);
            return;
        }
        float radius = dp(QUEUE_MAX_BLUR_RADIUS_DP) * progress;

        playerScrollView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    private void startSpectrumAnimation() {
        if (spectrumAnimating || spectrumView == null) {
            return;
        }
        spectrumAnimating = true;
        spectrumView.removeCallbacks(spectrumFrameRunnable);
        spectrumView.post(spectrumFrameRunnable);
    }

    private void stopSpectrumAnimation() {
        spectrumAnimating = false;
        if (spectrumView != null) {
            spectrumView.removeCallbacks(spectrumFrameRunnable);
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static boolean sameText(TextView view, String value) {
        CharSequence current = view.getText();

        if (current == null) {
            return value == null || value.length() == 0;
        }
        return current.toString().contentEquals(value == null ? "" : value);
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

    private void applyQueueBottomInset(View queueSheet) {
        final int baseSheetBottomPadding = queueSheet.getPaddingBottom();
        final int baseListBottomPadding = playlistView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(queueSheet, (target, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            target.setPadding(
                    target.getPaddingLeft(),
                    target.getPaddingTop(),
                    target.getPaddingRight(),
                    baseSheetBottomPadding + systemBars.bottom + dp(20));
            playlistView.setPadding(
                    playlistView.getPaddingLeft(),
                    playlistView.getPaddingTop(),
                    playlistView.getPaddingRight(),
                    baseListBottomPadding + dp(56));
            return insets;
        });
        ViewCompat.requestApplyInsets(queueSheet);
    }

    private static int resolveStatusIcon(PlayerController.PlayerUiState state) {
        if (state.playing) {
            return R.drawable.ic_note;
        }
        if (state.paused) {
            return R.drawable.ic_pause;
        }
        if (state.loading) {
            return R.drawable.ic_sync;
        }
        return R.drawable.ic_note;
    }

    private static int progressValue(int positionMs, int durationMs) {
        if (positionMs <= 0 || durationMs <= 0) {
            return 0;
        }
        if (positionMs >= durationMs) {
            return 1000;
        }
        return (int) ((positionMs * 1000L) / durationMs);
    }

    private static String formatTime(int millis) {
        if (millis <= 0) {
            return "00:00";
        }
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();

        if (minutes < 10) {
            builder.append('0');
        }
        builder.append(minutes).append(':');
        if (seconds < 10) {
            builder.append('0');
        }
        builder.append(seconds);
        return builder.toString();
    }
}
