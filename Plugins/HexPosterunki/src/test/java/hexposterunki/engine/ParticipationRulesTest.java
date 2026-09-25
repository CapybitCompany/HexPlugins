package hexposterunki.engine;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 5 regression: damage and kill credit share one rule set, and it checks far more than
 * town membership. This is the exact function the listener and the engine call.
 */
class ParticipationRulesTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** A full participant: everything true, right town. */
    private static Participation eligible() {
        return ParticipationRules.evaluate(true, true, true, true, false, true, true, TOWN, TOWN);
    }

    @Test
    void aFullParticipantMayDamageAndEarnKills() {
        Participation result = eligible();
        assertEquals(Participation.ELIGIBLE, result);
        assertTrue(result.mayDamage());
        assertTrue(result.mayEarnKill());
    }

    @Test
    void shootingFromOutsideTheRegionEarnsNothing() {
        Participation result = ParticipationRules.evaluate(true, true, true, true, false,
                false, true, TOWN, TOWN);
        assertEquals(Participation.OUTSIDE_REGION, result);
        assertFalse(result.mayDamage());
        assertFalse(result.mayEarnKill());
    }

    @Test
    void anUntrackedPlayerInsideTheRegionIsStillNotAParticipant() {
        Participation result = ParticipationRules.evaluate(true, true, true, true, false,
                true, false, TOWN, TOWN);
        assertEquals(Participation.OUTSIDE_REGION, result);
    }

    @Test
    void aDeadOrOfflineOrSpectatingPlayerEarnsNothing() {
        assertEquals(Participation.NOT_ALIVE,
                ParticipationRules.evaluate(true, true, false, true, false, true, true, TOWN, TOWN));
        assertEquals(Participation.NOT_ALIVE,
                ParticipationRules.evaluate(true, true, true, false, false, true, true, TOWN, TOWN));
        assertEquals(Participation.NOT_ALIVE,
                ParticipationRules.evaluate(true, true, true, true, true, true, true, TOWN, TOWN));
    }

    @Test
    void aProjectileLandingAfterTheShooterDiedEarnsNothing() {
        // The rules are evaluated at impact, so the shooter's state right now decides.
        Participation atImpact = ParticipationRules.evaluate(true, true, true, false, false,
                true, true, TOWN, TOWN);
        assertFalse(atImpact.mayEarnKill());
    }

    @Test
    void aProjectileLandingAfterTheShooterLeftEarnsNothing() {
        Participation atImpact = ParticipationRules.evaluate(true, true, true, true, false,
                false, false, TOWN, TOWN);
        assertEquals(Participation.OUTSIDE_REGION, atImpact);
        assertFalse(atImpact.mayEarnKill());
    }

    @Test
    void aPlayerWithoutATownCanNeitherDamageNorScore() {
        Participation result = ParticipationRules.evaluate(true, true, true, true, false,
                true, true, null, TOWN);
        assertEquals(Participation.NO_TOWN, result);
        assertFalse(result.mayDamage());
        assertFalse(result.mayEarnKill());
    }

    @Test
    void aForeignTownMemberCannotDamageTheDefenders() {
        Participation result = ParticipationRules.evaluate(true, true, true, true, false,
                true, true, OTHER_TOWN, TOWN);
        assertEquals(Participation.WRONG_TOWN, result);
        assertFalse(result.mayDamage());
    }

    @Test
    void anUnclaimedOutpostAcceptsAnyTown() {
        Participation result = ParticipationRules.evaluate(true, true, true, true, false,
                true, true, OTHER_TOWN, null);
        assertEquals(Participation.ELIGIBLE, result);
    }

    @Test
    void outsideACombatPhaseNothingCounts() {
        assertEquals(Participation.NO_ACTIVE_RUN,
                ParticipationRules.evaluate(false, true, true, true, false, true, true, TOWN, TOWN));
        assertEquals(Participation.NO_ACTIVE_RUN,
                ParticipationRules.evaluate(true, false, true, true, false, true, true, TOWN, TOWN));
    }

    @Test
    void anAdminBypassUnlocksDamageButNeverRankingOrRewards() {
        Participation bypassed = ParticipationRules.withBypass(
                ParticipationRules.evaluate(true, true, true, true, false, false, false, null, TOWN), true);

        assertEquals(Participation.ADMIN_BYPASS, bypassed);
        assertTrue(bypassed.mayDamage());
        assertFalse(bypassed.mayEarnKill(), "bypass nie może tworzyć roszczeń do nagród");
    }

    @Test
    void aBypassDoesNotDowngradeARealParticipant() {
        assertEquals(Participation.ELIGIBLE, ParticipationRules.withBypass(eligible(), true));
    }

    @Test
    void everyRefusalCarriesAPolishReason() {
        for (Participation value : Participation.values()) {
            if (value == Participation.ELIGIBLE || value == Participation.ADMIN_BYPASS) {
                continue;
            }
            assertFalse(value.polishReason().isBlank(), value + " nie ma polskiego powodu");
        }
    }

    @Test
    void aFrozenEncounterSuspendsEveryoneAndBypassDoesNotLiftIt() {
        assertEquals(null, ParticipationRules.gate(EngineMode.RUNNING, true));
        assertEquals(Participation.SUSPENDED, ParticipationRules.gate(EngineMode.PERSISTENCE_PAUSED, true));
        assertEquals(Participation.SUSPENDED, ParticipationRules.gate(EngineMode.STOPPED, true));
        assertEquals(Participation.SUSPENDED, ParticipationRules.gate(EngineMode.RESETTING, true));
        assertEquals(Participation.NO_ACTIVE_RUN, ParticipationRules.gate(EngineMode.PERSISTENCE_PAUSED, false));

        assertFalse(Participation.SUSPENDED.mayDamage());
        assertFalse(Participation.SUSPENDED.mayEarnKill());
        assertEquals(Participation.SUSPENDED, ParticipationRules.withBypass(Participation.SUSPENDED, true),
                "wstrzymanej walki nie odblokowuje uprawnienie administratora");
    }
}
