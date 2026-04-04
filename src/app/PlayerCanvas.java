package app;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

public final class PlayerCanvas extends Canvas {
    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_LOADING = 1;
    public static final int STATUS_PLAYING = 2;
    public static final int STATUS_ERROR = 3;

    public static final int PRIMARY_NONE = 0;
    public static final int PRIMARY_PLAY = 1;
    public static final int PRIMARY_STOP = 2;

    private static final int MENU_ITEM_OPEN = 0;
    private static final int MENU_ITEM_OPEN_LIST = 1;
    private static final int MENU_ITEM_LOOP = 2;
    private static final int MENU_ITEM_EXIT = 3;
    private static final int PRESS_TARGET_NONE = 0;
    private static final int PRESS_TARGET_PRIMARY = 1;
    private static final int PRESS_TARGET_PREVIOUS = 2;
    private static final int PRESS_TARGET_NEXT = 3;
    private static final int PRIMARY_BUTTON_PLAY_OFFSET_Y = -2;
    private static final int PRIMARY_BUTTON_STOP_OFFSET_Y = -1;
    private static final int MENU_ITEM_COUNT = 4;
    private static final int PLAYLIST_VISIBLE_ROWS = 7;
    private static final String[] EMPTY_PLAYLIST = new String[0];

    public static interface ActionHandler {
        void onPrimaryActionRequested();

        void onOpenRequested();

        void onOpenListRequested();

        void onLoopModeToggleRequested();

        void onPlaylistRequested();

        void onPlaylistItemRequested(int index);

        void onPreviousTrackRequested();

        void onNextTrackRequested();

        void onExitRequested();

        void onPlaybackCanvasVisibilityChanged(boolean visible);
    }

    public static final class State {
        public final String appTitle;
        public final String title;
        public final String copyright;
        public final String fileName;
        public final String statusText;
        public final int statusKind;
        public final int primaryAction;
        public final boolean hasLoop;
        public final boolean loopingIndefinitely;
        public final boolean trackLoaded;
        public final boolean primaryEnabled;
        public final long positionMillis;
        public final long durationMillis;
        public final int[] spectrumBars;
        public final String[] playlistTitles;
        public final int playlistSelectedIndex;
        public final int playlistCurrentIndex;
        public final boolean playlistEmpty;
        public final String loopModeLabel;

        public State(
                String appTitle,
                String title,
                String copyright,
                String fileName,
                String statusText,
                int statusKind,
                int primaryAction,
                boolean hasLoop,
                boolean loopingIndefinitely,
                boolean trackLoaded,
                boolean primaryEnabled,
                long positionMillis,
                long durationMillis,
                int[] spectrumBars,
                String[] playlistTitles,
                int playlistSelectedIndex,
                int playlistCurrentIndex,
                boolean playlistEmpty,
                String loopModeLabel) {
            this.appTitle = safeText(appTitle, "MLD Player");
            this.title = safeText(title, "");
            this.copyright = safeText(copyright, "");
            this.fileName = safeText(fileName, "");
            this.statusText = safeText(statusText, "");
            this.statusKind = statusKind;
            this.primaryAction = primaryAction;
            this.hasLoop = hasLoop;
            this.loopingIndefinitely = loopingIndefinitely;
            this.trackLoaded = trackLoaded;
            this.primaryEnabled = primaryEnabled;
            this.positionMillis = positionMillis;
            this.durationMillis = durationMillis;
            this.spectrumBars = copySpectrumBars(spectrumBars);
            this.playlistTitles = playlistTitles == null ? EMPTY_PLAYLIST : playlistTitles;
            this.playlistSelectedIndex = playlistSelectedIndex;
            this.playlistCurrentIndex = playlistCurrentIndex;
            this.playlistEmpty = playlistEmpty;
            this.loopModeLabel = safeText(loopModeLabel, "Loop One");
        }
    }

    private final ActionHandler handler;
    private final PlayerSkinAssets skinAssets = new PlayerSkinAssets();

    private State state = new State(
            "MLD Player",
            "MLD Player",
            "",
            "",
            "Open a file to begin",
            STATUS_STOPPED,
            PRIMARY_NONE,
            false,
            false,
            false,
            false,
            0L,
            -1L,
            null,
            EMPTY_PLAYLIST,
            -1,
            -1,
            true,
            "Loop One");

    private Image landscapeBuffer;
    private Graphics landscapeGraphics;
    private int bufferWidth;
    private int bufferHeight;
    private int animationFrame;
    private boolean menuVisible;
    private boolean playlistVisible;
    private int menuIndex;
    private int playlistFocusIndex = -1;

    private int menuButtonX;
    private int menuButtonY;
    private int menuButtonWidth;
    private int menuButtonHeight;
    private int menuPopupX;
    private int menuPopupY;
    private int menuPopupWidth;
    private int menuPopupHeight;
    private int menuItemHeight;
    private int playlistPopupX;
    private int playlistPopupY;
    private int playlistPopupWidth;
    private int playlistPopupHeight;
    private int playlistItemHeight;
    private int playlistScrollStart;
    private int playlistVisibleCount;
    private int primaryButtonX;
    private int primaryButtonY;
    private int primaryButtonWidth;
    private int primaryButtonHeight;
    private int previousButtonX;
    private int previousButtonY;
    private int previousButtonWidth;
    private int previousButtonHeight;
    private int nextButtonX;
    private int nextButtonY;
    private int nextButtonWidth;
    private int nextButtonHeight;
    private int rightSoftKeyX;
    private int rightSoftKeyY;
    private int rightSoftKeyWidth;
    private int rightSoftKeyHeight;
    private int titleScrollWidth;
    private int copyrightScrollWidth;
    private final int[] spectrumLevels = new int[PlayerTheme.BAR_PROFILE.length];
    private final int[] spectrumPeaks = new int[PlayerTheme.BAR_PROFILE.length];
    private int pressTarget;
    private boolean primaryKeyHeld;
    private boolean previousKeyHeld;
    private boolean nextKeyHeld;

    public PlayerCanvas(ActionHandler handler) {
        this.handler = handler;
        setFullScreenMode(true);
    }

