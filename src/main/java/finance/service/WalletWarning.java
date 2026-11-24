package finance.service;

import java.math.BigDecimal;

public sealed interface WalletWarning {
    record BudgetExceeded(String category, BigDecimal amount) implements WalletWarning {
    }

    record BudgetThresholdReached(String category, BigDecimal spent, BigDecimal limit) implements WalletWarning {
    }

    record ExpensesExceedIncome(BigDecimal amount) implements WalletWarning {
    }

    record BalanceReachedZero() implements WalletWarning {
    }
}
