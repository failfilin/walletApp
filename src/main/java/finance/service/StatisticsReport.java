package finance.service;

import finance.model.CategoryBudget;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public record StatisticsReport(BigDecimal income, BigDecimal expenses, List<CategoryTotals> categories,
                               List<String> unknownCategories, List<CategoryBudget> budgets,
                               Optional<ReportPeriod> period) {
    public StatisticsReport {
        categories = List.copyOf(categories);
        unknownCategories = List.copyOf(unknownCategories);
        budgets = List.copyOf(budgets);
    }

    public BigDecimal balance() {
        return income.subtract(expenses);
    }

    public boolean hasOperations() {
        return income.signum() != 0 || expenses.signum() != 0;
    }

    public record CategoryTotals(String category, BigDecimal income, BigDecimal expenses) {
        public boolean hasOperations() {
            return income.signum() != 0 || expenses().signum() != 0;
        }
    }
}
