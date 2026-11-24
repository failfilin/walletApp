package finance.service;

import finance.model.CategoryBudget;
import finance.model.Operation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StatisticsService {
    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2);
    private final WalletService wallets;

    public StatisticsService(WalletService wallets) {
        this.wallets = wallets;
    }

    public StatisticsReport summarize(String ownerLogin, List<String> requestedCategories) {
        return summarize(ownerLogin, requestedCategories, Optional.empty());
    }

    public StatisticsReport summarize(String ownerLogin, List<String> requestedCategories, ReportPeriod period) {
        return summarize(ownerLogin, requestedCategories, Optional.of(period));
    }

    private StatisticsReport summarize(String ownerLogin, List<String> requestedCategories,
                                       Optional<ReportPeriod> period) {
        Map<String, Optional<BigDecimal>> limits = wallets.categoryLimits(ownerLogin);
        Set<String> selected = selectCategories(requestedCategories, limits.keySet());
        Map<String, BigDecimal> incomes = new HashMap<>();
        Map<String, BigDecimal> spending = new HashMap<>();

        for (Operation operation : wallets.history(ownerLogin)) {
            if (!selected.contains(operation.category())
                    || (period.isPresent() && !period.get().includes(operation.date()))) {
                continue;
            }
            Map<String, BigDecimal> amounts = switch (operation.type()) {
                case INCOME -> incomes;
                case EXPENSE -> spending;
            };
            amounts.merge(operation.category(), operation.amount(), BigDecimal::add);
        }

        BigDecimal income = ZERO_AMOUNT;
        BigDecimal expenses = ZERO_AMOUNT;
        List<StatisticsReport.CategoryTotals> categories = new ArrayList<>();
        List<String> unknownCategories = new ArrayList<>();
        List<CategoryBudget> budgets = new ArrayList<>();
        for (String name : selected) {
            Optional<BigDecimal> limit = limits.get(name);
            if (limit == null) {
                unknownCategories.add(name);
                continue;
            }
            BigDecimal categoryIncome = incomes.getOrDefault(name, ZERO_AMOUNT);
            BigDecimal categoryExpenses = spending.getOrDefault(name, ZERO_AMOUNT);
            categories.add(new StatisticsReport.CategoryTotals(name, categoryIncome, categoryExpenses));
            if (period.isEmpty() && limit.isPresent()) {
                budgets.add(new CategoryBudget(name, categoryExpenses, limit));
            }
            income = income.add(categoryIncome);
            expenses = expenses.add(categoryExpenses);
        }
        return new StatisticsReport(income, expenses, categories, unknownCategories, budgets, period);
    }

    private Set<String> selectCategories(List<String> requestedCategories, Set<String> knownCategories) {
        Set<String> selected = new LinkedHashSet<>();
        for (String category : requestedCategories) {
            String name = category.strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Названия категорий в выборке должны быть непустыми.");
            }
            selected.add(name);
        }
        return selected.isEmpty() ? knownCategories : selected;
    }
}