    public void setState(State nextState) {
        if (nextState == null) {
            nextState = new State(
                    "MLD Player",
                    "MLD Player",
                    "",
                    "",
                    "Open a file to begin",
                    STATUS_STOPPED,
                    PRIMARY_NONE,
                    false,
                    false,
                    false,
                    false,
                    0L,
                    -1L,
                    null,
                    EMPTY_PLAYLIST,
                    -1,
                    -1,
                    true,
                    "Loop One");
        }
        if (needsAnimation(nextState)) {
            animationFrame = (animationFrame + 1) % 2048;
        } else {
            animationFrame = 0;
        }
        if (nextState.statusKind == STATUS_LOADING) {
            menuVisible = false;
            playlistVisible = false;
        }
        state = nextState;
        if (!state.primaryEnabled || state.primaryAction == PRIMARY_NONE) {
            if (pressTarget == PRESS_TARGET_PRIMARY) {
                pressTarget = PRESS_TARGET_NONE;
            }
            primaryKeyHeld = false;
        }
        if (!playlistVisible) {
            playlistFocusIndex = state.playlistSelectedIndex;
        } else if (playlistFocusIndex < 0 || playlistFocusIndex >= playlistItemCount()) {
            playlistFocusIndex = state.playlistSelectedIndex;
        }
        repaint();
    }

    public void toggleMenu() {
        playlistVisible = false;
        menuVisible = !menuVisible;
        repaint();
    }

    public void closeMenu() {
        if (menuVisible) {
            menuVisible = false;
            repaint();
        }
    }

    public void closePlaylist() {
        if (playlistVisible) {
            playlistVisible = false;
            playlistFocusIndex = state.playlistSelectedIndex;
            repaint();
        }
    }

    public void togglePlaylist() {
        menuVisible = false;
        playlistVisible = !playlistVisible;
        if (playlistVisible) {
            playlistFocusIndex = state.playlistSelectedIndex;
        }
        if (playlistVisible && handler != null) {
            handler.onPlaylistRequested();
        }
        repaint();
    }

    protected void showNotify() {
        if (handler != null) {
            handler.onPlaybackCanvasVisibilityChanged(true);
        }
    }

    protected void hideNotify() {
        menuVisible = false;
        playlistVisible = false;
        playlistFocusIndex = state.playlistSelectedIndex;
        pressTarget = PRESS_TARGET_NONE;
        primaryKeyHeld = false;
        if (handler != null) {
            handler.onPlaybackCanvasVisibilityChanged(false);
        }
    }

    public boolean needsAnimation() {
        return needsAnimation(state);
    }

    private boolean needsAnimation(State candidate) {
        Font titleFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        Font metaFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        Font listFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int titleWidth = PlayerTheme.DESIGN_WIDTH - (18 * 2);
        int metaWidth = titleWidth - 22;
        int playlistTextWidth = 122;

        return candidate.statusKind == STATUS_PLAYING
                || candidate.statusKind == STATUS_LOADING
                || titleFont.stringWidth(resolveTitle(candidate)) > titleWidth
                || metaFont.stringWidth(resolveSecondaryText(candidate)) > metaWidth
                || selectedPlaylistTextWidth(candidate, listFont) > playlistTextWidth;
    }

    protected void keyPressed(int keyCode) {
        int action = gameActionOrNone(keyCode);

        if (isLeftSoftKey(keyCode)) {
            if (playlistVisible) {
                playlistVisible = false;
                menuVisible = true;
                repaint();
            } else if (menuVisible) {
                closeMenu();
            } else {
                toggleMenu();
            }
            return;
        }

        if (isRightSoftKey(keyCode)) {
            if (menuVisible) {
                menuVisible = false;
                togglePlaylist();
            } else if (playlistVisible) {
                closePlaylist();
            } else {
                togglePlaylist();
            }
            return;
        }

        if (menuVisible) {
            if (action == UP) {
                moveMenuSelection(-1);
                return;
            }
            if (action == DOWN) {
                moveMenuSelection(1);
                return;
            }
            if (isFireKey(keyCode)) {
                activateMenuSelection();
                return;
            }
        }

        if (playlistVisible) {
            if (action == UP) {
                movePlaylistSelection(-1);
                return;
            }
            if (action == DOWN) {
                movePlaylistSelection(1);
                return;
            }
            if (isFireKey(keyCode)) {
                activatePlaylistSelection();
                return;
            }
        }

        if (action == LEFT) {
            armAndTriggerPreviousKey();
            return;
        }

        if (action == RIGHT) {
            armAndTriggerNextKey();
            return;
        }

        if (isFireKey(keyCode)) {
            armAndTriggerPrimaryKey();
        }
    }

