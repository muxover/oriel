package com.hisilicon.miracast.ndk;

/**
 * The TV's own Wi-Fi Display receiver (HiSilicon libsink_jni.so). The library binds its natives to this class
 * name, so it keeps the maker's package and method names. Used only by Oriel's WindowsReceiver, as root.
 */
public final class SinkNative {
    /** Native events: 3001 connection lost, 3002 lost packets, 3003 keep-alive failed, 3011 started, 3012 stopped. */
    public interface Listener {
        void onEvent(int what, int arg1, int arg2);
    }

    private static volatile Listener listener;

    private native void nativeSetup();

    public native void initPlayer();

    public native void initSink(String sourceIp, int sourcePort);

    public native void releaseSink();

    public native void setAudioMode(String mode);

    public native void setHdcpMode(int mode);

    public native void startSink();

    public native void stopSink();

    public SinkNative(Listener l) {
        listener = l;
        nativeSetup();
    }

    private static void postEventFromNative(int what, int arg1, int arg2) {
        Listener l = listener;
        if (l != null) l.onEvent(what, arg1, arg2);
    }
}
