package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;

/** Saved money totals used by the admin economy report. */
public final class JobEconomy {
    private BigDecimal earned = BigDecimal.ZERO, capped = BigDecimal.ZERO, paid = BigDecimal.ZERO;
    private long capHits;

    public record Snapshot(BigDecimal earned, BigDecimal capped, BigDecimal paid, long capHits) { }

    public void earnings(BigDecimal earned, BigDecimal capped, boolean reachedCap) {
        this.earned = this.earned.add(earned);
        this.capped = this.capped.add(capped);
        if (reachedCap) capHits++;
    }

    public void paid(BigDecimal amount) { paid = paid.add(amount); }

    public Snapshot snapshot() { return new Snapshot(earned, capped, paid, capHits); }

    public void restore(Snapshot saved) {
        earned = saved.earned(); capped = saved.capped(); paid = saved.paid(); capHits = saved.capHits();
    }
}
