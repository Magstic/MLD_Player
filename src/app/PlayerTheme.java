package app;

final class PlayerTheme {
    static final int DESIGN_WIDTH = 320;
    static final int DESIGN_HEIGHT = 240;

    static final String TOP_BAR_PATH = "/ui/top_bar.png";
    static final String BOTTOM_BAR_PATH = "/ui/bottom_bar.png";
    static final String META_ICON_PATH = "/ui/meta_icon.png";
    static final String PLAY_DISABLED_PATH = "/ui/play_disabled.png";
    static final String PLAY_ENABLED_PATH = "/ui/play_enabled.png";
    static final String STOP_DISABLED_PATH = "/ui/stop_disabled.png";
    static final String STOP_ENABLED_PATH = "/ui/stop_enabled.png";
    static final String PREV_DISABLED_PATH = "/ui/prev_disabled.png";
    static final String PREV_ENABLED_PATH = "/ui/prev_enabled.png";
    static final String NEXT_DISABLED_PATH = "/ui/next_disabled.png";
    static final String NEXT_ENABLED_PATH = "/ui/next_enabled.png";

    static final int COLOR_BACKGROUND = 0x000000;
    static final int COLOR_BACKGROUND_SOFT = 0x0B0D11;
    static final int COLOR_BACKGROUND_MID = 0x11141A;
    static final int COLOR_LINE = 0x242931;
    static final int COLOR_LINE_ACCENT = 0x495769;
    static final int COLOR_TEXT = 0xF8FBFF;
    static final int COLOR_TEXT_WEAK = 0xC2CBD6;
    static final int COLOR_TEXT_DIM = 0x8B94A0;
    static final int COLOR_STATUS_PLAY = 0xE8EEF7;
    static final int COLOR_STATUS_STOP = 0x3A404A;
    static final int COLOR_STATUS_LOAD = 0x5B6C80;
    static final int COLOR_STATUS_ERROR = 0x6A4049;
    static final int COLOR_STATUS_TEXT = 0xFFFFFF;
    static final int COLOR_STATUS_TEXT_DARK = 0x0B1016;
    static final int COLOR_MENU_FILL = 0x101318;
    static final int COLOR_MENU_BORDER = 0x454D58;
    static final int COLOR_MENU_SELECTED = 0xE5EBF5;
    static final int COLOR_MENU_SELECTED_TEXT = 0x050608;
    static final int COLOR_BUTTON_FILL = 0x13171D;
    static final int COLOR_BUTTON_BORDER = 0x586373;
    static final int COLOR_BUTTON_DISABLED = 0x1B1F26;
    static final int COLOR_BUTTON_TEXT = 0xFFFFFF;
    static final int COLOR_BUTTON_TEXT_DISABLED = 0x6B7380;
    static final int COLOR_DECORATION = 0x2A3039;
    static final int COLOR_PROGRESS_HEAD = 0xFFFFFF;
    static final int COLOR_PROGRESS_TRACK = 0x9AA3AD;
    static final int COLOR_PROGRESS_TRACK_EDGE = 0xC4CBD3;
    static final int COLOR_SPECTRUM_BASE = 0x090C11;
    static final int COLOR_SPECTRUM_GRID = 0x1B2129;
    static final int COLOR_SPECTRUM_EDGE = 0x3C4654;
    static final int COLOR_SPECTRUM_LOW = 0x5A6676;
    static final int COLOR_SPECTRUM_HIGH = 0xE5EEF8;
    static final int COLOR_SPECTRUM_CAP = 0xFFFFFF;

    static final int[] BAR_PROFILE = new int[] {
            84, 68, 76, 58, 48, 42, 34, 38, 46, 59, 72, 66, 54, 44
    };

    private PlayerTheme() {
    }
}
