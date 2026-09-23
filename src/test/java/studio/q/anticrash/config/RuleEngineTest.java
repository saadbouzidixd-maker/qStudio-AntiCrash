package studio.q.anticrash.config;

import org.junit.jupiter.api.Test;

import studio.q.anticrash.api.Rule;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.mitigation.Action;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    @Test
    void severityParsesCaseInsensitive() {
        assertEquals(Severity.HIGH, Severity.parse("high", Severity.MEDIUM));
        assertEquals(Severity.CRITICAL, Severity.parse("  Critical ", Severity.MEDIUM));
        assertEquals(Severity.MEDIUM, Severity.parse("bogus", Severity.MEDIUM));
        assertEquals(Severity.MEDIUM, Severity.parse(null, Severity.MEDIUM));
    }

    @Test
    void severityWeightsOrder() {
        assertTrue(Severity.LOW.weight() < Severity.MEDIUM.weight());
        assertTrue(Severity.MEDIUM.weight() < Severity.HIGH.weight());
        assertTrue(Severity.HIGH.weight() < Severity.CRITICAL.weight());
    }

    @Test
    void ruleBuilderDefaults() {
        Rule r = Rule.builder("packet-flood").build();
        assertEquals("packet-flood", r.id());
        assertTrue(r.enabled());
        assertEquals(Severity.MEDIUM, r.severity());
        assertEquals(1000, r.cooldownMs());
        assertEquals(List.of("LOG"), r.actions());
    }

    @Test
    void ruleBuilderNormalizesId() {
        Rule r = Rule.builder("Packet-Flood").build();
        assertEquals("packet-flood", r.id());
    }

    @Test
    void actionParsing() {
        assertEquals(Action.CANCEL, Action.parse("cancel"));
        assertEquals(Action.RATE_LIMIT, Action.parse("rate-limit"));
        assertEquals(Action.DISCONNECT, Action.parse("DISCONNECT"));
        assertEquals(Action.LOG, Action.parse("unknown-action"));
        assertEquals(Action.LOG, Action.parse(null));
    }

    @Test
    void actionParsingAll() {
        var set = Action.parseAll(List.of("LOG", "ALERT"));
        assertTrue(set.contains(Action.LOG));
        assertTrue(set.contains(Action.ALERT));
        // Empty list falls back to LOG.
        assertTrue(Action.parseAll(List.of()).contains(Action.LOG));
        assertTrue(Action.parseAll(null).contains(Action.LOG));
    }

    @Test
    void actionPlanVerdicts() {
        var cancelPlan = studio.q.anticrash.mitigation.ActionPlan.of(java.util.Set.of(Action.CANCEL));
        assertTrue(cancelPlan.shouldCancel());
        assertFalse(cancelPlan.shouldDisconnect());

        var kickPlan = studio.q.anticrash.mitigation.ActionPlan.of(java.util.Set.of(Action.KICK));
        assertTrue(kickPlan.shouldDisconnect());
        assertFalse(kickPlan.shouldCancel());

        var nonePlan = studio.q.anticrash.mitigation.ActionPlan.none();
        assertTrue(nonePlan.isEmpty());
        assertFalse(nonePlan.shouldCancel());
    }
}
