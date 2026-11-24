package finance.model;

import java.math.BigDecimal;
import java.util.Optional;

final class Category {
    private String name;
    private BigDecimal budget;
    private BigDecimal spent = BigDecimal.ZERO.setScale(2);

    Category(String name) {
        this.name = normalizeName(name);
    }

    String name() {
        return name;
    }

    void rename(String newName) {
        name = normalizeName(newName);
    }

    Optional<BigDecimal> budget() {
        return Optional.ofNullable(budget);
    }

    void recordExpense(BigDecimal amount) {
        spent = spent.add(amount);
    }

    CategoryBudget budgetSummary() {
        return new CategoryBudget(name, spent, budget());
    }

    void setBudget(BigDecimal limit) {
        if (budget != null) {
            throw new IllegalArgumentException("Для категории «" + name + "» уже установлен лимит.");
        }
        budget = normalizeLimit(limit);
    }

    void changeBudget(BigDecimal limit) {
        requireBudget();
        budget = normalizeLimit(limit);
    }

    void removeBudget() {
        requireBudget();
        budget = null;
    }

    private void requireBudget() {
        if (budget == null) {
            throw new IllegalArgumentException("Для категории «" + name + "» ещё нет лимита.");
        }
    }

    private static BigDecimal normalizeLimit(BigDecimal limit) {
        if (limit.signum() < 0) {
            throw new IllegalArgumentException("Лимит не может быть отрицательным.");
        }
        return Money.normalize(limit);
    }

    static String normalizeName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Укажите название категории.");
        }
        return name.strip();
    }
}
