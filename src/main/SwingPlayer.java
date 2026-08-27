package main;

import java.awt.EventQueue;

import javax.swing.UIManager;

/** Swing entry point; composition lives in SwingPlayerController/SwingPlayerView. */
public final class SwingPlayer {
    private SwingPlayer() {
    }

    public static void main(String[] args) {
        final String[] launchArgs = args == null ? new String[0] : args.clone();
        installLookAndFeel();
        EventQueue.invokeLater(() -> new SwingPlayerController(launchArgs).show());
    }

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
    }
}
