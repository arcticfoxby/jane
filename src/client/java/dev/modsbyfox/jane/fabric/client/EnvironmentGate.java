package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.OneShotExtraJoin;
import java.util.List;

/** Pure, local login routing. Source resolution is deliberately absent from this path. */
final class EnvironmentGate {
    enum Route { EXACT_PASS, DECISION }

    private EnvironmentGate() { }

    static Route route(List<Comparison.Result> required, ClientCompatibilityReport extras) {
        return Comparison.passed(required) && !OneShotExtraJoin.hasExtras(extras)
                ? Route.EXACT_PASS : Route.DECISION;
    }
}
