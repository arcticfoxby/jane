package dev.modsbyfox.jane.fabric.client;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.PendingSyncContext;

sealed interface PendingClientAction {
    record RequiredSync(PendingSyncContext context) implements PendingClientAction { }
    record CompatibilityReview(String serverId, ClientCompatibilityReport report,
                               ReconnectTarget target) implements PendingClientAction { }
}
