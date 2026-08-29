package mld.semantic;

import java.util.List;

import mld.decode.MachineDependentEvent;
import mld.decode.MachineDescriptorMatcher;

/** Typed parsing for machine-dependent audio controls and route state. */
final class MachineControlInterpreter {
    private MachineControlInterpreter() {
    }

    static AudioProgram.AudioAction channelControl(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            boolean phaseGated,
            boolean level,
            List<Diagnostic> diagnostics) {
        if (!MachineAudioSupport.has(payload, match.bodyOffset, 1)) {
            MachineAudioSupport.truncated(event, match, diagnostics, "packed channel/value byte");
            return null;
        }
        int packed = MachineAudioSupport.u8(payload, match.bodyOffset);
        int logicalChannel = (packed >> 6) & 0x03;
        return MachineAudioSupport.action(
                order,
                event,
                match,
                level ? AudioProgram.ActionKind.CHANNEL_LEVEL : AudioProgram.ActionKind.CHANNEL_PAN,
                logicalChannel,
                -1,
                -1,
                -1,
                AudioProgram.AudioType.NONE,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1L,
                (packed & 0x3F) * 2,
                false,
                phaseGated,
                true,
                AudioProgram.RendererSupport.CONTROL_ONLY,
                phaseGated ? AudioProgram.BranchEffect.PHASE_GATED_BACKEND : AudioProgram.BranchEffect.BACKEND_ACTION,
                AudioProgram.BranchEffect.NO_ACTION,
                null);
    }

    static AudioProgram.AudioAction compactControl(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            int offset,
            boolean phaseGated,
            List<Diagnostic> diagnostics) {
        if (!MachineAudioSupport.has(payload, offset, 1)) {
            MachineAudioSupport.truncated(event, match, diagnostics, "compact audio control byte");
            return null;
        }
        int command = MachineAudioSupport.u8(payload, offset);
        int logicalChannel = (command >> 6) & 0x03;
        int opcode = command & 0x0F;
        AudioProgram.ActionKind kind = AudioProgram.ActionKind.NO_ACTION;
        int slot = -1;
        int value = -1;
        if ((opcode == 3 || opcode == 4) && MachineAudioSupport.has(payload, offset + 1, 2)) {
            kind = AudioProgram.ActionKind.SLOT_START;
            slot = MachineAudioSupport.u8(payload, offset + 1) & 0x1F;
            value = MachineAudioSupport.u8(payload, offset + 2) & 0x7F;
        } else if (opcode == 5 && MachineAudioSupport.has(payload, offset + 1, 1)) {
            kind = AudioProgram.ActionKind.SLOT_STOP;
            slot = MachineAudioSupport.u8(payload, offset + 1) & 0x1F;
        } else if (opcode == 6 && MachineAudioSupport.has(payload, offset + 1, 2)) {
            value = doubledValue(MachineAudioSupport.u8(payload, offset + 2));
            if (value < 0) return null;
            kind = AudioProgram.ActionKind.CHANNEL_PAN;
        } else if (opcode == 7 && MachineAudioSupport.has(payload, offset + 1, 1)) {
            kind = AudioProgram.ActionKind.SLOT_CONTROL;
            value = MachineAudioSupport.u8(payload, offset + 1);
        } else if (opcode >= 3 && opcode <= 7) {
            MachineAudioSupport.truncated(event, match, diagnostics, "compact audio control operands");
            return null;
        }
        if (kind == AudioProgram.ActionKind.NO_ACTION) return null;
        return controlAction(
                event, match, order, kind, logicalChannel, slot, opcode, value, phaseGated);
    }

