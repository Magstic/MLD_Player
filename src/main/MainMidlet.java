package main;

import javax.microedition.midlet.MIDlet;

import app.PlaybackController;

public final class MainMidlet extends MIDlet {
    private PlaybackController controller;

    protected void startApp() {
        if (controller == null) {
            controller = new PlaybackController(this);
        }
        controller.start();
    }

    protected void pauseApp() {
        if (controller != null) {
            controller.pause();
        }
    }

    protected void destroyApp(boolean unconditional) {
        if (controller != null) {
            controller.destroy();
        }
    }
}
