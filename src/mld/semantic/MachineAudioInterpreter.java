package mld.semantic;

import java.util.List;

import mld.decode.MachineDependentEvent;
import mld.decode.MachineDescriptorMatcher;

/** Dispatches matched machine-dependent handlers to typed audio interpreters. */
final class MachineAudioInterpreter {
    private MachineAudioInterpreter() {
    }

    static AudioProgram.AudioAction interpret(
            MachineDependentEvent event,
            MachineDescriptorMatcher.Match match,
            int order,
            List<Diagnostic> diagnostics) {
        if (event == null || match == null) return null;
        byte[] payload = event.copyPayload();
        switch (match.handlerId) {
            case 0x000:
                return MachineControlInterpreter.channelControl(
                        event, match, order, payload, false, true, diagnostics);
            case 0x001:
                return MachineControlInterpreter.channelControl(
                        event, match, order, payload, false, false, diagnostics);
            case 0x104:
                return MachineControlInterpreter.channelControl(
                        event, match, order, payload, true, true, diagnostics);
            case 0x105:
                return MachineControlInterpreter.channelControl(
                        event, match, order, payload, true, false, diagnostics);
            case 0x002:
                return MachineSlotInterpreter.slot8000(event, match, order, payload, false, diagnostics);
            case 0x003:
                return MachineSlotInterpreter.slot8000(event, match, order, payload, true, diagnostics);
            case 0x103:
                return MachineControlInterpreter.audioProfile(
                        event, match, order, payload, diagnostics);
            case 0x109:
                return MachineSlotInterpreter.slot8001(event, match, order, payload, false, diagnostics);
            case 0x106:
                return MachineSlotInterpreter.slot8001(event, match, order, payload, true, diagnostics);
            case 0x400:
                return MachineSlotInterpreter.slot8002(
                        event, match, order, payload, match.bodyOffset, diagnostics);
            case 0x401:
                return MachineControlInterpreter.compactControl(
                        event, match, order, payload, match.bodyOffset, true, diagnostics);
            case 0x402:
                return MachineControlInterpreter.mixedDispatch(event, match, order, payload, diagnostics);
            case 0x407:
                return MachineControlInterpreter.route(
                        event, match, order, payload, match.bodyOffset, diagnostics);
            default:
                return null;
        }
    }
}
