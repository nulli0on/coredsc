package com.hubertstudios.coredsc.competitive;


public final class EloCalculator {
    public record Update(
            int winnerBefore,
            int winnerAfter,
            int loserBefore,
            int loserAfter,
            int winnerDelta,
            int loserDelta
    ) { }

    private EloCalculator() { }

    public static Update calculate(
            int winnerRating,
            int loserRating,
            int winnerK,
            int loserK,
            int minimumRating
    ) {
        if (winnerK < 1 || loserK < 1) {
            throw new IllegalArgumentException("ELO K-factors must be positive");
        }
        if (minimumRating < 0) {
            throw new IllegalArgumentException("Minimum ELO rating cannot be negative");
        }
        double winnerExpected = expected(winnerRating, loserRating);
        double loserExpected = expected(loserRating, winnerRating);
        int winnerDelta = Math.max(1, (int) Math.round(winnerK * (1.0D - winnerExpected)));
        int requestedLoserDelta = Math.max(1, (int) Math.round(loserK * loserExpected));
        int loserAfter = Math.max(minimumRating, loserRating - requestedLoserDelta);
        int actualLoserDelta = loserRating - loserAfter;
        return new Update(
                winnerRating,
                winnerRating + winnerDelta,
                loserRating,
                loserAfter,
                winnerDelta,
                -actualLoserDelta);
    }

    private static double expected(int rating, int opponentRating) {
        return 1.0D / (1.0D + Math.pow(10.0D, (opponentRating - rating) / 400.0D));
    }
}
