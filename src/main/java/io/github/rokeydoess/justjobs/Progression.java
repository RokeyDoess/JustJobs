package io.github.rokeydoess.justjobs;

public final class Progression {
    private final double first, early, plateau, multiplier;
    private final int earlyLevel, plateauLevel;
    private final double[] thresholds;

    public Progression(double first, double early, double plateau, int earlyLevel, int plateauLevel, double multiplier) {
        if (!Double.isFinite(first) || !Double.isFinite(early) || !Double.isFinite(plateau) || !Double.isFinite(multiplier)
                || first <= 0 || early < first || plateau < early || plateau > 1_000_000_000
                || earlyLevel < 2 || plateauLevel <= earlyLevel || plateauLevel > 10_000
                || multiplier <= 0 || multiplier > 1_000_000) {
            throw new IllegalArgumentException("Progression needs 0 < first-level-xp <= early-level-xp <= plateau-xp <= 1 billion, "
                    + "2 <= early-level < plateau-level <= 10000, and 0 < xp-multiplier <= 1000000");
        }
        this.first = first; this.early = early; this.plateau = plateau;
        this.earlyLevel = earlyLevel; this.plateauLevel = plateauLevel; this.multiplier = multiplier;
        thresholds = new double[plateauLevel];
        for (int i = 1; i < thresholds.length; i++) thresholds[i] = thresholds[i - 1] + required(i);
    }
    public static Progression defaults() { return new Progression(25, 150, 2500, 10, 50, 1); }
    public double first() { return first; }
    public double early() { return early; }
    public double plateau() { return plateau; }
    public int earlyLevel() { return earlyLevel; }
    public int plateauLevel() { return plateauLevel; }
    public double multiplier() { return multiplier; }

    public double required(int level) {
        int at = Math.max(1, level);
        double base = at < earlyLevel ? first + (early - first) * (at - 1.0) / (earlyLevel - 1)
                : at < plateauLevel ? early + (plateau - early) * (at - (double) earlyLevel) / (plateauLevel - earlyLevel) : plateau;
        return Math.ceil(base * multiplier);
    }

    public record Progress(int level, double xp, double required) {
        public double fraction() { return Math.clamp(xp / required, 0, 1); }
    }

    public Progress progress(double total) {
        if (!Double.isFinite(total) || total < 0) throw new IllegalArgumentException("Invalid total XP");
        int last = thresholds.length - 1;
        if (total >= thresholds[last]) {
            double quota = required(plateauLevel);
            long extra = Math.min((long) ((total - thresholds[last]) / quota), Integer.MAX_VALUE - (long) plateauLevel);
            return new Progress(plateauLevel + (int) extra, total - thresholds[last] - extra * quota, quota);
        }
        int index = java.util.Arrays.binarySearch(thresholds, total);
        if (index < 0) index = -index - 2;
        return new Progress(index + 1, total - thresholds[index], required(index + 1));
    }
}
