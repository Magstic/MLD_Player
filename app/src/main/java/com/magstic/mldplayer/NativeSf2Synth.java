package com.magstic.mldplayer;

import java.io.File;
import java.io.IOException;

public final class NativeSf2Synth {
    public static final int SAMPLE_RATE = 44100;

    static {
        System.loadLibrary("mldplayer_native");
    }

    private long handle;

    public NativeSf2Synth(File soundFontFile) throws IOException {
        if (soundFontFile == null || !soundFontFile.isFile()) {
            throw new IOException("SF2 file unavailable");
        }
        handle = nativeCreate(soundFontFile.getAbsolutePath(), SAMPLE_RATE);
        if (handle == 0L) {
            throw new IOException("Cannot create SF2 synth");
        }
    }

    public void sendEvent(int status, int channel, int data1, int data2) throws IOException {
        ensureOpen();
        throwIfError(nativeSendEvent(handle, status, channel, data1, data2));
    }

    public void render(short[] buffer, int offsetShorts, int frameCount) throws IOException {
        ensureOpen();
        if (buffer == null || frameCount <= 0) {
            return;
        }
        throwIfError(nativeRender(handle, buffer, offsetShorts, frameCount));
    }

    public void release() {
        if (handle != 0L) {
            nativeRelease(handle);
            handle = 0L;
        }
    }

    private void ensureOpen() throws IOException {
        if (handle == 0L) {
            throw new IOException("SF2 synth released");
        }
    }

    private static void throwIfError(String error) throws IOException {
        if (error != null && error.length() > 0) {
            throw new IOException(error);
        }
    }

    private static native long nativeCreate(String soundFontPath, int sampleRate);

    private static native String nativeSendEvent(long handle, int status, int channel, int data1, int data2);

    private static native String nativeRender(long handle, short[] buffer, int offsetShorts, int frameCount);

    private static native void nativeRelease(long handle);
}
