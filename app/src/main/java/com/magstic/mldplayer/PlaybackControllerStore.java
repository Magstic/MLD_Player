package com.magstic.mldplayer;

import android.content.Context;

public final class PlaybackControllerStore {
    private static PlayerController controller;

    private PlaybackControllerStore() {
    }

    public static synchronized PlayerController get(Context context) {
        if (controller == null) {
            controller = new PlayerController(context.getApplicationContext());
        }
        return controller;
    }
}
