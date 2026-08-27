package mld.semantic;

import java.util.List;
import java.util.Set;

final class WarningSink {

    private WarningSink() {
    }

    static void addOnce(List<String> warnings, Set<String> keys, String key, String warning) {
        if (keys.add(key)) warnings.add(warning);
    }
}
