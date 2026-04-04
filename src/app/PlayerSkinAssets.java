package app;

import javax.microedition.lcdui.Image;

final class PlayerSkinAssets {
    private boolean loadAttempted;
    private Image topBar;
    private Image bottomBar;
    private Image metaIcon;
    private Image playDisabled;
    private Image playEnabled;
    private Image stopDisabled;
    private Image stopEnabled;
    private Image prevDisabled;
    private Image prevEnabled;
    private Image nextDisabled;
    private Image nextEnabled;

    void ensureLoaded() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        topBar = load(PlayerTheme.TOP_BAR_PATH);
        bottomBar = load(PlayerTheme.BOTTOM_BAR_PATH);
        metaIcon = load(PlayerTheme.META_ICON_PATH);
        playDisabled = load(PlayerTheme.PLAY_DISABLED_PATH);
        playEnabled = load(PlayerTheme.PLAY_ENABLED_PATH);
        stopDisabled = load(PlayerTheme.STOP_DISABLED_PATH);
        stopEnabled = load(PlayerTheme.STOP_ENABLED_PATH);
        prevDisabled = load(PlayerTheme.PREV_DISABLED_PATH);
        prevEnabled = load(PlayerTheme.PREV_ENABLED_PATH);
        nextDisabled = load(PlayerTheme.NEXT_DISABLED_PATH);
        nextEnabled = load(PlayerTheme.NEXT_ENABLED_PATH);
    }

    Image topBar() {
        ensureLoaded();
        return topBar;
    }

    Image bottomBar() {
        ensureLoaded();
        return bottomBar;
    }

    Image metaIcon() {
        ensureLoaded();
        return metaIcon;
    }

    Image playDisabled() {
        ensureLoaded();
        return playDisabled;
    }

    Image playEnabled() {
        ensureLoaded();
        return playEnabled;
    }

    Image stopDisabled() {
        ensureLoaded();
        return stopDisabled;
    }

    Image stopEnabled() {
        ensureLoaded();
        return stopEnabled;
    }

    Image prevDisabled() {
        ensureLoaded();
        return prevDisabled;
    }

    Image prevEnabled() {
        ensureLoaded();
        return prevEnabled;
    }

    Image nextDisabled() {
        ensureLoaded();
        return nextDisabled;
    }

    Image nextEnabled() {
        ensureLoaded();
        return nextEnabled;
    }

    private static Image load(String path) {
        try {
            return Image.createImage(path);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
