package mld.semantic;

import java.util.List;
import java.util.Set;

import mld.format.MldDocument;

/**
 * Header-level rules that affect semantic compilation.
 */
final class ContainerRules {

    private ContainerRules() {
    }

    static void emitFirstOnlyWarnings(MldDocument d, List<String> w, Set<String> k) {
        add(d, w, k, "vers", "version window");
        add(d, w, k, "sorc", "source/flags value");
        add(d, w, k, "note", "note extra-byte count");
        add(d, w, k, "exst", "resource extension count");
        add(d, w, k, "date", "date text");
    }

    private static void add(MldDocument d, List<String> w, Set<String> k, String id, String meaning) {
        if (d.countTopLevelChunks(id) > 1) {
            WarningSink.addOnce(
                    w,
                    k,
                    id + "_multiple",
                    "Multiple " + id + " chunks were present; only the first accepted " + id
                            + " chunk is used as the authoritative " + meaning + ".");
        }
    }
}
