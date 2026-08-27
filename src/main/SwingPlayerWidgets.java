package main;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSliderUI;

/** Reusable custom widgets and painting details for the Swing view. */
final class SwingPlayerWidgets {
    private static final Color PLAYLIST_HINT_COLOR = new Color(140, 140, 140);
    private static final Color PROGRESS_BG_COLOR = new Color(220, 220, 220);
    private static final Color PROGRESS_FG_COLOR = new Color(100, 100, 100);
    private static final Color BUTTON_BG_COLOR = new Color(230, 230, 230);
    private static final Color BUTTON_FG_COLOR = new Color(80, 80, 80);
    private static final Color BUTTON_HOVER_COLOR = new Color(210, 210, 210);
    private static final String[] FONT_FALLBACKS = {
        "Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "WenQuanYi Micro Hei",
        "Arial Unicode MS", "SansSerif"
    };

    private SwingPlayerWidgets() {
    }

    static Font findFont(int style, int size) {
        for (String name : FONT_FALLBACKS) {
            Font font = new Font(name, style, size);
            if (font.getFamily().equals(name) || !font.getFamily().equals("Dialog")) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

static final class PlayButton extends JButton {
    private static final long serialVersionUID = 1L;

    private boolean playing;
    private boolean paused;
    private final int size;

    PlayButton(int size) {
        this.size = size;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setMaximumSize(new Dimension(size, size));
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    void setPlaying(boolean playing) {
        this.playing = playing;
        repaint();
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(getModel().isRollover() ? BUTTON_HOVER_COLOR : BUTTON_BG_COLOR);
        g2d.fillOval(0, 0, size, size);
        g2d.setColor(BUTTON_FG_COLOR);
        if (!playing || paused) {
            int margin = size / 4;
            g2d.fillPolygon(
                    new int[] {margin + 2, margin + 2, size - margin + 2},
                    new int[] {margin, size - margin, size / 2},
                    3);
        } else {
            int barWidth = size / 6;
            int barHeight = size / 2;
            int margin = (size - barWidth * 3) / 2;
            g2d.fillRoundRect(margin, (size - barHeight) / 2, barWidth, barHeight, 2, 2);
            g2d.fillRoundRect(margin + barWidth * 2, (size - barHeight) / 2, barWidth, barHeight, 2, 2);
        }
        g2d.dispose();
    }
}

abstract static class ControlButton extends JButton {
    private static final long serialVersionUID = 1L;

    ControlButton(String description) {
        setPreferredSize(new Dimension(32, 32));
        setMinimumSize(new Dimension(32, 32));
        setMaximumSize(new Dimension(32, 32));
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setDescription(description);
    }

    final void setDescription(String description) {
        setToolTipText(description);
        getAccessibleContext().setAccessibleName(description);
    }

    @Override
    protected final void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (isEnabled() && getModel().isRollover()) {
            g2d.setColor(BUTTON_HOVER_COLOR);
            g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
        }
        g2d.setColor(isEnabled() ? BUTTON_FG_COLOR : PLAYLIST_HINT_COLOR);
        g2d.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        paintIcon(g2d);
        g2d.dispose();
    }

    protected abstract void paintIcon(Graphics2D graphics);
}

static final class LoopButton extends ControlButton {
    private static final long serialVersionUID = 1L;

    private PlaylistState.LoopMode mode;

    LoopButton() {
        super("Repeat one");
    }

    void setMode(PlaylistState.LoopMode mode) {
        this.mode = mode;
        setDescription(mode == PlaylistState.LoopMode.SINGLE_TRACK ? "Repeat one" : "Repeat playlist");
        repaint();
    }

    @Override
    protected void paintIcon(Graphics2D g2d) {
        int left = 6;
        int right = getWidth() - 6;
        int top = 9;
        int bottom = getHeight() - 9;
        g2d.drawLine(left + 3, top, right - 3, top);
        g2d.drawLine(right - 2, top + 3, right - 2, bottom - 3);
        g2d.drawLine(right - 3, bottom, left + 3, bottom);
        g2d.drawLine(left + 2, bottom - 3, left + 2, top + 3);
        g2d.fillPolygon(new int[] {right, right - 5, right - 5}, new int[] {top, top - 4, top + 4}, 3);
        g2d.fillPolygon(new int[] {left, left + 5, left + 5}, new int[] {bottom, bottom - 4, bottom + 4}, 3);
        if (mode == PlaylistState.LoopMode.SINGLE_TRACK) {
            g2d.setFont(findFont(Font.BOLD, 10));
            String one = "1";
            int x = (getWidth() - g2d.getFontMetrics().stringWidth(one)) / 2;
            int y = (getHeight() + g2d.getFontMetrics().getAscent() - g2d.getFontMetrics().getDescent()) / 2;
            g2d.drawString(one, x, y);
        }
    }
}

static final class ExportButton extends ControlButton {
    private static final long serialVersionUID = 1L;

    ExportButton() {
        super("Export playlist as MIDI");
    }

    @Override
    protected void paintIcon(Graphics2D g2d) {
        g2d.drawLine(6, 9, 13, 9);
        g2d.drawLine(6, 15, 13, 15);
        g2d.drawLine(6, 21, 13, 21);
        g2d.drawLine(17, 15, 26, 15);
        g2d.drawLine(22, 10, 26, 15);
        g2d.drawLine(22, 20, 26, 15);
    }
}

static final class VolumeIcon extends JComponent {
    private static final long serialVersionUID = 1L;

    private int volume;

    VolumeIcon(int volume) {
        setPreferredSize(new Dimension(24, 24));
        setMinimumSize(new Dimension(24, 24));
        setMaximumSize(new Dimension(24, 24));
        setVolume(volume);
    }

    void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(127, volume));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(BUTTON_FG_COLOR);
        g2d.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.fillPolygon(new int[] {3, 7, 12, 12, 7, 3}, new int[] {9, 9, 5, 19, 15, 15}, 6);
        if (volume == 0) {
            g2d.drawLine(15, 9, 21, 15);
            g2d.drawLine(21, 9, 15, 15);
        } else {
            g2d.drawArc(10, 7, 8, 10, -55, 110);
            if (volume >= 64) {
                g2d.drawArc(10, 4, 13, 16, -55, 110);
            }
        }
        g2d.dispose();
    }
}

static final class MinimalSliderUI extends BasicSliderUI {
    MinimalSliderUI(JSlider slider) {
        super(slider);
    }