    protected void keyReleased(int keyCode) {
        int action = gameActionOrNone(keyCode);

        if (isFireKey(keyCode)) {
            primaryKeyHeld = false;
        }
        if (action == LEFT) {
            previousKeyHeld = false;
        } else if (action == RIGHT) {
            nextKeyHeld = false;
        }

        if (pressTarget == PRESS_TARGET_PRIMARY && isFireKey(keyCode)) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
            return;
        }
        if (pressTarget == PRESS_TARGET_PREVIOUS && action == LEFT) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
            return;
        }
        if (pressTarget == PRESS_TARGET_NEXT && action == RIGHT) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
        }
    }

    protected void pointerPressed(int x, int y) {
        int landscapeX = toLandscapeX(y);
        int landscapeY = toLandscapeY(x);

        if (menuVisible) {
            if (contains(rightSoftKeyX, rightSoftKeyY, rightSoftKeyWidth, rightSoftKeyHeight, landscapeX, landscapeY)) {
                menuVisible = false;
                togglePlaylist();
                return;
            }
            if (contains(menuButtonX, menuButtonY, menuButtonWidth, menuButtonHeight, landscapeX, landscapeY)) {
                menuVisible = false;
                repaint();
                return;
            }
            if (contains(menuPopupX, menuPopupY, menuPopupWidth, menuPopupHeight, landscapeX, landscapeY)) {
                int selected = (landscapeY - menuPopupY) / max(1, menuItemHeight);
                selected = clamp(MENU_ITEM_OPEN, MENU_ITEM_EXIT, selected);
                menuIndex = selected;
                activateMenuSelection();
                return;
            }
            menuVisible = false;
            repaint();
            return;
        }

        if (playlistVisible) {
            if (contains(menuButtonX, menuButtonY, menuButtonWidth, menuButtonHeight, landscapeX, landscapeY)) {
                playlistVisible = false;
                toggleMenu();
                return;
            }
            if (contains(rightSoftKeyX, rightSoftKeyY, rightSoftKeyWidth, rightSoftKeyHeight, landscapeX, landscapeY)) {
                playlistVisible = false;
                repaint();
                return;
            }
            if (contains(playlistPopupX, playlistPopupY, playlistPopupWidth, playlistPopupHeight, landscapeX, landscapeY)) {
                int itemCount = playlistItemCount();
                int selected;
                if (itemCount <= 0) {
                    playlistVisible = false;
                    repaint();
                    return;
                }
                selected = playlistScrollStart + ((landscapeY - playlistPopupY) / max(1, playlistItemHeight));
                selected = clamp(0, itemCount - 1, selected);
                if (selected >= 0 && selected < itemCount) {
                    activatePlaylistSelectionAt(selected);
                }
                return;
            }
            playlistVisible = false;
            repaint();
            return;
        }

        if (contains(menuButtonX, menuButtonY, menuButtonWidth, menuButtonHeight, landscapeX, landscapeY)) {
            playlistVisible = false;
            menuVisible = true;
            menuIndex = MENU_ITEM_OPEN;
            repaint();
            return;
        }

        if (contains(rightSoftKeyX, rightSoftKeyY, rightSoftKeyWidth, rightSoftKeyHeight, landscapeX, landscapeY)) {
            togglePlaylist();
            return;
        }

        if (contains(primaryButtonX, primaryButtonY, primaryButtonWidth, primaryButtonHeight, landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_PRIMARY;
            repaint();
            return;
        }
        if (contains(previousButtonX, previousButtonY, previousButtonWidth, previousButtonHeight, landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_PREVIOUS;
            repaint();
            return;
        }
        if (contains(nextButtonX, nextButtonY, nextButtonWidth, nextButtonHeight, landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_NEXT;
            repaint();
        }
    }

    protected void pointerReleased(int x, int y) {
        int landscapeX;
        int landscapeY;

        if (pressTarget == PRESS_TARGET_NONE) {
            return;
        }

        landscapeX = toLandscapeX(y);
        landscapeY = toLandscapeY(x);
        if (pressTarget == PRESS_TARGET_PRIMARY
                && contains(primaryButtonX, primaryButtonY, primaryButtonWidth, primaryButtonHeight, landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
            triggerPrimaryAction();
            return;
        }
        if (pressTarget == PRESS_TARGET_PREVIOUS
                && contains(previousButtonX, previousButtonY, previousButtonWidth, previousButtonHeight, landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
            triggerPreviousAction();
            return;
        }
        if (pressTarget == PRESS_TARGET_NEXT
                && contains(nextButtonX, nextButtonY, nextButtonWidth, nextButtonHeight, landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
            triggerNextAction();
            return;
        }
        pressTarget = PRESS_TARGET_NONE;
        repaint();
    }

    protected void pointerDragged(int x, int y) {
        int landscapeX;
        int landscapeY;

        landscapeX = toLandscapeX(y);
        landscapeY = toLandscapeY(x);
        if (!containsPressTarget(landscapeX, landscapeY)) {
            pressTarget = PRESS_TARGET_NONE;
            repaint();
        }
    }

    protected void paint(Graphics g) {
        int portraitWidth = getWidth();
        int portraitHeight = getHeight();
        int landscapeWidth = portraitHeight;
        int landscapeHeight = portraitWidth;

        ensureBuffer(landscapeWidth, landscapeHeight);
        renderLandscape(landscapeGraphics, landscapeWidth, landscapeHeight);

        g.drawRegion(
                landscapeBuffer,
                0,
                0,
                landscapeWidth,
                landscapeHeight,
                Sprite.TRANS_ROT270,
                0,
                0,
                Graphics.TOP | Graphics.LEFT);
    }

    private void ensureBuffer(int width, int height) {
        if (landscapeBuffer != null && bufferWidth == width && bufferHeight == height) {
            return;
        }
        landscapeBuffer = Image.createImage(width, height);
        landscapeGraphics = landscapeBuffer.getGraphics();
        bufferWidth = width;
        bufferHeight = height;
    }

    private void renderLandscape(Graphics g, int width, int height) {
        Font topFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        Font titleFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        Font metaFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        Font buttonFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        Font bottomFont = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int padding = 18;
        int topBarHeight = 30;
        int bottomBarHeight = 28;
        int contentBottom = height - bottomBarHeight;
        int titleY = 42;
        int subtitleY;
        int spectrumY = 92;
        int spectrumHeight = 46;
        int progressTextY = 153;
        int progressBarY = 176;
        int controlsY = 179;
        int controlsHeight = 33;
        int spectrumWidth = max(80, width - (padding * 2));
        String titleText = resolveTitle();
        String secondaryText = resolveSecondaryText();
        int titleTextX = padding;
        int titleTextY = titleY;
        int titleTextWidth = width - (padding * 2);
        int secondaryTextX = padding + 22;
        int secondaryTextY;
        int secondaryTextWidth = width - (padding * 2) - 22;

        skinAssets.ensureLoaded();

        subtitleY = titleY + titleFont.getHeight() + 3;
        secondaryTextY = subtitleY;
        menuButtonWidth = 52;
        menuButtonHeight = 18;
        menuButtonX = width - padding - menuButtonWidth;
        menuButtonY = 6;
        menuItemHeight = 20;
        menuPopupWidth = 118;
        menuPopupHeight = menuItemHeight * MENU_ITEM_COUNT;
        menuPopupX = width - padding - menuPopupWidth;
        menuPopupY = topBarHeight - 1;
        playlistItemHeight = 18;
        playlistVisibleCount = playlistVisibleRowCount(height, topBarHeight, bottomBarHeight);
        playlistPopupWidth = 154;
        playlistPopupHeight = max(playlistItemHeight + 8, (playlistVisibleCount * playlistItemHeight) + 8);
        playlistPopupX = width - padding - playlistPopupWidth;
        playlistPopupY = max(topBarHeight + 6, height - bottomBarHeight - playlistPopupHeight - 6);
        playlistScrollStart = playlistScrollStart(playlistVisibleCount);
        primaryButtonWidth = 30;
        primaryButtonHeight = controlsHeight;
        primaryButtonX = (width - primaryButtonWidth) / 2;
        primaryButtonY = controlsY;
        previousButtonWidth = 39;
        previousButtonHeight = controlsHeight;
        previousButtonX = primaryButtonX - 68;
        previousButtonY = controlsY;
        nextButtonWidth = 39;
        nextButtonHeight = controlsHeight;
        nextButtonX = primaryButtonX + primaryButtonWidth + 38;
        nextButtonY = controlsY;
        rightSoftKeyWidth = 58;
        rightSoftKeyHeight = bottomBarHeight;
        rightSoftKeyX = width - rightSoftKeyWidth;
        rightSoftKeyY = height - bottomBarHeight;
        titleScrollWidth = titleFont.stringWidth(titleText);
        copyrightScrollWidth = metaFont.stringWidth(secondaryText);

        drawBackground(g, width, height, topBarHeight, contentBottom);
        drawTopBar(g, width, topBarHeight, topFont);
        drawTitleBlock(
                g,
                titleText,
                secondaryText,
                titleFont,
                metaFont,
                titleTextX,
                titleTextY,
                titleTextWidth,
                secondaryTextX,
                secondaryTextY,
                secondaryTextWidth);
        updateSpectrumLevels();
        drawSpectrum(g, padding, spectrumY, spectrumWidth, spectrumHeight);
        drawProgress(g, padding, progressTextY, progressBarY, spectrumWidth, metaFont);
        drawControls(g, width, controlsY, controlsHeight, buttonFont);
        drawBottomBar(g, width, height, bottomBarHeight, bottomFont);

        if (menuVisible) {
            drawMenu(g, topFont);
        } else if (playlistVisible) {
            drawPlaylist(g, bottomFont);
        }
    }

    private void drawBackground(Graphics g, int width, int height, int topBarHeight, int contentBottom) {
        int lowerPanelY = 146;
        int lowerPanelHeight = max(0, contentBottom - lowerPanelY - 1);
        int i;
        int scanY;

        g.setColor(PlayerTheme.COLOR_BACKGROUND);
        g.fillRect(0, 0, width, height);

        g.setColor(PlayerTheme.COLOR_BACKGROUND_SOFT);
        g.fillRect(0, topBarHeight, width, 48);

        g.setColor(PlayerTheme.COLOR_BACKGROUND_MID);
        g.fillRect(0, lowerPanelY, width, lowerPanelHeight);

        g.setColor(PlayerTheme.COLOR_LINE);
        for (i = 48; i < contentBottom - 16; i += 24) {
            g.drawLine(16, i, width - 16, i);
        }
        for (i = 32; i < width; i += 32) {
            g.drawLine(i, 30, i, contentBottom - 4);
        }

        g.setColor(PlayerTheme.COLOR_LINE_ACCENT);
        g.drawLine(18, 78, width - 18, 78);
        g.drawLine(18, lowerPanelY - 2, width - 18, lowerPanelY - 2);
        g.drawLine(18, 176, width - 18, 176);

        scanY = 89 + (animationFrame % 10);
        g.setColor(0x1A2029);
        g.drawLine(18, scanY, width - 18, scanY);
    }

    private void drawTopBar(Graphics g, int width, int topBarHeight, Font topFont) {
        int statusWidth;
        int statusX;
        int statusY = 6;
        int statusHeight = 18;
        int menuTextY = menuButtonY + 4;

        drawRepeatedImage(g, skinAssets.topBar(), 0, 0, width, topBarHeight, PlayerTheme.COLOR_BACKGROUND);

        g.setFont(topFont);
        g.setColor(PlayerTheme.COLOR_TEXT);
        g.drawString(state.appTitle, 12, 7, Graphics.TOP | Graphics.LEFT);

        statusWidth = max(62, topFont.stringWidth(statusLabel()) + 18);
        statusX = max(92, menuButtonX - 8 - statusWidth);
        drawStatusPill(g, statusX, statusY, statusWidth, statusHeight);
        g.setColor(statusTextColor());
        g.drawString(statusLabel(), statusX + (statusWidth / 2), statusY + 4, Graphics.TOP | Graphics.HCENTER);

        g.setColor(PlayerTheme.COLOR_BUTTON_FILL);
        g.fillRoundRect(menuButtonX, menuButtonY, menuButtonWidth, menuButtonHeight, 8, 8);
        g.setColor(PlayerTheme.COLOR_BUTTON_BORDER);
        g.drawRoundRect(menuButtonX, menuButtonY, menuButtonWidth, menuButtonHeight, 8, 8);
        g.setColor(PlayerTheme.COLOR_TEXT_WEAK);
        g.drawString("MENU", menuButtonX + (menuButtonWidth / 2), menuTextY, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawStatusPill(Graphics g, int x, int y, int width, int height) {
        int fillColor = PlayerTheme.COLOR_STATUS_STOP;

        if (state.statusKind == STATUS_PLAYING) {
            fillColor = PlayerTheme.COLOR_STATUS_PLAY;
        } else if (state.statusKind == STATUS_LOADING) {
            fillColor = PlayerTheme.COLOR_STATUS_LOAD;
        } else if (state.statusKind == STATUS_ERROR) {
            fillColor = PlayerTheme.COLOR_STATUS_ERROR;
        }

        g.setColor(fillColor);
        g.fillRoundRect(x, y, width, height, 10, 10);
    }

    private int statusTextColor() {
        if (state.statusKind == STATUS_PLAYING) {
            return PlayerTheme.COLOR_STATUS_TEXT_DARK;
        }
        return PlayerTheme.COLOR_STATUS_TEXT;
    }

    private void drawTitleBlock(
            Graphics g,
            String titleText,
            String secondaryText,
            Font titleFont,
            Font metaFont,
            int titleX,
            int titleY,
            int titleWidth,
            int subtitleX,
            int subtitleY,
            int subtitleWidth) {

        g.setFont(titleFont);
        g.setColor(PlayerTheme.COLOR_TEXT);
        drawScrollingLine(g, titleText, titleFont, titleX, titleY, titleWidth, titleScrollWidth);

        g.setFont(metaFont);
        g.setColor(PlayerTheme.COLOR_TEXT_WEAK);
        drawMetaIcon(g, subtitleX - 22, subtitleY + 1);
        drawScrollingLine(g, secondaryText, metaFont, subtitleX, subtitleY, subtitleWidth, copyrightScrollWidth);
    }

    private void drawSpectrum(Graphics g, int x, int y, int width, int height) {
        int i;
        int barCount = PlayerTheme.BAR_PROFILE.length;
        int barWidth = 7;
        int gap = 9;
        int usedWidth = (barWidth * barCount) + ((barCount - 1) * gap);
        int startX = x + max(0, (width - usedWidth) / 2);
        int innerTop = y + 4;
        int innerBottom = y + height - 4;
        int segmentHeight = 3;
        int segmentGap = 1;
        int maxSegments = max(1, ((innerBottom - innerTop) + segmentGap) / (segmentHeight + segmentGap));
        int drawableSegments = max(1, maxSegments - 1);

        g.setColor(PlayerTheme.COLOR_SPECTRUM_BASE);
        g.fillRect(x, y, width, height);
        g.setColor(PlayerTheme.COLOR_SPECTRUM_EDGE);
        g.drawLine(x, y, x + width - 1, y);
        g.drawLine(x, y + height - 1, x + width - 1, y + height - 1);
        g.setColor(PlayerTheme.COLOR_SPECTRUM_GRID);
        for (i = 1; i <= 3; i++) {
            int gridY = y + ((height * i) / 4);
            g.drawLine(x, gridY, x + width - 1, gridY);
        }

        for (i = 0; i < barCount; i++) {
            int level = spectrumLevels[i];
            int peak = spectrumPeaks[i];
            int barX = startX + (i * (barWidth + gap));
            int litSegments = clamp(0, drawableSegments, (level * drawableSegments + 99) / 100);
            int peakSegment = peak <= 0
                    ? -1
                    : clamp(0, drawableSegments - 1, ((peak * drawableSegments + 99) / 100) - 1);
            int segment;

            for (segment = 0; segment < litSegments; segment++) {
                int segY = innerBottom - segmentHeight - (segment * (segmentHeight + segmentGap));
                int distanceFromTop = (litSegments - 1) - segment;

                if (distanceFromTop <= 1) {
                    g.setColor(PlayerTheme.COLOR_SPECTRUM_HIGH);
                } else {
                    g.setColor(PlayerTheme.COLOR_SPECTRUM_LOW);
                }
                g.fillRect(barX, segY, barWidth, segmentHeight);
            }

            if (state.trackLoaded) {
                peakSegment = max(peakSegment, defaultPeakSegment(i, drawableSegments));
            }

            if (peakSegment >= 0) {
                int peakY = innerBottom - 1 - (peakSegment * (segmentHeight + segmentGap));
                g.setColor(PlayerTheme.COLOR_SPECTRUM_CAP);
                g.drawLine(barX, peakY, barX + barWidth - 1, peakY);
            }
        }
    }

    private void drawProgress(Graphics g, int x, int textY, int barY, int width, Font font) {
        int filled = 0;

        g.setFont(font);
        g.setColor(PlayerTheme.COLOR_TEXT_WEAK);
        g.drawString(progressLeftTime(), x, textY, Graphics.TOP | Graphics.LEFT);
        g.drawString(progressRightTime(), x + width, textY, Graphics.TOP | Graphics.RIGHT);

        if (state.statusKind == STATUS_LOADING) {
            int segmentWidth = max(26, width / 5);
            int travel = max(1, width - segmentWidth);
            int segmentX = x + ((animationFrame * travel) / 35);
            g.setColor(PlayerTheme.COLOR_PROGRESS_HEAD);
            g.drawLine(segmentX, barY, segmentX + segmentWidth - 1, barY);
            return;
        }

        if (state.durationMillis > 0L) {
            filled = (int) ((state.positionMillis * width) / maxLong(1L, state.durationMillis));
            filled = clamp(0, width, filled);
        }

        if (filled > 0) {
            g.setColor(PlayerTheme.COLOR_PROGRESS_HEAD);
            g.drawLine(x, barY, x + filled - 1, barY);
        }
    }

    private void drawControls(Graphics g, int width, int controlsY, int controlsHeight, Font buttonFont) {
        drawTransportDecoration(g, previousButtonX, previousButtonY, previousButtonWidth, previousButtonHeight, true);
        drawPrimaryButton(g, primaryButtonX, primaryButtonY, buttonFont);
        drawTransportDecoration(g, nextButtonX, nextButtonY, nextButtonWidth, nextButtonHeight, false);
    }

    private void drawPrimaryButton(Graphics g, int x, int y, Font fallbackFont) {
        Image image = null;
        boolean pressed = pressTarget == PRESS_TARGET_PRIMARY;

        if (state.primaryAction == PRIMARY_STOP) {
            image = pressed ? skinAssets.stopEnabled() : skinAssets.stopDisabled();
        } else {
            image = pressed ? skinAssets.playEnabled() : skinAssets.playDisabled();
        }

        if (image != null) {
            drawCenteredImage(g, image, x, y, primaryButtonWidth, primaryButtonHeight, 0, primaryButtonImageOffsetY(image));
            return;
        }

        if (state.primaryEnabled) {
            g.setColor(PlayerTheme.COLOR_BUTTON_FILL);
        } else {
            g.setColor(PlayerTheme.COLOR_BUTTON_DISABLED);
        }
        g.fillRoundRect(x, y, primaryButtonWidth, primaryButtonHeight, 10, 10);
        g.setColor(PlayerTheme.COLOR_BUTTON_BORDER);
        g.drawRoundRect(x, y, primaryButtonWidth, primaryButtonHeight, 10, 10);

        g.setFont(fallbackFont);
        g.setColor(state.primaryEnabled ? PlayerTheme.COLOR_BUTTON_TEXT : PlayerTheme.COLOR_BUTTON_TEXT_DISABLED);
        g.drawString(primaryLabel(),
                x + (primaryButtonWidth / 2),
                y + 4,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawTransportDecoration(Graphics g, int x, int y, int width, int height, boolean leftArrow) {
        Image image;

        if (leftArrow) {
            image = pressTarget == PRESS_TARGET_PREVIOUS ? skinAssets.prevEnabled() : skinAssets.prevDisabled();
        } else {
            image = pressTarget == PRESS_TARGET_NEXT ? skinAssets.nextEnabled() : skinAssets.nextDisabled();
        }

        if (image != null) {
            drawCenteredImage(g, image, x, y, width, height, 0, 0);
            return;
        }

        drawFallbackTransportDecoration(g, x, y, width - 3, height - 13, leftArrow, leftArrow
                ? pressTarget == PRESS_TARGET_PREVIOUS
                : pressTarget == PRESS_TARGET_NEXT);
    }

    private void drawFallbackTransportDecoration(Graphics g, int x, int y, int width, int height, boolean leftArrow, boolean pressed) {
        int arrowMidY = y + (height / 2);
        int arrowStartX = x + 10;
        int arrowEndX = x + width - 10;

        g.setColor(pressed ? PlayerTheme.COLOR_MENU_SELECTED : PlayerTheme.COLOR_DECORATION);
        g.fillRoundRect(x, y, width, height, 8, 8);
        g.setColor(pressed ? PlayerTheme.COLOR_MENU_SELECTED_TEXT : PlayerTheme.COLOR_LINE_ACCENT);
        g.drawRoundRect(x, y, width, height, 8, 8);

        if (leftArrow) {
            g.drawLine(arrowEndX, y + 5, arrowStartX, arrowMidY);
            g.drawLine(arrowStartX, arrowMidY, arrowEndX, y + height - 5);
        } else {
            g.drawLine(arrowStartX, y + 5, arrowEndX, arrowMidY);
            g.drawLine(arrowEndX, arrowMidY, arrowStartX, y + height - 5);
        }
    }

    private void drawBottomBar(Graphics g, int width, int height, int bottomBarHeight, Font font) {
        int y = height - bottomBarHeight;
        String rightLabel = softkeyRightLabel();
        int centerX = width / 2;

        drawRepeatedImage(g, skinAssets.bottomBar(), 0, y, width, bottomBarHeight, PlayerTheme.COLOR_BACKGROUND);

        g.setFont(font);
        g.setColor(PlayerTheme.COLOR_TEXT);
        g.drawString(menuVisible ? "Close" : "Menu", 8, y + 5, Graphics.TOP | Graphics.LEFT);
        g.drawString(trimToWidth(state.statusText, font, width - 120), centerX, y + 5, Graphics.TOP | Graphics.HCENTER);
        g.setColor(PlayerTheme.COLOR_TEXT);
        g.drawString(rightLabel, width - 8, y + 5, Graphics.TOP | Graphics.RIGHT);
    }

    private void drawMenu(Graphics g, Font font) {
        int i;

        g.setColor(PlayerTheme.COLOR_MENU_FILL);
        g.fillRoundRect(menuPopupX, menuPopupY, menuPopupWidth, menuPopupHeight, 8, 8);
        g.setColor(PlayerTheme.COLOR_MENU_BORDER);
        g.drawRoundRect(menuPopupX, menuPopupY, menuPopupWidth, menuPopupHeight, 8, 8);
        g.setFont(font);

        for (i = 0; i < MENU_ITEM_COUNT; i++) {
            int itemY = menuPopupY + (i * menuItemHeight);
            boolean selected = i == menuIndex;
            String label = menuItemLabel(i);

            if (selected) {
                g.setColor(PlayerTheme.COLOR_MENU_SELECTED);
                g.fillRoundRect(menuPopupX + 4, itemY + 2, menuPopupWidth - 8, menuItemHeight - 4, 6, 6);
                g.setColor(PlayerTheme.COLOR_MENU_SELECTED_TEXT);
            } else {
                g.setColor(PlayerTheme.COLOR_TEXT_WEAK);
            }
            g.drawString(label,
                    menuPopupX + 10,
                    itemY + 4,
                    Graphics.TOP | Graphics.LEFT);
        }
    }

    private void drawPlaylist(Graphics g, Font font) {
        int i;
        int itemCount = playlistItemCount();
        int textX = playlistPopupX + 18;
        int textWidth = playlistPopupWidth - 28;

        g.setColor(PlayerTheme.COLOR_MENU_FILL);
        g.fillRoundRect(playlistPopupX, playlistPopupY, playlistPopupWidth, playlistPopupHeight, 8, 8);
        g.setColor(PlayerTheme.COLOR_MENU_BORDER);
        g.drawRoundRect(playlistPopupX, playlistPopupY, playlistPopupWidth, playlistPopupHeight, 8, 8);
        g.setFont(font);

        if (itemCount == 0) {
            g.setColor(PlayerTheme.COLOR_TEXT_DIM);
            g.drawString("No List", playlistPopupX + 10, playlistPopupY + 6, Graphics.TOP | Graphics.LEFT);
            return;
        }

        for (i = 0; i < playlistVisibleCount; i++) {
            int entryIndex = playlistScrollStart + i;
            int itemY = playlistPopupY + 4 + (i * playlistItemHeight);
            boolean selected = entryIndex == playlistFocusedIndex();
            boolean current = entryIndex == state.playlistCurrentIndex;
            String label;

            if (entryIndex >= itemCount) {
                break;
            }

            label = playlistTitleAt(entryIndex);
            if (selected) {
                g.setColor(PlayerTheme.COLOR_MENU_SELECTED);
                g.fillRoundRect(playlistPopupX + 4, itemY, playlistPopupWidth - 8, playlistItemHeight - 2, 6, 6);
                g.setColor(PlayerTheme.COLOR_MENU_SELECTED_TEXT);
            } else {
                g.setColor(current ? PlayerTheme.COLOR_TEXT : PlayerTheme.COLOR_TEXT_WEAK);
            }

            if (current) {
                int markerY = itemY + ((playlistItemHeight - 8) / 2);
                int markerColor = selected ? PlayerTheme.COLOR_MENU_SELECTED_TEXT : PlayerTheme.COLOR_PROGRESS_HEAD;
                g.setColor(markerColor);
                g.fillRect(playlistPopupX + 8, markerY, 4, 8);
                g.setColor(selected ? PlayerTheme.COLOR_MENU_SELECTED_TEXT : PlayerTheme.COLOR_TEXT_WEAK);
            }

            if (selected) {
                drawScrollingLine(g, label, font, textX, itemY + 2, textWidth, font.stringWidth(label));
            } else {
                g.drawString(trimToWidthWithEllipsis(label, font, textWidth), textX, itemY + 2, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    private void drawRepeatedImage(
            Graphics g,
            Image image,
            int x,
            int y,
            int width,
            int height,
            int fallbackColor) {
        if (image == null) {
            g.setColor(fallbackColor);
            g.fillRect(x, y, width, height);
            return;
        }

        {
            int remaining = width;
            int drawX = x;
            int tileWidth = image.getWidth();
            int tileHeight = image.getHeight();

            while (remaining > 0) {
                g.drawImage(image, drawX, y, Graphics.TOP | Graphics.LEFT);
                drawX += tileWidth;
                remaining -= tileWidth;
            }
            if (height > tileHeight) {
                g.setColor(fallbackColor);
                g.fillRect(x, y + tileHeight, width, height - tileHeight);
            }
        }
    }

    private void drawMetaIcon(Graphics g, int x, int y) {
        Image icon = skinAssets.metaIcon();

        if (icon != null) {
            g.drawImage(icon, x, y, Graphics.TOP | Graphics.LEFT);
            return;
        }

        g.setColor(PlayerTheme.COLOR_LINE_ACCENT);
        g.drawRect(x + 2, y + 2, 10, 10);
    }

    private void drawCenteredImage(Graphics g, Image image, int x, int y, int width, int height, int offsetX, int offsetY) {
        int drawX;
        int drawY;

        if (image == null) {
            return;
        }

        drawX = x + ((width - image.getWidth()) / 2) + offsetX;
        drawY = y + ((height - image.getHeight()) / 2) + offsetY;
        g.drawImage(image, drawX, drawY, Graphics.TOP | Graphics.LEFT);
    }

    private void drawScrollingLine(Graphics g, String text, Font font, int x, int y, int width, int textWidth) {
        int offset;

        if (text == null) {
            text = "";
        }

        if (textWidth <= width) {
            g.drawString(text, x, y, Graphics.TOP | Graphics.LEFT);
            return;
        }

        offset = marqueeOffset(textWidth, width);
        g.setClip(x, y, width, font.getHeight());
        g.drawString(text, x - offset, y, Graphics.TOP | Graphics.LEFT);
        g.setClip(0, 0, bufferWidth, bufferHeight);
    }

    private int marqueeOffset(int textWidth, int availableWidth) {
        int overflow = textWidth - availableWidth;
        int pause = 6;
        int cycle = (overflow * 2) + (pause * 2);
        int phase;

        if (overflow <= 0) {
            return 0;
        }

        phase = animationFrame % max(1, cycle);
        if (phase < pause) {
            return 0;
        }
        phase -= pause;
        if (phase < overflow) {
            return phase;
        }
        phase -= overflow;
        if (phase < pause) {
            return overflow;
        }
        phase -= pause;
        return max(0, overflow - phase);
    }

    private void updateSpectrumLevels() {
        int i;

        for (i = 0; i < spectrumLevels.length; i++) {
            int target = targetSpectrumLevel(i);
            int current = spectrumLevels[i];
            int peak = spectrumPeaks[i];

            if (current < target) {
                current += max(2, (target - current + 2) / 3);
            } else if (current > target) {
                current -= max(1, (current - target + 5) / 7);
            }
            current = clamp(0, 88, current);

            if (current >= peak) {
                peak = current;
            } else {
                peak -= max(1, 2 + ((animationFrame + i) % 3));
                if (peak < current) {
                    peak = current;
                }
            }

            spectrumLevels[i] = current;
            spectrumPeaks[i] = clamp(0, 92, peak);
        }
    }

    private int targetSpectrumLevel(int index) {
        if (state.spectrumBars != null && index < state.spectrumBars.length) {
            return compressSpectrumLevel(state.spectrumBars[index]);
        }
        if (state.statusKind == STATUS_ERROR) {
            return clamp(4, 18, 6 + (index % 4));
        }
        return clamp(4, 14, 5 + (index % 3));
    }

    private int primaryButtonImageOffsetY(Image image) {
        int offset = state.primaryAction == PRIMARY_STOP ? PRIMARY_BUTTON_STOP_OFFSET_Y : PRIMARY_BUTTON_PLAY_OFFSET_Y;

        if (image != null && image.getHeight() < primaryButtonHeight) {
            offset += max(0, (primaryButtonHeight - image.getHeight()) / 3);
        }
        return offset;
    }

    private int compressSpectrumLevel(int rawLevel) {
        int level = clamp(0, 100, rawLevel);

        if (level <= 72) {
            return level;
        }
        if (level <= 88) {
            return 72 + (((level - 72) * 3) / 4);
        }
        return 84 + ((level - 88) / 3);
    }

    private int defaultPeakSegment(int index, int drawableSegments) {
        int idleLevel = 5 + ((index % 4) * 2);

        return clamp(0, drawableSegments - 1, ((idleLevel * drawableSegments + 99) / 100) - 1);
    }

    private int selectedPlaylistTextWidth(State candidate, Font font) {
        int focus;

        if (!playlistVisible || candidate == null || candidate.playlistTitles == null || candidate.playlistTitles.length == 0) {
            return 0;
        }

        focus = playlistFocusedIndex();
        if (focus < 0 || focus >= candidate.playlistTitles.length) {
            return 0;
        }
        return font.stringWidth(playlistTitleAt(focus));
    }

    private int playlistFocusedIndex() {
        if (playlistFocusIndex >= 0 && playlistFocusIndex < playlistItemCount()) {
            return playlistFocusIndex;
        }
        return state.playlistSelectedIndex;
    }

    private int playlistScrollStart(int visibleCount) {
        int itemCount = playlistItemCount();
        int focus = playlistFocusedIndex();
        int maxStart;
        int start;

        if (itemCount <= 0 || visibleCount <= 0) {
            return 0;
        }

        maxStart = max(0, itemCount - visibleCount);
        start = focus - (visibleCount / 2);
        return clamp(0, maxStart, start);
    }

    private int playlistVisibleRowCount(int height, int topBarHeight, int bottomBarHeight) {
        int itemCount = playlistItemCount();
        int availableHeight;
        int rows;

        if (itemCount <= 0) {
            return 1;
        }

        availableHeight = height - topBarHeight - bottomBarHeight - 18;
        rows = (availableHeight - 8) / max(1, playlistItemHeight);
        rows = clamp(1, PLAYLIST_VISIBLE_ROWS, rows);
        return min(rows, itemCount);
    }

    private int playlistItemCount() {
        return state.playlistTitles == null ? 0 : state.playlistTitles.length;
    }

    private String playlistTitleAt(int index) {
        if (state.playlistTitles == null || index < 0 || index >= state.playlistTitles.length) {
            return "";
        }
        return safeText(state.playlistTitles[index], "");
    }

    private String menuItemLabel(int index) {
        if (index == MENU_ITEM_OPEN) {
            return "Open";
        }
        if (index == MENU_ITEM_OPEN_LIST) {
            return "Open List";
        }
        if (index == MENU_ITEM_LOOP) {
            return state.loopModeLabel;
        }
        return "Exit";
    }

    private void moveMenuSelection(int delta) {
        menuIndex += delta;
        if (menuIndex < MENU_ITEM_OPEN) {
            menuIndex = MENU_ITEM_COUNT - 1;
        } else if (menuIndex > MENU_ITEM_EXIT) {
            menuIndex = MENU_ITEM_OPEN;
        }
        repaint();
    }

    private void activateMenuSelection() {
        menuVisible = false;
        repaint();
        if (handler == null) {
            return;
        }
        if (menuIndex == MENU_ITEM_OPEN) {
            handler.onOpenRequested();
        } else if (menuIndex == MENU_ITEM_OPEN_LIST) {
            handler.onOpenListRequested();
        } else if (menuIndex == MENU_ITEM_LOOP) {
            handler.onLoopModeToggleRequested();
        } else {
            handler.onExitRequested();
        }
    }

    private void movePlaylistSelection(int delta) {
        int itemCount = playlistItemCount();

        if (itemCount <= 0) {
            return;
        }

        int next = playlistFocusedIndex() + delta;
        if (next < 0) {
            next = itemCount - 1;
        } else if (next >= itemCount) {
            next = 0;
        }
        playlistFocusIndex = next;
        repaint();
    }

    private void activatePlaylistSelection() {
        activatePlaylistSelectionAt(playlistFocusedIndex());
    }

    private void activatePlaylistSelectionAt(int index) {
        if (index < 0 || index >= playlistItemCount()) {
            return;
        }
        playlistVisible = false;
        playlistFocusIndex = index;
        repaint();
        if (handler != null) {
            handler.onPlaylistItemRequested(index);
        }
    }

    private void triggerPrimaryAction() {
        if (handler != null && state.primaryEnabled && state.primaryAction != PRIMARY_NONE) {
            handler.onPrimaryActionRequested();
        }
    }

    private void triggerPreviousAction() {
        if (handler != null) {
            handler.onPreviousTrackRequested();
        }
    }

    private void triggerNextAction() {
        if (handler != null) {
            handler.onNextTrackRequested();
        }
    }

    private void armAndTriggerPrimaryKey() {
        if (primaryKeyHeld) {
            return;
        }
        primaryKeyHeld = true;
        pressTarget = PRESS_TARGET_PRIMARY;
        repaint();
        triggerPrimaryAction();
    }

    private void armAndTriggerPreviousKey() {
        if (previousKeyHeld) {
            return;
        }
        previousKeyHeld = true;
        pressTarget = PRESS_TARGET_PREVIOUS;
        repaint();
        triggerPreviousAction();
    }

    private void armAndTriggerNextKey() {
        if (nextKeyHeld) {
            return;
        }
        nextKeyHeld = true;
        pressTarget = PRESS_TARGET_NEXT;
        repaint();
        triggerNextAction();
    }

    private String primaryLabel() {
        if (state.primaryAction == PRIMARY_STOP) {
            return "STOP";
        }
        return "PLAY";
    }

    private String softkeyRightLabel() {
        return playlistVisible ? "Close" : "List";
    }

    private String statusLabel() {
        if (state.statusKind == STATUS_PLAYING) {
            return "PLAYING";
        }
        if (state.statusKind == STATUS_LOADING) {
            return "LOADING";
        }
        if (state.statusKind == STATUS_ERROR) {
            return "ERROR";
        }
        return "STOPPED";
    }

    private String resolveTitle() {
        return resolveTitle(state);
    }

    private String resolveTitle(State candidate) {
        if (hasMeaningfulText(candidate.title)) {
            return candidate.title;
        }
        if (hasMeaningfulText(candidate.fileName)) {
            return candidate.fileName;
        }
        return "MLD Player";
    }

    private String resolveSecondaryText() {
        return resolveSecondaryText(state);
    }

    private String resolveSecondaryText(State candidate) {
        if (hasMeaningfulText(candidate.copyright)) {
            return candidate.copyright;
        }
        if (candidate.trackLoaded && hasMeaningfulText(candidate.fileName)) {
            return candidate.fileName;
        }
        if (candidate.statusKind == STATUS_LOADING) {
            return "Preparing track";
        }
        if (candidate.statusKind == STATUS_ERROR) {
            return candidate.statusText;
        }
        return "Open a file to begin";
    }

    private String progressLeftTime() {
        if (state.positionMillis < 0L) {
            return "00:00";
        }
        return formatClock(state.positionMillis);
    }

    private String progressRightTime() {
        if (state.durationMillis < 0L) {
            return "--:--";
        }
        return formatClock(state.durationMillis);
    }

    private int toLandscapeX(int portraitY) {
        return max(0, bufferWidth - 1 - portraitY);
    }

    private int toLandscapeY(int portraitX) {
        return clamp(0, bufferHeight - 1, portraitX);
    }

    private int gameActionOrNone(int keyCode) {
        try {
            return getGameAction(keyCode);
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
    }

    private boolean isFireKey(int keyCode) {
        int action = gameActionOrNone(keyCode);
        return action == FIRE || keyCode == -5 || keyCode == 10 || keyCode == 13;
    }

    private boolean isLeftSoftKey(int keyCode) {
        return !isDirectionalKey(keyCode)
                && (keyCode == -6
                || keyCode == -21
                || hasSoftKeyName(keyCode, "SOFT1", "SOFT 1", "LEFT SOFT", "LSK"));
    }

    private boolean isRightSoftKey(int keyCode) {
        return !isDirectionalKey(keyCode)
                && (keyCode == -7
                || keyCode == -22
                || hasSoftKeyName(keyCode, "SOFT2", "SOFT 2", "RIGHT SOFT", "RSK"));
    }

    private boolean isDirectionalKey(int keyCode) {
        int action = gameActionOrNone(keyCode);

        return action == LEFT || action == RIGHT;
    }

    private boolean hasSoftKeyName(int keyCode, String a, String b, String c, String d) {
        try {
            String keyName = getKeyName(keyCode);
            String upper;
            if (keyName == null) {
                return false;
            }
            upper = keyName.toUpperCase();
            return upper.indexOf(a) >= 0
                    || upper.indexOf(b) >= 0
                    || upper.indexOf(c) >= 0
                    || upper.indexOf(d) >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean containsPressTarget(int x, int y) {
        if (pressTarget == PRESS_TARGET_PRIMARY) {
            return contains(primaryButtonX, primaryButtonY, primaryButtonWidth, primaryButtonHeight, x, y);
        }
        if (pressTarget == PRESS_TARGET_PREVIOUS) {
            return contains(previousButtonX, previousButtonY, previousButtonWidth, previousButtonHeight, x, y);
        }
        if (pressTarget == PRESS_TARGET_NEXT) {
            return contains(nextButtonX, nextButtonY, nextButtonWidth, nextButtonHeight, x, y);
        }
        return false;
    }

    private static boolean contains(int x, int y, int width, int height, int testX, int testY) {
        return testX >= x && testX < x + width && testY >= y && testY < y + height;
    }

    private static boolean hasMeaningfulText(String text) {
        String trimmed;
        if (text == null) {
            return false;
        }
        trimmed = text.trim();
        return trimmed.length() > 0;
    }

    private static int[] copySpectrumBars(int[] source) {
        int[] copy;
        int i;

        if (source == null) {
            return null;
        }
        copy = new int[source.length];
        for (i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String trimToWidth(String text, Font font, int maxWidth) {
        String value = safeText(text, "");
        int length = value.length();

        if (font.stringWidth(value) <= maxWidth) {
            return value;
        }

        while (length > 0) {
            String candidate = value.substring(0, length).trim();
            if (font.stringWidth(candidate) <= maxWidth) {
                return candidate;
            }
            length--;
        }
        return "";
    }

    private static String trimToWidthWithEllipsis(String text, Font font, int maxWidth) {
        String value = safeText(text, "");
        int length = value.length();

        if (font.stringWidth(value) <= maxWidth) {
            return value;
        }
        if (font.stringWidth("...") > maxWidth) {
            return "";
        }

        while (length > 0) {
            String candidate = value.substring(0, length).trim() + "...";
            if (font.stringWidth(candidate) <= maxWidth) {
                return candidate;
            }
            length--;
        }
        return "...";
    }

    private static String formatClock(long millis) {
        long seconds;
        long minutes;
        long remainder;

        if (millis < 0L) {
            return "--:--";
        }

        seconds = millis / 1000L;
        minutes = seconds / 60L;
        remainder = seconds % 60L;

        if (remainder < 10L) {
            return String.valueOf(minutes) + ":0" + String.valueOf(remainder);
        }
        return String.valueOf(minutes) + ":" + String.valueOf(remainder);
    }

    private static String safeText(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        return text.length() == 0 ? fallback : text;
    }

    private static int clamp(int min, int max, int value) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static int max(int left, int right) {
        return left > right ? left : right;
    }

    private static int min(int left, int right) {
        return left < right ? left : right;
    }

    private static long maxLong(long left, long right) {
        return left > right ? left : right;
    }
}
