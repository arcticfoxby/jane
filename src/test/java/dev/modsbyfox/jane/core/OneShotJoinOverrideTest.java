package dev.modsbyfox.jane.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OneShotJoinOverrideTest {
    private static final String SERVER = ServerIdentity.id("one.example.org");
    private static final String OTHER_SERVER = ServerIdentity.id("two.example.org");

    private static RequiredManifest manifest(String hash) {
        return new RequiredManifest(RequiredManifest.PROTOCOL,
                List.of(new ManifestEntry("create", "Create", "1.0", 12, hash)));
    }

    private static List<Comparison.Result> results(RequiredManifest manifest, Comparison.Status status) {
        var entry = manifest.entries().get(0);
        var local = status == Comparison.Status.MISSING ? null
                : new Comparison.LocalMod(entry.modId(), entry.version(), Path.of("create.jar"));
        return List.of(new Comparison.Result(entry, local, status,
                status == Comparison.Status.OK ? entry.sha512() : null));
    }

    private static SyncSelection selection(RequiredManifest manifest, List<Comparison.Result> results) {
        SyncSelection selection = new SyncSelection(new PendingSyncContext("one.example.org", manifest, results));
        for (Comparison.Result result : results)
            if (result.status() != Comparison.Status.OK) selection.setSelected(result.required().modId(), false);
        return selection;
    }

    @Test void exactPassIsDistinctFromOverrideAndWireCodesStayOneByte() {
        assertEquals(4, RequiredManifest.PROTOCOL);
        assertEquals(0, LoginStatus.EXACT_PASS.code());
        assertEquals(1, LoginStatus.ACTION_REQUIRED.code());
        assertEquals(2, LoginStatus.PROTOCOL_ERROR.code());
        assertEquals(3, LoginStatus.USER_OVERRIDE.code());
        for (LoginStatus status : LoginStatus.values()) assertSame(status, LoginStatus.fromCode(status.code()));
        assertTrue(LoginStatus.EXACT_PASS.allowsJaneGate());
        assertTrue(LoginStatus.USER_OVERRIDE.allowsJaneGate());
        assertFalse(LoginStatus.ACTION_REQUIRED.allowsJaneGate());
        assertFalse(LoginStatus.PROTOCOL_ERROR.allowsJaneGate());
        assertThrows(IllegalArgumentException.class, () -> LoginStatus.fromCode(4));
    }

    @Test void noConfirmationCannotOverrideAndMatchingIntentIsConsumedOnce() {
        var manifest = manifest("a".repeat(128));
        var missing = results(manifest, Comparison.Status.MISSING);
        var override = new OneShotJoinOverride();
        Object login = new Object();
        override.beginConnection(login);
        assertFalse(override.consume(login, SERVER, manifest, missing));
        assertTrue(override.arm(SERVER, manifest, selection(manifest, missing), missing));
        override.beginConnection(login);
        assertTrue(override.consume(login, SERVER, manifest, missing));
        assertFalse(override.consume(login, SERVER, manifest, missing));
        assertFalse(Comparison.passed(missing), "A skipped Required mod must never be reported as exact pass");
    }

    @Test void serverManifestAndConnectionChangesInvalidateIntent() {
        var original = manifest("a".repeat(128));
        var changed = manifest("c".repeat(128));
        var override = new OneShotJoinOverride();
        Object login = new Object();
        assertTrue(override.arm(SERVER, original,
                selection(original, results(original, Comparison.Status.MISSING)),
                results(original, Comparison.Status.MISSING)));
        override.beginConnection(login);
        assertFalse(override.consume(login, OTHER_SERVER, original, results(original, Comparison.Status.MISSING)));
        assertFalse(override.consume(login, SERVER, original, results(original, Comparison.Status.MISSING)));

        assertTrue(override.arm(SERVER, original,
                selection(original, results(original, Comparison.Status.MISSING)),
                results(original, Comparison.Status.MISSING)));
        override.beginConnection(login);
        assertFalse(override.consume(login, SERVER, changed, results(changed, Comparison.Status.MISSING)));

        assertTrue(override.arm(SERVER, original,
                selection(original, results(original, Comparison.Status.MISSING)),
                results(original, Comparison.Status.MISSING)));
        override.beginConnection(login);
        override.beginConnection(new Object());
        assertFalse(override.consume(login, SERVER, original, results(original, Comparison.Status.MISSING)));
    }

    @Test void intentExpiresAndIsBoundToItsClientSession() {
        MutableClock clock = new MutableClock();
        var original = manifest("a".repeat(128));
        var missing = results(original, Comparison.Status.MISSING);
        var override = new OneShotJoinOverride(clock);
        Object login = new Object();
        assertTrue(override.arm(SERVER, original, selection(original, missing), missing));
        override.beginConnection(login);
        clock.advance(Duration.ofSeconds(91));
        assertFalse(override.consume(login, SERVER, original, missing));
        assertFalse(new OneShotJoinOverride(clock).consume(login, SERVER, original, missing));
    }

    @Test void localFileErrorsAndAmbiguousDuplicatesCannotBeOverridden() {
        var original = manifest("a".repeat(128));
        var missing = results(original, Comparison.Status.MISSING);
        var bad = results(original, Comparison.Status.FILE_ERROR);
        var override = new OneShotJoinOverride();
        assertFalse(override.arm(SERVER, original, selection(original, bad), bad));
        var exact = results(original, Comparison.Status.OK);
        assertFalse(override.arm(SERVER, original, selection(original, exact), exact));
        var candidate = new Comparison.LocalMod("create", "1.0", Path.of("duplicate.jar"));
        var ambiguous = List.of(new Comparison.Result(original.entries().get(0), null,
                Comparison.Status.MISSING, null, null, List.of(candidate)));
        assertFalse(override.arm(SERVER, original, selection(original, ambiguous), ambiguous));

        assertTrue(override.arm(SERVER, original, selection(original, missing), missing));
        Object login = new Object();
        override.beginConnection(login);
        assertFalse(override.consume(login, SERVER, original, bad));
        assertFalse(override.consume(login, SERVER, original, missing));
        assertTrue(override.arm(SERVER, original, selection(original, missing), missing));
        override.beginConnection(new Object());
        assertFalse(override.consume(login, SERVER, original, missing));
    }

    @Test void freshAssessmentMustMatchTheFilesSeenAtConfirmation() {
        var first = new ManifestEntry("create", "Create", "1.0", 12, "a".repeat(128));
        var second = new ManifestEntry("fabric_api", "Fabric API", "1.0", 12, "b".repeat(128));
        var manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(first, second));
        var originallyMatched = new Comparison.LocalMod("create", "1.0", Path.of("create.jar"));
        var before = List.of(new Comparison.Result(first, originallyMatched, Comparison.Status.OK, first.sha512()),
                new Comparison.Result(second, null, Comparison.Status.MISSING, null));
        var after = List.of(new Comparison.Result(first, null, Comparison.Status.MISSING, null),
                new Comparison.Result(second, null, Comparison.Status.MISSING, null));
        var override = new OneShotJoinOverride();
        Object login = new Object();
        assertTrue(override.arm(SERVER, manifest, selection(manifest, before), before));
        override.beginConnection(login);
        assertFalse(override.consume(login, SERVER, manifest, after),
                "A previously matched Required JAR disappearing must require new confirmation");

        var one = manifest("a".repeat(128));
        var local = new Comparison.LocalMod("create", "1.0", Path.of("create.jar"));
        var oldHash = List.of(new Comparison.Result(one.entries().get(0), local,
                Comparison.Status.HASH_MISMATCH, "c".repeat(128)));
        var newHash = List.of(new Comparison.Result(one.entries().get(0), local,
                Comparison.Status.HASH_MISMATCH, "d".repeat(128)));
        assertTrue(override.arm(SERVER, one, selection(one, oldHash), oldHash));
        Object nextLogin = new Object();
        override.beginConnection(nextLogin);
        assertFalse(override.consume(nextLogin, SERVER, one, newHash),
                "A different local SHA-512 must require new confirmation");
    }

    @Test void exactRequiredWithRetainedDuplicateRemainsUnambiguous() {
        var first = new ManifestEntry("create", "Create", "1.0", 12, "a".repeat(128));
        var second = new ManifestEntry("fabric_api", "Fabric API", "1.0", 12, "b".repeat(128));
        var manifest = new RequiredManifest(RequiredManifest.PROTOCOL, List.of(first, second));
        var exact = new Comparison.LocalMod("create", "1.0", Path.of("create-exact.jar"));
        var retained = new Comparison.LocalMod("create", "0.9", Path.of("create-old.jar"));
        var assessment = List.of(new Comparison.Result(first, exact, Comparison.Status.OK,
                        first.sha512(), null, List.of(retained)),
                new Comparison.Result(second, null, Comparison.Status.MISSING, null));
        var override = new OneShotJoinOverride();
        assertTrue(override.arm(SERVER, manifest, selection(manifest, assessment), assessment));
        Object login = new Object();
        override.beginConnection(login);
        assertTrue(override.consume(login, SERVER, manifest, assessment));
    }

    @Test void selectionMustRemainExactlyAsConfirmedAndCannotContainPendingDownloads() {
        var manifest = manifest("a".repeat(128));
        var missing = results(manifest, Comparison.Status.MISSING);
        var choice = selection(manifest, missing);
        var override = new OneShotJoinOverride();
        assertTrue(override.arm(SERVER, manifest, choice, missing));
        Object login = new Object();
        override.beginConnection(login);
        choice.setSelected("create", true);
        choice.setSelected("create", false);
        assertFalse(override.consume(login, SERVER, manifest, missing),
                "Restoring the same checkbox digest must not reuse an older confirmation");
        choice.setSelected("create", true);
        assertFalse(override.arm(SERVER, manifest, choice, missing),
                "A selected, uninstalled Required mod needs the updater, not a direct override join");
    }

    @Test void manifestDigestExcludesProviderTokenAndTracksManifestContent() {
        var first = manifest("a".repeat(128));
        var second = manifest("c".repeat(128));
        assertEquals(64, OneShotJoinOverride.manifestDigest(first).length());
        assertEquals(OneShotJoinOverride.manifestDigest(first), OneShotJoinOverride.manifestDigest(first));
        assertNotEquals(OneShotJoinOverride.manifestDigest(first), OneShotJoinOverride.manifestDigest(second));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
