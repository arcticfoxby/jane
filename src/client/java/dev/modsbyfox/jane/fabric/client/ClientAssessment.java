package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.fabric.ClientPhysicalDiscovery;
import java.util.List;
import java.util.Objects;

record ClientAssessment(ClientPhysicalDiscovery.Discovery discovery,
                        List<Comparison.Result> requiredResults,
                        ClientCompatibilityReport compatibility) {
    ClientAssessment {
        Objects.requireNonNull(discovery, "discovery");
        requiredResults = List.copyOf(requiredResults);
        Objects.requireNonNull(compatibility, "compatibility");
    }
    boolean requiredPassed() { return Comparison.passed(requiredResults); }
}
