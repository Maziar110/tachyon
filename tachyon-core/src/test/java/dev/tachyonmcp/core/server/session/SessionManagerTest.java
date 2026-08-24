/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.runtime.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionManagerTest {

    @Test
    void onSessionClosedFiresOnExplicitRemoval() {
        List<String> closed = new ArrayList<>();
        var manager = new SessionManager(closed::add);
        manager.createSession("s1");

        manager.removeSession("s1");

        assertThat(closed).containsExactly("s1");
    }

    @Test
    void onSessionClosedFiresOnSweepEviction() {
        List<String> closed = new ArrayList<>();
        var manager = new SessionManager(closed::add);
        manager.createSession("s1");

        manager.sweep(-1); // any idle time exceeds a negative TTL

        assertThat(closed).containsExactly("s1");
    }

    @Test
    void onSessionClosedDoesNotFireForUnknownSession() {
        List<String> closed = new ArrayList<>();
        var manager = new SessionManager(closed::add);

        manager.removeSession("never-created");

        assertThat(closed).isEmpty();
    }

    @Test
    void sweepEvictsExpiredSessions() {
        var manager = new SessionManager();
        var session = manager.createSession("s1");

        manager.sweep(-1); // any idle time exceeds a negative TTL

        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(manager.getSession("s1")).isEmpty();
    }

    @Test
    void sweepKeepsFreshSessions() {
        var manager = new SessionManager();
        var session = manager.createSession("s1");
        session.activate();

        manager.sweep(Long.MAX_VALUE);

        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(manager.getSession("s1")).contains(session);
    }

    /**
     * The race {@link SessionManager#removeIfCurrent} exists for: the sweep iterates a snapshot
     * containing a stale session while the live table already holds a replacement created under
     * the same id (custom SessionIdGenerator scenario). The stale session must be closed, but
     * the replacement must survive the eviction.
     */
    @Test
    void removeIfCurrentEvictsOnlyTheExpectedInstance() {
        var manager = new SessionManager();
        var stale = manager.createSession("s1");

        // A replacement session appears under the same id (custom SessionIdGenerator scenario)
        // between the janitor's expiry check and its removal.
        var replacement = manager.createSession("s1");

        assertThat(manager.removeIfCurrent("s1", stale)).isFalse();
        assertThat(manager.getSession("s1")).contains(replacement);

        assertThat(manager.removeIfCurrent("s1", replacement)).isTrue();
        assertThat(manager.getSession("s1")).isEmpty();
    }
}
