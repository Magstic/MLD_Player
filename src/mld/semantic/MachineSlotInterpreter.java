package mld.semantic;

import java.util.List;

import mld.decode.MachineDependentEvent;
import mld.decode.MachineDescriptorMatcher;

/** Typed parsing for machine-dependent sampled-audio slot/load handlers. */
final class MachineSlotInterpreter {
    private MachineSlotInterpreter() {
    }

    static AudioProgram.AudioAction slot8000(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            boolean cached,
            List<Diagnostic> diagnostics) {
        int headerSize = cached ? 3 : 2;
        if (!MachineAudioSupport.has(payload, match.bodyOffset, headerSize)) {
            MachineAudioSupport.truncated(
                    event, match, diagnostics, cached ? "cached 0x8000 header" : "0x8000 header");
            return null;
        }
        int channelSlot = MachineAudioSupport.u8(payload, match.bodyOffset);
        int opFormat = MachineAudioSupport.u8(payload, match.bodyOffset + 1);
        int operation = (opFormat >> 6) & 0x03;
        int formatCode = opFormat & 0x3F;
        int sampleRate = format8000Rate(formatCode, cached);
        int controlFlag = cached ? MachineAudioSupport.u8(payload, match.bodyOffset + 2) & 0x01 : -1;
        byte[] encoded = MachineAudioSupport.slice(
                payload, match.bodyOffset + headerSize, payload.length);
        boolean mayExecute = operation != 3 || (cached && controlFlag == 1);
        boolean mayLoad = operation == 0 || operation == 1
                || (cached && controlFlag == 1 && operation != 2);
        if (sampleRate < 0 && operation != 2 && operation != 3) {
            MachineAudioSupport.unsupported(
                    diagnostics,
                    event,
                    "AUDIO_MD_8000_FORMAT_UNSUPPORTED",
                    "Recognized 0x8000 format code " + formatCode
                            + " has no verified sample-rate mapping.");
        }
        if (mayExecute) {
            MachineAudioSupport.unsupported(
                    diagnostics,
                    event,
                    "AUDIO_RENDERER_8000_UNSUPPORTED",
                    "MFiAudio type 0x8000 semantics are recognized; renderer support is not implemented.");
        }
        AudioProgram.ActionKind actionKind = operationKind(match.handlerId, operation);
        if (!cached && actionKind != AudioProgram.ActionKind.SLOT_LOAD
                && actionKind != AudioProgram.ActionKind.SLOT_LOAD_AND_START) {
            encoded = new byte[0];
        }
        int localChannel = (channelSlot >> 6) & 0x03;
        return MachineAudioSupport.action(
                order,
                event,
                match,
                actionKind,
                localChannel,
                channelSlot & 0x3F,
                -1,
                -1,
                AudioProgram.AudioType.MFI_8000,
                operation,
                formatCode,
                sampleRate,
                4,
                1,
                controlFlag,
                -1,
                -1L,
                -1,
                cached,
                false,
                localChannel <= 1,
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                mayExecute
                        ? AudioProgram.BranchEffect.BACKEND_ACTION
                        : AudioProgram.BranchEffect.NO_ACTION,
                mayLoad
                        ? AudioProgram.BranchEffect.STATE_ONLY
                        : AudioProgram.BranchEffect.NO_ACTION,
                encoded);
    }

    static AudioProgram.AudioAction slot8001(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            boolean countedDuration,
            List<Diagnostic> diagnostics) {
        int required = countedDuration ? 7 : 3;
        if (!MachineAudioSupport.has(payload, match.bodyOffset, required)) {
            MachineAudioSupport.truncated(
                    event,
                    match,
                    diagnostics,
                    countedDuration ? "counted 0x8001 header" : "inline 0x8001 header");
            return null;
        }
        int channelSlot = MachineAudioSupport.u8(payload, match.bodyOffset);
        int opFormat = MachineAudioSupport.u8(payload, match.bodyOffset + 1);
        int operation = (opFormat >> 6) & 0x03;
        int formatCode = opFormat & 0x3F;
        int rawFlags = MachineAudioSupport.u8(payload, match.bodyOffset + 2);
        int controlFlag = rawFlags & 0x01;
        AudioFormat format = format8001(formatCode);
        if (format == null) {
            MachineAudioSupport.unsupported(
                    diagnostics,
                    event,
                    "AUDIO_MD_8001_FORMAT_UNSUPPORTED",
                    "Recognized 0x8001 handler uses unsupported format code " + formatCode + ".");
        }
        int encodedOffset = match.bodyOffset + (countedDuration ? 7 : 3);
        int durationByteCount = countedDuration
                ? MachineAudioSupport.readBe32(payload, match.bodyOffset + 3)
                : payload.length - encodedOffset;
        byte[] encoded = MachineAudioSupport.slice(payload, encodedOffset, payload.length);
        long durationMs = format == null
                ? -1L
                : MachineAudioSupport.durationMilliseconds(
                        durationByteCount, format.sampleRate, format.codedBits);

        boolean verifiedLegacyPath = countedDuration
                && match.descriptorIndex == 23
                && operation == 1
                && rawFlags == 0
                && format != null
                && format.codedBits == 4
                && format.channelCount == 1;
        AudioProgram.RendererSupport support = verifiedLegacyPath
                ? AudioProgram.RendererSupport.VERIFIED_8001_4BIT
                : AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED;

        boolean mayExecute = operation != 3 || controlFlag == 1;
        boolean mayLoad = operation == 0 || operation == 1
                || (controlFlag == 1 && operation != 2);
        if (format != null && format.codedBits == 2 && mayExecute) {
            MachineAudioSupport.unsupported(
                    diagnostics,
                    event,
                    "AUDIO_RENDERER_8001_2BIT_UNSUPPORTED",
                    "MFiAudio type 0x8001 2-bit G.726 is recognized; decoder support is not implemented.");
        } else if (format != null && format.codedBits == 4 && mayExecute && !verifiedLegacyPath) {
            String reason;
            if (match.descriptorIndex != 23 || !countedDuration) {
                reason = "only the verified legacy 71:84 descriptor is renderable";
            } else if (operation != 1) {
                reason = "only verified 71:84 mode/operation 1 is renderable";
            } else if (rawFlags != 0) {
                reason = "current renderer coverage requires raw control byte 0";
            } else {
                reason = "the profile is not enabled by the renderer";
            }
            MachineAudioSupport.unsupported(
                    diagnostics,
                    event,
                    "AUDIO_RENDERER_8001_PROFILE_UNSUPPORTED",
                    "Recognized 4-bit 0x8001 audio is outside the verified renderer profile: "
                            + reason + ".");
        }
        AudioProgram.ActionKind actionKind = operationKind(match.handlerId, operation);
        // Keep the body remainder for every cached 0x8001 operation. Pending state or
        // control bit0 can replace the incoming operation with a load at execution time.
        int localChannel = (channelSlot >> 6) & 0x03;
        return MachineAudioSupport.action(
                order,
                event,
                match,
                actionKind,
                localChannel,
                channelSlot & 0x3F,
                -1,
                -1,
                AudioProgram.AudioType.MFI_8001,
                operation,
                formatCode,
                format != null ? format.sampleRate : -1,
                format != null ? format.codedBits : -1,
                format != null ? format.channelCount : -1,
                controlFlag,
                durationByteCount,
                durationMs,
                -1,
                true,
                true,
                localChannel <= 3,
                support,
                mayExecute
                        ? AudioProgram.BranchEffect.PHASE_GATED_BACKEND
                        : AudioProgram.BranchEffect.NO_ACTION,
                mayLoad
                        ? AudioProgram.BranchEffect.STATE_ONLY
                        : AudioProgram.BranchEffect.NO_ACTION,
                encoded);
    }

