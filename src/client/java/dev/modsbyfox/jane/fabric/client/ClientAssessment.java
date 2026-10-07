package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.fabric.ClientPhysicalDiscovery;
import java.util.List;

record ClientAssessment(ClientPhysicalDiscovery.Discovery discovery,
                        List<Comparison.Result> requiredResults,
                        ClientCompatibilityReport compatibility) {
    ClientAssessment {
        requiredResults = List.copyOf(requiredResults);
    }
    boolean requiredPassed() { return Comparison.passed(requiredResults); }
}
