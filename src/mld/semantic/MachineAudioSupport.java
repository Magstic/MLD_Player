package mld.semantic;

import java.util.Arrays;
import java.util.List;

import mld.decode.MachineDependentEvent;
import mld.decode.MachineDescriptorMatcher;

/** Shared low-level helpers for typed machine-audio interpreters. */
final class MachineAudioSupport {
    private MachineAudioSupport() {
    }

    static AudioProgram.AudioAction action(
            int order,
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            AudioProgram.ActionKind kind,
            int logicalChannel,
            int slot,
            int resourceIndex,
            int linkedCatalogIndex,
            AudioProgram.AudioType audioType,
            int operation,
            int formatCode,
            int sampleRate,
            int codedBits,
            int channelCount,
            int controlFlag,
            int durationByteCount,
            long durationMs,
            int value,
            boolean cacheAware,
            boolean phaseGated,
            boolean layeredStartEligible,
            AudioProgram.RendererSupport rendererSupport,
            AudioProgram.BranchEffect layeredEffect,
            AudioProgram.BranchEffect monolithicEffect,
            byte[] encodedPayload) {
        return new AudioProgram.AudioAction(
                order,
                AudioProgram.SourceKind.MACHINE_DEPENDENT,
                kind,
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                match.descriptorIndex,
                match.handlerId,
                logicalChannel,
                slot,
                resourceIndex,
                linkedCatalogIndex,
                audioType,
                operation,
                formatCode,
                sampleRate,
                codedBits,
                channelCount,
                controlFlag,
                durationByteCount,
                durationMs,
                value,
                cacheAware,
                phaseGated,
                layeredStartEligible,
                rendererSupport,
                layeredEffect,
                monolithicEffect,
                encodedPayload);
    }

    static void truncated(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            List<Diagnostic> diagnostics,
            String required) {
        diagnostics.add(new Diagnostic(
                "AUDIO_MD_TRUNCATED",
                Diagnostic.Severity.WARNING,
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                "Machine-dependent audio handler 0x" + Integer.toHexString(match.handlerId)
                        + " is truncated before " + required + "."));
    }

    static void unsupported(
            List<Diagnostic> diagnostics,
            MachineDependentEvent event,
            String code,
            String message) {
        diagnostics.add(new Diagnostic(
                code,
                Diagnostic.Severity.UNSUPPORTED,
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                message));
    }

    static boolean has(byte[] payload, int offset, int count) {
        return offset >= 0 && count >= 0 && offset + count <= payload.length;
    }

    static int u8(byte[] payload, int offset) {
        return payload[offset] & 0xFF;
    }

    static int readBe16(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
    }

    static int readBe32(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 24)
                | ((payload[offset + 1] & 0xFF) << 16)
                | ((payload[offset + 2] & 0xFF) << 8)
                | (payload[offset + 3] & 0xFF);
    }

    static byte[] slice(byte[] payload, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, payload.length));
        int safeEnd = Math.max(safeStart, Math.min(end, payload.length));
        return Arrays.copyOfRange(payload, safeStart, safeEnd);
    }

    static long durationMilliseconds(int byteCount, int sampleRate, int codedBits) {
        if (sampleRate <= 0 || codedBits <= 0 || 8 % codedBits != 0) {
            return -1L;
        }
        long samplesPerByte = 8 / codedBits;
        long byteCountUnsigned = Integer.toUnsignedLong(byteCount);
        long sampleCount = (byteCountUnsigned * samplesPerByte) & 0xFFFFFFFFL;
        return Math.round((sampleCount * 1000.0) / sampleRate);
    }
}