    static AudioProgram.AudioAction slot8002(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            int offset,
            List<Diagnostic> diagnostics) {
        if (!MachineAudioSupport.has(payload, offset, 4)) {
            MachineAudioSupport.truncated(event, match, diagnostics, "compact 0x8002 header");
            return null;
        }
        int slot = MachineAudioSupport.u8(payload, offset);
        int control = MachineAudioSupport.u8(payload, offset + 1);
        int sampleRate = MachineAudioSupport.readBe16(payload, offset + 2);
        int channelCount = ((control >> 7) & 0x01) + 1;
        byte[] encoded = MachineAudioSupport.slice(payload, offset + 4, payload.length);
        long durationMs = -1L;
        if (sampleRate > 0) {
            long sampleCount = (((long) encoded.length) * 2L) & 0xFFFFFFFFL;
            durationMs = Math.round((sampleCount * 1000.0) / sampleRate);
        }
        boolean verifiedRendererProfile = slot < 64
                && encoded.length >= 81 * channelCount
                && (sampleRate == 4000 || sampleRate == 8000
                        || sampleRate == 16000 || sampleRate == 32000);
        AudioProgram.RendererSupport support = verifiedRendererProfile
                ? AudioProgram.RendererSupport.VERIFIED_8002_AWC2_4BIT
                : AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED;
        if (!verifiedRendererProfile) {
            MachineAudioSupport.unsupported(
                    diagnostics,
                    event,
                    "AUDIO_RENDERER_8002_PROFILE_UNSUPPORTED",
                    "Recognized 0x8002 audio is outside the verified native-length slot-0..63, 4/8/16/32-kHz mono/stereo AWC2 renderer profiles.");
        }
        return MachineAudioSupport.action(
                order,
                event,
                match,
                AudioProgram.ActionKind.SLOT_LOAD,
                -1,
                slot,
                -1,
                -1,
                AudioProgram.AudioType.MFI_8002,
                0,
                control & 0x03,
                sampleRate,
                4,
                channelCount,
                (control >> 7) & 0x01,
                encoded.length,
                durationMs,
                -1,
                true,
                true,
                true,
                support,
                AudioProgram.BranchEffect.PHASE_GATED_BACKEND,
                AudioProgram.BranchEffect.STATE_ONLY,
                encoded);
    }

    private static AudioProgram.ActionKind operationKind(int handlerId, int operation) {
        if (operation == 3) return AudioProgram.ActionKind.NO_ACTION;
        if (operation == 2) return AudioProgram.ActionKind.SLOT_START;
        if (handlerId == 0x002 || operation == 1) {
            return AudioProgram.ActionKind.SLOT_LOAD_AND_START;
        }
        return AudioProgram.ActionKind.SLOT_LOAD;
    }

    private static int format8000Rate(int formatCode, boolean cached) {
        if (formatCode == 4) return 4000;
        if (formatCode == 5) return 8000;
        if (cached && formatCode == 6) return 16000;
        return -1;
    }

    private static AudioFormat format8001(int formatCode) {
        switch (formatCode) {
            case 4: return new AudioFormat(8000, 2, 1);
            case 5: return new AudioFormat(8000, 4, 1);
            case 12: return new AudioFormat(16000, 2, 1);
            case 13: return new AudioFormat(16000, 4, 1);
            case 20: return new AudioFormat(32000, 2, 1);
            case 21: return new AudioFormat(32000, 4, 1);
            default: return null;
        }
    }

    private static final class AudioFormat {
        final int sampleRate;
        final int codedBits;
        final int channelCount;

        AudioFormat(int sampleRate, int codedBits, int channelCount) {
            this.sampleRate = sampleRate;
            this.codedBits = codedBits;
            this.channelCount = channelCount;
        }
    }
}
