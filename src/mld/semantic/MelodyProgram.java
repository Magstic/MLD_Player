package mld.semantic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable native ordinary-track semantic result.
 */
public final class MelodyProgram {
    public final OrdinaryModel ordinaryModel;
    public final List<NativeNote> notes;
    public final List<NoteAction> noteActions;
    public final List<GateSchedule> gateSchedules;
    public final List<NativeControl> controls;
    public final List<UnmappedControl> unmappedControls;
    private final int[] finalVoiceMap;

    MelodyProgram(
            OrdinaryModel m,
            List<NativeNote> n,
            List<NoteAction> a,
            List<GateSchedule> g,
            List<NativeControl> c,
            List<UnmappedControl> u,
            int[] v) {
        ordinaryModel = m;
        notes = immutable(n);
        noteActions = immutable(a);
        gateSchedules = immutable(g);
        controls = immutable(c);
        unmappedControls = immutable(u);
        finalVoiceMap = Arrays.copyOf(v, v.length);
    }

    /** Immediate native note transition used by the live semantic transport. */
    public static final class NoteAction {
        public final boolean noteOn;
        public final int sourceTrack;
        public final int sourceVoice;
        public final int logicalChannel;
        public final int pitchOffset;
        public final int velocity;
        public final int rawTick;
        public final int order;
        public final ChannelSnapshot channel;

        NoteAction(
                boolean noteOn,
                int sourceTrack,
                int sourceVoice,
                int logicalChannel,
                int pitchOffset,
                int velocity,
                int rawTick,
                int order,
                ChannelSnapshot channel) {
            this.noteOn = noteOn;
            this.sourceTrack = sourceTrack;
            this.sourceVoice = sourceVoice;
            this.logicalChannel = logicalChannel;
            this.pitchOffset = pitchOffset;
            this.velocity = velocity;
            this.rawTick = rawTick;
            this.order = order;
            this.channel = channel;
        }
    }

    public int[] copyFinalVoiceMap() {
        return Arrays.copyOf(finalVoiceMap, finalVoiceMap.length);
    }

    private static <T>List<T> immutable(List<T> s) {
        return Collections.unmodifiableList(new ArrayList<T>(s));
    }

    public static final class OrdinaryModel {
        public final boolean dualHalfVoiceState;
        public final int replacementOverlapSamples;
        public final String selectedHalfRule;
        public final boolean normalReleaseDistinctFromForcedStop;
        public final boolean liveLevelPanRefresh;
        public final boolean livePitchRefresh;
        public final boolean liveLookupRefresh;

        public OrdinaryModel(boolean a, int b, String c, boolean d, boolean e, boolean f, boolean g) {
            dualHalfVoiceState = a;
            replacementOverlapSamples = b;
            selectedHalfRule = c;
            normalReleaseDistinctFromForcedStop = d;
            liveLevelPanRefresh = e;
            livePitchRefresh = f;
            liveLookupRefresh = g;
        }
    }

    public static final class ChannelSnapshot {
        public final int mode;
        public final int bank;
        public final int program;
        public final int level;
        public final int pan;
        public final int pitchCoarse;
        public final int pitchFine;
        public final int pitchRange;
        public final int modulation;
        public final boolean noteOnSuppressed;
        public final int nativeKind;
        public final int nativeSub;
        public final int nativeValue;

        public ChannelSnapshot(int a, int b, int c, int d, int e, int f, int g, int h, int i, boolean j, int k, int l, int m) {
            mode = a;
            bank = b;
            program = c;
            level = d;
            pan = e;
            pitchCoarse = f;
            pitchFine = g;
            pitchRange = h;
            modulation = i;
            noteOnSuppressed = j;
            nativeKind = k;
            nativeSub = l;
            nativeValue = m;
        }
    }

    public static final class NativeNote {
        public final int sourceTrack;
        public final int sourceVoice;
        public final int logicalChannel;
        public final int nativeNote;
        public final int pitchOffset;
        public final int velocity;
        public final int rawStartTick;
        public final int rawEndTick;
        public final int order;
        public final ChannelSnapshot channel;

        public NativeNote(int a, int b, int c, int d, int e, int f, int g, int h, int i, ChannelSnapshot j) {
            sourceTrack = a;
            sourceVoice = b;
            logicalChannel = c;
            nativeNote = d;
            pitchOffset = e;
            velocity = f;
            rawStartTick = g;
            rawEndTick = h;
            order = i;
            channel = j;
        }
    }

    /**
     * One native gate scheduling action after voice mapping and scheduler-state resolution.
     */
    public static final class GateSchedule {
        public final int sourceTrack;
        public final int sourceVoice;
        public final int logicalChannel;
        public final int nativeNote;
        public final int rawStartTick;
        public final int rawEndTick;
        public final boolean sounding;

        public GateSchedule(int a, int b, int c, int d, int e, int f, boolean g) {
            sourceTrack = a;
            sourceVoice = b;
            logicalChannel = c;
            nativeNote = d;
            rawStartTick = e;
            rawEndTick = f;
            sounding = g;
        }
    }

    public static final class NativeControl {
        public final int sourceTrack;
        public final int sourceCommand;
        public final int rawTick;
        public final int logicalChannel;
        public final int value;
        public final int part;
        public final int masterVolume;
        public final int continuityWindowSamples;
        public final int order;
        public final String sourceName;
        public final String nativePath;
        public final boolean selectedHalfAware;
        public final boolean writesReplacementHalfWhenPendingSwap;
        public final ChannelSnapshot channel;

        public NativeControl(int a, int b, String c, int d, int e, int f, int g, int h, String i, boolean j, boolean k, int l, int m, ChannelSnapshot n) {
            sourceTrack = a;
            sourceCommand = b;
            sourceName = c;
            rawTick = d;
            logicalChannel = e;
            value = f;
            part = g;
            masterVolume = h;
            nativePath = i;
            selectedHalfAware = j;
            writesReplacementHalfWhenPendingSwap = k;
            continuityWindowSamples = l;
            order = m;
            channel = n;
        }
    }

    public static final class UnmappedControl {
        public final int sourceTrack;
        public final int sourceCommand;
        public final int rawTick;
        public final int value;
        public final int part;
        public final String sourceName;

        public UnmappedControl(int a, int b, String c, int d, int e, int f) {
            sourceTrack = a;
            sourceCommand = b;
            sourceName = c;
            rawTick = d;
            value = e;
            part = f;
        }
    }
}
