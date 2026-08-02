package com.hubertstudios.coredsc.competitive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EloCalculatorTest {
    @Test
    void equalRatingsExchangeHalfTheKFactor() {
        EloCalculator.Update update = EloCalculator.calculate(1_000, 1_000, 32, 32, 100);

        assertEquals(1_016, update.winnerAfter());
        assertEquals(984, update.loserAfter());
        assertEquals(16, update.winnerDelta());
        assertEquals(-16, update.loserDelta());
    }

    @Test
    void upsetAwardsMoreThanExpectedWin() {
        EloCalculator.Update upset = EloCalculator.calculate(800, 1_200, 32, 32, 100);
        EloCalculator.Update expected = EloCalculator.calculate(1_200, 800, 32, 32, 100);

        assertTrue(upset.winnerDelta() > expected.winnerDelta());
        assertEquals(29, upset.winnerDelta());
        assertEquals(-29, upset.loserDelta());
    }

    @Test
    void minimumRatingClampsOnlyTheLoss() {
        EloCalculator.Update update = EloCalculator.calculate(1_000, 1_000, 32, 32, 995);

        assertEquals(995, update.loserAfter());
        assertEquals(-5, update.loserDelta());
        assertTrue(update.winnerDelta() >= 1);
    }

    @Test
    void provisionalPlayersCanUseASeparateKFactor() {
        EloCalculator.Update update = EloCalculator.calculate(1_000, 1_000, 48, 32, 100);

        assertEquals(24, update.winnerDelta());
        assertEquals(-16, update.loserDelta());
    }

    @Test
    void rejectsInvalidPolicyInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> EloCalculator.calculate(1_000, 1_000, 0, 32, 100));
        assertThrows(IllegalArgumentException.class,
                () -> EloCalculator.calculate(1_000, 1_000, 32, 32, -1));
    }
}
