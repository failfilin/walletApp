package finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Wallet {
    private static final String DEFAULT_TRANSFER_CATEGORY = "Переводы";

    private final String ownerLogin;
    private final List<Entry> operations = new ArrayList<>();
    private Map<String, Category> categories = new LinkedHashMap<>();
    private Category transferCategory;
    private BigDecimal balance = BigDecimal.ZERO.setScale(2);

    public Wallet(String ownerLogin) {
        this.ownerLogin = Objects.requireNonNull(ownerLogin);
    }

    public String ownerLogin() {
        return ownerLogin;
    }

    public String transferCategoryName() {
        return transferCategory == null ? DEFAULT_TRANSFER_CATEGORY : transferCategory.name();
    }

    public Optional<String> assignedTransferCategoryName() {
        return Optional.ofNullable(transferCategory).map(Category::name);
    }

    public void add(Operation operation) {
        Category category = categories.computeIfAbsent(operation.category(), Category::new);
        if (transferCategory == null && operation.transfer().isPresent()) {
            transferCategory = category;
        }
        bindDefaultTransferCategory(category);
        record(operation, category);
    }

    public void restoreOperations(List<Operation> history, Optional<String> savedTransferCategory) {
        Category legacyBudgetCategory = null;
        if (savedTransferCategory.isPresent()) {
            transferCategory = requireCategory(savedTransferCategory.get());
        } else {
            List<Category> historicalTransferCategories = history.stream()
                    .filter(operation -> operation.transfer().isPresent())
                    .map(operation -> requireCategory(operation.category())).toList();
            if (!historicalTransferCategories.isEmpty()) {
                List<Category> budgeted = historicalTransferCategories.stream().distinct()
                        .filter(category -> category.budget().isPresent()).toList();
                if (budgeted.size() == 1) {
                    legacyBudgetCategory = budgeted.get(0);
                    transferCategory = legacyBudgetCategory;
                } else {
                    transferCategory = historicalTransferCategories.get(historicalTransferCategories.size() - 1);
                }
            }
        }
        for (Operation operation : history) {
            if (legacyBudgetCategory != null && operation.transfer().isPresent()) {
                record(operation, legacyBudgetCategory);
            } else {
                add(operation);
            }
        }
    }

    private void record(Operation operation, Category category) {
        operations.add(new Entry(operation.type(), operation.amount(), category, operation.date(), operation.transfer()));
        if (operation.type() == Operation.Type.EXPENSE) {
            category.recordExpense(operation.amount());
            balance = balance.subtract(operation.amount());
        } else {
            balance = balance.add(operation.amount());
        }
    }

    public void addCategory(String name) {
        Category category = new Category(name);
        if (categories.putIfAbsent(category.name(), category) != null) {
            throw new IllegalArgumentException("Категория «" + category.name() + "» уже существует.");
        }
        bindDefaultTransferCategory(category);
    }

    public void setBudget(String name, BigDecimal limit) {
        requireCategory(name).setBudget(limit);
    }

    public void renameCategory(String name, String newName) {
        Category category = requireCategory(name);
        String normalizedName = Category.normalizeName(newName);
        if (categories.containsKey(normalizedName)) {
            throw new IllegalArgumentException("Категория «" + normalizedName + "» уже существует.");
        }

        Map<String, Category> renamed = new LinkedHashMap<>();
        for (Category existing : categories.values()) {
            renamed.put(existing == category ? normalizedName : existing.name(), existing);
        }
        category.rename(normalizedName);
        categories = renamed;
        bindDefaultTransferCategory(category);
    }

    public void changeBudget(String name, BigDecimal limit) {
        requireCategory(name).changeBudget(limit);
    }

    public void removeBudget(String name) {
        requireCategory(name).removeBudget();
    }

    public CategoryBudget categoryBudget(String name) {
        return requireCategory(name).budgetSummary();
    }

    public List<CategoryBudget> categoryBudgets() {
        return categories.values().stream().map(Category::budgetSummary).toList();
    }

    public Map<String, Optional<BigDecimal>> categoryLimits() {
        Map<String, Optional<BigDecimal>> limits = new LinkedHashMap<>();
        categories.forEach((name, category) -> limits.put(name, category.budget()));
        return Collections.unmodifiableMap(limits);
    }

    public BigDecimal balance() {
        return balance;
    }

    public List<Operation> history() {
        return operations.stream().map(Entry::snapshot).toList();
    }

    private void bindDefaultTransferCategory(Category category) {
        if (transferCategory == null && DEFAULT_TRANSFER_CATEGORY.equals(category.name())) {
            transferCategory = category;
        }
    }

    private Category requireCategory(String name) {
        String normalizedName = Category.normalizeName(name);
        Category category = categories.get(normalizedName);
        if (category == null) {
            throw new IllegalArgumentException("Категория «" + normalizedName + "» не найдена.");
        }
        return category;
    }

    private record Entry(Operation.Type type, BigDecimal amount, Category category, LocalDate date,
                         Optional<Operation.Transfer> transfer) {
        Operation snapshot() {
            return new Operation(type, amount, category.name(), date, transfer);
        }
    }
}