    static AudioProgram.AudioAction mixedDispatch(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            List<Diagnostic> diagnostics) {
        int offset = match.bodyOffset;
        if (!MachineAudioSupport.has(payload, offset, 1)) {
            MachineAudioSupport.truncated(event, match, diagnostics, "mixed-dispatch command byte");
            return null;
        }
        int command = MachineAudioSupport.u8(payload, offset);
        int code = command & 0x3F;
        if (code == 7) {
            return MachineSlotInterpreter.slot8002(event, match, order, payload, offset + 1, diagnostics);
        }
        if (code == 8) {
            return route(event, match, order, payload, offset + 1, diagnostics);
        }
        int logicalChannel = (command >> 6) & 0x03;
        if (code == 9 || code == 15) {
            if (!MachineAudioSupport.has(payload, offset + 1, 2)) {
                MachineAudioSupport.truncated(event, match, diagnostics, "mixed audio-start operands");
                return null;
            }
            return controlAction(
                    event, match, order, AudioProgram.ActionKind.SLOT_START, logicalChannel,
                    MachineAudioSupport.u8(payload, offset + 1) & 0x1F,
                    code, MachineAudioSupport.u8(payload, offset + 2) & 0x7F, true);
        }
        if (code == 10) {
            if (!MachineAudioSupport.has(payload, offset + 1, 1)) {
                MachineAudioSupport.truncated(event, match, diagnostics, "mixed audio-stop operand");
                return null;
            }
            return controlAction(
                    event, match, order, AudioProgram.ActionKind.SLOT_STOP, logicalChannel,
                    MachineAudioSupport.u8(payload, offset + 1) & 0x1F, code, -1, true);
        }
        if (code == 11) {
            if (!MachineAudioSupport.has(payload, offset + 1, 2)) {
                MachineAudioSupport.truncated(event, match, diagnostics, "mixed pan-control operands");
                return null;
            }
            int value = doubledValue(MachineAudioSupport.u8(payload, offset + 2));
            if (value < 0) return null;
            return controlAction(
                    event, match, order, AudioProgram.ActionKind.CHANNEL_PAN, logicalChannel,
                    -1, code, value, true);
        }
        if (code == 12) {
            if (!MachineAudioSupport.has(payload, offset + 1, 1)) {
                MachineAudioSupport.truncated(event, match, diagnostics, "mixed slot-control operand");
                return null;
            }
            return controlAction(
                    event, match, order, AudioProgram.ActionKind.SLOT_CONTROL, logicalChannel,
                    -1, code, MachineAudioSupport.u8(payload, offset + 1), true);
        }
        return null;
    }


    static AudioProgram.AudioAction audioProfile(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            List<Diagnostic> diagnostics) {
        if (match.descriptorIndex != 25) {
            return null;
        }
        boolean recognizedLayout = payload.length == 14;
        int[] selectors = new int[] {0x81, 0x89, 0x83, 0x8B};
        int offset = match.bodyOffset;
        for (int i = 0; recognizedLayout && i < selectors.length; i++, offset += 3) {
            if (!MachineAudioSupport.has(payload, offset, 3)
                    || MachineAudioSupport.u8(payload, offset) != selectors[i]
                    || MachineAudioSupport.u8(payload, offset + 1) != 1) {
                recognizedLayout = false;
            }
        }
        if (!recognizedLayout) {
            diagnostics.add(new Diagnostic(
                    "AUDIO_718F_METADATA_UNMODELED",
                    Diagnostic.Severity.WARNING,
                    event.trackIndex,
                    event.eventIndex,
                    event.rawTick,
                    "MFiAudio 71:8F metadata layout is not modeled; Player handler 0x103 is a no-op and 71:84 remains authoritative."));
        }
        return MachineAudioSupport.action(
                order, event, match, AudioProgram.ActionKind.NO_ACTION,
                -1, -1, -1, -1, AudioProgram.AudioType.NONE,
                -1, -1, -1, -1, -1, -1, -1, -1L, -1,
                false, false, true,
                AudioProgram.RendererSupport.CONTROL_ONLY,
                AudioProgram.BranchEffect.STATE_ONLY,
                AudioProgram.BranchEffect.STATE_ONLY,
                MachineAudioSupport.slice(payload, match.bodyOffset, payload.length));
    }

    static AudioProgram.AudioAction route(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            byte[] payload,
            int offset,
            List<Diagnostic> diagnostics) {
        if (!MachineAudioSupport.has(payload, offset, 16)) {
            MachineAudioSupport.truncated(event, match, diagnostics, "16-byte route map");
            return null;
        }
        return MachineAudioSupport.action(
                order,
                event,
                match,
                AudioProgram.ActionKind.ROUTE_UPDATE,
                -1,
                -1,
                -1,
                -1,
                AudioProgram.AudioType.NONE,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1L,
                -1,
                false,
                true,
                true,
                AudioProgram.RendererSupport.CONTROL_ONLY,
                AudioProgram.BranchEffect.PHASE_GATED_BACKEND,
                AudioProgram.BranchEffect.STATE_ONLY,
                MachineAudioSupport.slice(payload, offset, offset + 16));
    }

    private static AudioProgram.AudioAction controlAction(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            AudioProgram.ActionKind kind,
            int logicalChannel,
            int slot,
            int operation,
            int value,
            boolean phaseGated) {
        return MachineAudioSupport.action(
                order, event, match, kind, logicalChannel, slot, -1, -1,
                AudioProgram.AudioType.NONE, operation, -1, -1, -1, -1, -1, -1, -1L,
                value, false, phaseGated, true, AudioProgram.RendererSupport.CONTROL_ONLY,
                phaseGated ? AudioProgram.BranchEffect.PHASE_GATED_BACKEND : AudioProgram.BranchEffect.BACKEND_ACTION,
                AudioProgram.BranchEffect.NO_ACTION, null);
    }

    private static int doubledValue(int raw) {
        if (raw == 0x80 || raw == 0xFF) return 0x40;
        return raw <= 0x3F ? raw * 2 : -1;
    }
}
