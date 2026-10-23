package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentDeliveryTest {
    private static class Account implements PaymentDelivery.Account {
        BigDecimal balance = new BigDecimal("100");
        boolean fail, throwBefore, throwAfter, returnFailureAfter, unavailableBalance, cancelCredit, unavailableAfter;
        BigDecimal maximumCredit;
        int attempts;
        public BigDecimal balance() { if (unavailableBalance) throw new IllegalStateException(); return balance; }
        public boolean deposit(BigDecimal amount) {
            attempts++;
            if (throwBefore) throw new IllegalStateException();
            if (fail) return false;
            if (!cancelCredit) balance = balance.add(maximumCredit == null ? amount : amount.min(maximumCredit));
            if (unavailableAfter) unavailableBalance = true;
            if (throwAfter) throw new IllegalStateException();
            return !returnFailureAfter;
        }
    }
    @Test void successfulPaymentsAreDeliveredOnce() {
        var account = new Account();
        assertEquals(BigDecimal.ONE, PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
        assertEquals(new BigDecimal("101"), account.balance);
        assertEquals(1, account.attempts);
    }
    @Test void failedPaymentsCanRetryWithoutIntervention() {
        var account = new Account(); account.fail = true;
        assertEquals(0, PaymentDelivery.deliver(account, BigDecimal.ONE).credited().signum());
        account.fail = false;
        assertEquals(BigDecimal.ONE, PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
        assertEquals(new BigDecimal("101"), account.balance);
    }
    @Test void exceptionsBeforeCreditLeaveMoneyAvailableForRetry() {
        var account = new Account(); account.throwBefore = true;
        assertEquals(0, PaymentDelivery.deliver(account, BigDecimal.ONE).credited().signum());
        account.throwBefore = false;
        assertEquals(BigDecimal.ONE, PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
        assertEquals(new BigDecimal("101"), account.balance);
    }
    @Test void exceptionsAfterCreditAreReconciledWithoutRepeatingTheDeposit() {
        var account = new Account(); account.throwAfter = true;
        assertEquals(BigDecimal.ONE, PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
        assertEquals(new BigDecimal("101"), account.balance);
        assertEquals(1, account.attempts);
    }
    @Test void failureResponsesAfterCreditAreAlsoReconciled() {
        var account = new Account(); account.returnFailureAfter = true;
        assertEquals(BigDecimal.ONE, PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
    }
    @Test void aSuccessResponseWithACancelledCreditDoesNotLoseTheReward() {
        var account = new Account(); account.cancelCredit = true;
        assertEquals(0, PaymentDelivery.deliver(account, BigDecimal.ONE).credited().signum());
        assertEquals(new BigDecimal("100"), account.balance);
    }
    @Test void aPartialCreditReturnsOnlyTheAmountActuallyReceived() {
        var account = new Account(); account.maximumCredit = new BigDecimal("0.25");
        assertEquals(new BigDecimal("0.25"), PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
        assertEquals(new BigDecimal("100.25"), account.balance);
    }
    @Test void uncertainCreditWaitsForABalanceReadWithoutDepositingAgain() {
        var account = new Account(); account.unavailableAfter = true;
        var result = PaymentDelivery.deliver(account, BigDecimal.ONE);
        assertFalse(result.complete());
        assertFalse(PaymentDelivery.reconcile(account, BigDecimal.ONE, result.before()).complete());
        account.unavailableBalance = false;
        assertEquals(BigDecimal.ONE, PaymentDelivery.reconcile(account, BigDecimal.ONE, result.before()).credited());
        assertEquals(1, account.attempts);
    }
    @Test void unavailableBalanceDefersTheDepositUntilItCanBeVerified() {
        var account = new Account(); account.unavailableBalance = true;
        assertEquals(0, PaymentDelivery.deliver(account, BigDecimal.ONE).credited().signum());
        assertEquals(0, account.attempts);
        account.unavailableBalance = false;
        assertEquals(BigDecimal.ONE, PaymentDelivery.deliver(account, BigDecimal.ONE).credited());
        account.throwBefore = true;
        assertEquals(0, PaymentDelivery.deliver(account, BigDecimal.ONE).credited().signum());
    }
}
