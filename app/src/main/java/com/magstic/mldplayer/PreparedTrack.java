package com.magstic.mldplayer;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PreparedTrack {
    public final Uri uri;
    public final String fileName;
    public final String title;
    public final String copyright;
    public final byte[] midiBytes;
    public final int durationMs;
    public final int loopStartMs;
    public final int playbackEndFrame;
    public final int loopStartFrame;
    public final boolean hasInternalLoop;
    public final TimelineSpectrum spectrum;
    public final List<SynthEvent> synthEvents;

    public PreparedTrack(
            Uri uri,
            String fileName,
            String title,
            String copyright,
            byte[] midiBytes,
            int durationMs,
            int loopStartMs,
            int playbackEndFrame,
            int loopStartFrame,
            boolean hasInternalLoop,
            TimelineSpectrum spectrum,
            List<SynthEvent> synthEvents) {
        this.uri = uri;
        this.fileName = fileName;
        this.title = title;
        this.copyright = copyright;
        this.midiBytes = midiBytes;
        this.durationMs = durationMs;
        this.loopStartMs = loopStartMs;
        this.playbackEndFrame = playbackEndFrame;
        this.loopStartFrame = loopStartFrame;
        this.hasInternalLoop = hasInternalLoop;
        this.spectrum = spectrum;
        this.synthEvents = Collections.unmodifiableList(new ArrayList<SynthEvent>(synthEvents));
    }

    public static final class SynthEvent {
        public static final int PRIORITY_CONTROL = 0;
        public static final int PRIORITY_NOTE_OFF = 1;
        public static final int PRIORITY_NOTE_ON = 2;

        public final int sampleFrame;
        public final int priority;
        public final int order;
        public final int status;
        public final int channel;
        public final int data1;
        public final int data2;

        public SynthEvent(
                int sampleFrame,
                int priority,
                int order,
                int status,
                int channel,
                int data1,
                int data2) {
            this.sampleFrame = sampleFrame;
            this.priority = priority;
            this.order = order;
            this.status = status;
            this.channel = channel;
            this.data1 = data1;
            this.data2 = data2;
        }
    }
}
