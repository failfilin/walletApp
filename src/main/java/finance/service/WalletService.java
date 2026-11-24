package finance.service;

import finance.model.CategoryBudget;
import finance.model.Operation;
import finance.model.Wallet;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class WalletService {
    private static final BigDecimal BUDGET_WARNING_FRACTION = new BigDecimal("0.80");

    private final Map<String, Wallet> wallets = new HashMap<>();
    private final Set<String> changedWallets = new LinkedHashSet<>();
    private final Set<String> transferWallets = new LinkedHashSet<>();
    private final Clock clock;
    private final WalletRepository repository;
    private final AccountRepository accounts;

    public WalletService(Clock clock, WalletRepository repository, AccountRepository accounts) {
        this.clock = clock;
        this.repository = repository;
        this.accounts = accounts;
    }

    public void load(String ownerLogin) throws IOException {
        if (!wallets.containsKey(ownerLogin)) {
            Wallet wallet = repository.load(ownerLogin).orElseGet(() -> new Wallet(ownerLogin));
            wallets.put(ownerLogin, wallet);
        }
    }

    public List<String> saveChanged() {
        List<String> unsaved = new ArrayList<>();
        if (!transferWallets.isEmpty()) {
            try {
                repository.saveTogether(transferWallets.stream().map(this::walletFor).toList());
                changedWallets.removeAll(transferWallets);
                transferWallets.clear();
            } catch (IOException exception) {
                unsaved.addAll(transferWallets);
            }
        }

        Iterator<String> iterator = changedWallets.iterator();
        while (iterator.hasNext()) {
            String ownerLogin = iterator.next();
            if (transferWallets.contains(ownerLogin)) {
                continue;
            }
            try {
                repository.save(walletFor(ownerLogin));
                iterator.remove();
            } catch (IOException exception) {
                unsaved.add(ownerLogin);
            }
        }
        return List.copyOf(unsaved);
    }

    public List<WalletWarning> record(String ownerLogin, Operation.Type type, BigDecimal amount, String category) {
        Operation operation = new Operation(type, amount, category, LocalDate.now(clock));
        Wallet wallet = walletFor(ownerLogin);
        wallet.add(operation);
        changedWallets.add(ownerLogin);
        return operationWarnings(wallet, operation);
    }

    public List<WalletWarning> transfer(String senderLogin, String receiverLogin, BigDecimal amount) throws IOException {
        if (senderLogin.equals(receiverLogin)) {
            throw new IllegalArgumentException("Перевод самому себе недоступен.");
        }
        if (accounts.findByLogin(receiverLogin).isEmpty()) {
            throw new IllegalArgumentException("Получатель «" + receiverLogin + "» не найден.");
        }

        LocalDate date = LocalDate.now(clock);
        UUID transferId = UUID.randomUUID();
        Wallet sender = walletFor(senderLogin);
        Operation expense = new Operation(Operation.Type.EXPENSE, amount, sender.transferCategoryName(), date,
                Optional.of(new Operation.Transfer(transferId, receiverLogin)));
        if (sender.balance().compareTo(expense.amount()) < 0) {
            throw new IllegalArgumentException("Недостаточно средств для перевода.");
        }

        load(receiverLogin);
        Wallet receiver = walletFor(receiverLogin);
        Operation income = new Operation(Operation.Type.INCOME, amount, receiver.transferCategoryName(), date,
                Optional.of(new Operation.Transfer(transferId, senderLogin)));
        sender.add(expense);
        receiver.add(income);
        changedWallets.add(senderLogin);
        changedWallets.add(receiverLogin);
        transferWallets.add(senderLogin);
        transferWallets.add(receiverLogin);
        return operationWarnings(sender, expense);
    }

    private List<WalletWarning> operationWarnings(Wallet wallet, Operation operation) {
        List<WalletWarning> warnings = new ArrayList<>();
        if (operation.type() == Operation.Type.EXPENSE) {
            CategoryBudget budget = wallet.categoryBudget(operation.category());
            BigDecimal previousSpending = budget.spent().subtract(operation.amount());
            budgetWarning(budget, hasReachedBudgetThreshold(previousSpending, budget.limit())).ifPresent(warnings::add);
        }
        BigDecimal updatedBalance = wallet.balance();
        if (updatedBalance.signum() < 0) {
            warnings.add(new WalletWarning.ExpensesExceedIncome(updatedBalance.negate()));
        } else if (updatedBalance.signum() == 0) {
            warnings.add(new WalletWarning.BalanceReachedZero());
        }
        return List.copyOf(warnings);
    }

    public BigDecimal balance(String ownerLogin) {
        return walletFor(ownerLogin).balance();
    }

    public List<Operation> history(String ownerLogin) {
        return walletFor(ownerLogin).history();
    }

    public void replaceWallet(String ownerLogin, Wallet replacement) {
        Wallet current = walletFor(ownerLogin);
        if (!ownerLogin.equals(replacement.ownerLogin())) {
            throw new IllegalArgumentException("Кошелёк принадлежит другой учётной записи.");
        }
        if (!transferEntries(current).equals(transferEntries(replacement))) {
            throw new IllegalArgumentException("CSV-снимок должен сохранять все существующие переводы кошелька. "
                    + "Добавление, удаление и изменение записей переводов через импорт запрещено.");
        }
        wallets.put(ownerLogin, replacement);
        changedWallets.add(ownerLogin);
    }

    private static Map<TransferEntry, Integer> transferEntries(Wallet wallet) {
        Map<TransferEntry, Integer> entries = new HashMap<>();
        for (Operation operation : wallet.history()) {
            operation.transfer().ifPresent(transfer -> entries.merge(
                    new TransferEntry(transfer, operation.type(), operation.amount(), operation.date()), 1, Integer::sum));
        }
        return entries;
    }

    private record TransferEntry(Operation.Transfer transfer, Operation.Type type, BigDecimal amount, LocalDate date) {
    }

    public void addCategory(String ownerLogin, String category) {
        walletFor(ownerLogin).addCategory(category);
        changedWallets.add(ownerLogin);
    }

    public void renameCategory(String ownerLogin, String category, String newName) {
        walletFor(ownerLogin).renameCategory(category, newName);
        changedWallets.add(ownerLogin);
    }

    public Optional<WalletWarning> setBudget(String ownerLogin, String category, BigDecimal limit) {
        Wallet wallet = walletFor(ownerLogin);
        wallet.setBudget(category, limit);
        changedWallets.add(ownerLogin);
        return budgetWarning(wallet.categoryBudget(category), false);
    }

    public Optional<WalletWarning> changeBudget(String ownerLogin, String category, BigDecimal limit) {
        Wallet wallet = walletFor(ownerLogin);
        CategoryBudget previous = wallet.categoryBudget(category);
        wallet.changeBudget(category, limit);
        changedWallets.add(ownerLogin);
        return budgetWarning(wallet.categoryBudget(category),
                hasReachedBudgetThreshold(previous.spent(), previous.limit()));
    }

    public void removeBudget(String ownerLogin, String category) {
        walletFor(ownerLogin).removeBudget(category);
        changedWallets.add(ownerLogin);
    }

    public List<CategoryBudget> categoryBudgets(String ownerLogin) {
        return walletFor(ownerLogin).categoryBudgets();
    }

    public Map<String, Optional<BigDecimal>> categoryLimits(String ownerLogin) {
        return walletFor(ownerLogin).categoryLimits();
    }

    public Optional<String> assignedTransferCategoryName(String ownerLogin) {
        return walletFor(ownerLogin).assignedTransferCategoryName();
    }

    private Optional<WalletWarning> budgetWarning(CategoryBudget budget, boolean thresholdAlreadyReached) {
        if (budget.limit().isEmpty()) {
            return Optional.empty();
        }
        BigDecimal limit = budget.limit().get();
        BigDecimal excess = budget.spent().subtract(limit);
        if (excess.signum() > 0) {
            return Optional.of(new WalletWarning.BudgetExceeded(budget.category(), excess));
        }
        if (!thresholdAlreadyReached && hasReachedBudgetThreshold(budget.spent(), budget.limit())) {
            return Optional.of(new WalletWarning.BudgetThresholdReached(budget.category(), budget.spent(), limit));
        }
        return Optional.empty();
    }

    private boolean hasReachedBudgetThreshold(BigDecimal spent, Optional<BigDecimal> limit) {
        return limit.filter(amount -> amount.signum() > 0
                && spent.compareTo(amount.multiply(BUDGET_WARNING_FRACTION)) >= 0).isPresent();
    }

    private Wallet walletFor(String ownerLogin) {
        Wallet wallet = wallets.get(ownerLogin);
        if (wallet == null) {
            throw new IllegalStateException("Кошелёк пользователя ещё не загружен.");
        }
        return wallet;
    }
}