    @Override
    protected Dimension getThumbSize() {
        return new Dimension(14, 14);
    }

    @Override
    protected TrackListener createTrackListener(JSlider slider) {
        return new TrackListener() {
            private boolean dragging;

            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || !slider.isEnabled()) return;
                if (slider.isRequestFocusEnabled()) slider.requestFocusInWindow();
                slider.setValueIsAdjusting(true);
                if (thumbRect.contains(event.getPoint())) {
                    offset = event.getX() - thumbRect.x;
                } else {
                    slider.setValue(valueForXPosition(event.getX()));
                    offset = thumbRect.width / 2;
                }
                dragging = true;
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragging && slider.isEnabled()) {
                    slider.setValue(valueForXPosition(event.getX() - offset + thumbRect.width / 2));
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (dragging) slider.setValueIsAdjusting(false);
                dragging = false;
                offset = 0;
            }
        };
    }

    @Override
    public void paintTrack(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int centerY = trackRect.y + trackRect.height / 2;
        int startX = trackRect.x;
        int endX = trackRect.x + trackRect.width;
        int thumbX = thumbRect.x + thumbRect.width / 2;
        g2d.setColor(PROGRESS_BG_COLOR);
        g2d.fillRoundRect(startX, centerY - 2, endX - startX, 4, 4, 4);
        g2d.setColor(PROGRESS_FG_COLOR);
        if (slider.getComponentOrientation().isLeftToRight()) {
            g2d.fillRoundRect(startX, centerY - 2, Math.max(0, thumbX - startX), 4, 4, 4);
        } else {
            g2d.fillRoundRect(thumbX, centerY - 2, Math.max(0, endX - thumbX), 4, 4, 4);
        }
        g2d.dispose();
    }

    @Override
    public void paintThumb(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle thumb = thumbRect;
        g2d.setColor(BUTTON_FG_COLOR);
        g2d.fillOval(thumb.x, thumb.y, thumb.width, thumb.height);
        g2d.dispose();
    }
}

static final class ProgressBar extends JComponent {
    private static final long serialVersionUID = 1L;

    private int maximum = 100;
    private int value;
    private volatile boolean indeterminate;
    private volatile float animationOffset;
    private transient Thread animationThread;

    ProgressBar() {
        setPreferredSize(new Dimension(1, 12));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 12));
        setMinimumSize(new Dimension(50, 12));
    }

    void setMaximum(int max) {
        maximum = max;
        repaint();
    }

    void setValue(int val) {
        value = Math.max(0, Math.min(val, maximum));
        repaint();
    }

    void setIndeterminate(boolean indeterminate) {
        if (this.indeterminate == indeterminate) return;
        this.indeterminate = indeterminate;
        if (indeterminate) startAnimation();
        repaint();
    }

    private synchronized void startAnimation() {
        if (animationThread != null && animationThread.isAlive()) return;
        animationThread = new Thread(() -> {
            while (indeterminate) {
                animationOffset += 0.05f;
                if (animationOffset > 1) animationOffset = 0;
                EventQueue.invokeLater(this::repaint);
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "mld-player-progress-animation");
        animationThread.setDaemon(true);
        animationThread.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g2d.setColor(PROGRESS_BG_COLOR);
        g2d.fillRoundRect(0, 0, w, h, h, h);
        if (indeterminate) {
            int barWidth = Math.max(w / 4, 30);
            int x = (int) ((w - barWidth) * animationOffset);
            g2d.setColor(PROGRESS_FG_COLOR);
            g2d.fillRoundRect(x, 0, barWidth, h, h, h);
        } else if (value > 0) {
            int fillWidth = (int) ((long) value * w / maximum);
            g2d.setColor(PROGRESS_FG_COLOR);
            g2d.fillRoundRect(0, 0, fillWidth, h, h, h);
        }
        g2d.dispose();
    }
}
}
