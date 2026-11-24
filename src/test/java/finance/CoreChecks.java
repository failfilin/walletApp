package finance;

import finance.infrastructure.InMemoryAccountRepository;
import finance.model.Operation;
import finance.model.Wallet;
import finance.service.AuthenticationException;
import finance.service.AuthenticationService;
import finance.service.ReportPeriod;
import finance.service.StatisticsService;
import finance.service.WalletRepository;
import finance.service.WalletService;
import finance.service.WalletWarning;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static finance.ProjectChecks.equal;
import static finance.ProjectChecks.rejects;
import static finance.model.Operation.Type.EXPENSE;
import static finance.model.Operation.Type.INCOME;

final class CoreChecks {
    private static final LocalDate DAY = LocalDate.of(2024, 2, 29);
    private static final String OWNER = "anna";

    static void run(ProjectChecks checks) {
        checks.run("Точные суммы и баланс", () -> {
            Wallet wallet = new Wallet(OWNER);
            wallet.add(operation(INCOME, "0.30", "Бонус", DAY));
            wallet.add(operation(EXPENSE, "0.20", "Еда", DAY));
            equal(money("0.10"), wallet.balance());
        });
        checks.run("Неверные суммы и пустая категория", () -> {
            for (String amount : List.of("0", "-1", "1.001")) {
                rejects(IllegalArgumentException.class, () -> operation(INCOME, amount, "Бонус", DAY));
            }
            rejects(IllegalArgumentException.class, () -> operation(EXPENSE, "1", " ", DAY));
        });
        checks.run("Бюджет учитывает только расходы", () -> {
            Wallet wallet = new Wallet(OWNER);
            wallet.add(operation(INCOME, "1000", "Еда", DAY));
            wallet.add(operation(EXPENSE, "300", "Еда", DAY));
            wallet.setBudget("Еда", money("250"));
            equal(Optional.of(money("-50.00")), wallet.categoryBudget("Еда").remaining());
        });
        checks.run("Переименование сохраняет историю, бюджет и порядок", () -> {
            Wallet wallet = new Wallet(OWNER);
            wallet.addCategory("Еда");
            wallet.addCategory("Такси");
            wallet.add(operation(EXPENSE, "80", "Еда", DAY));
            wallet.setBudget("Еда", money("100"));
            rejects(IllegalArgumentException.class, () -> wallet.renameCategory("Еда", "Такси"));
            wallet.renameCategory("Еда", "Продукты");
            equal(List.of("Продукты", "Такси"), List.copyOf(wallet.categoryLimits().keySet()));
            equal(List.of(operation(EXPENSE, "80", "Продукты", DAY)), wallet.history());
            equal(Optional.of(money("20.00")), wallet.categoryBudget("Продукты").remaining());
        });
        checks.run("Валидация редактирования и снятие бюджета", () -> {
            Wallet wallet = new Wallet(OWNER);
            wallet.add(operation(EXPENSE, "10", "Еда", DAY));
            wallet.setBudget("Еда", money("20"));
            rejects(IllegalArgumentException.class, () -> wallet.changeBudget("Еда", money("-1")));
            equal(Optional.of(money("20.00")), wallet.categoryBudget("Еда").limit());
            wallet.changeBudget("Еда", money("0"));
            equal(Optional.of(money("-10.00")), wallet.categoryBudget("Еда").remaining());
            wallet.removeBudget("Еда");
            equal(Optional.empty(), wallet.categoryBudget("Еда").limit());
            equal(1, wallet.history().size());
        });
        checks.run("Авторизация, смена пользователя и выход", () -> {
            AuthenticationService auth = new AuthenticationService(new InMemoryAccountRepository(), walletService());
            char[] password = "пароль".toCharArray();
            auth.register(OWNER, password);
            auth.register("boris", password);
            rejects(AuthenticationException.class, () -> auth.register(OWNER, password));
            auth.login(OWNER, password);
            rejects(AuthenticationException.class, () -> auth.login("boris", "ошибка".toCharArray()));
            equal(OWNER, auth.requireLogin());
            auth.login("boris", password);
            equal("boris", auth.requireLogin());
            auth.logout();
            rejects(AuthenticationException.class, auth::requireLogin);
        });
        checks.run("Разные соли при одинаковом пароле", () -> {
            InMemoryAccountRepository accounts = new InMemoryAccountRepository();
            AuthenticationService auth = new AuthenticationService(accounts, walletService());
            char[] password = "один пароль".toCharArray();
            auth.register("a", password);
            auth.register("b", password);
            equal(false, accounts.findByLogin("a").orElseThrow().salt()
                    .equals(accounts.findByLogin("b").orElseThrow().salt()));
        });
        checks.run("Кошельки изолированы", () -> {
            WalletService wallets = walletService();
            wallets.load(OWNER);
            wallets.load("boris");
            wallets.record(OWNER, INCOME, money("100"), "Бонус");
            equal(money("100.00"), wallets.balance(OWNER));
            equal(money("0.00"), wallets.balance("boris"));
            equal(List.of(), wallets.history("boris"));
        });
        checks.run("Предупреждение при 80% и приоритет превышения", () -> {
            WalletService wallets = walletService();
            wallets.load(OWNER);
            wallets.record(OWNER, INCOME, money("5000"), "Зарплата");
            wallets.addCategory(OWNER, "Еда");
            wallets.setBudget(OWNER, "Еда", money("1000"));
            equal(List.of(), wallets.record(OWNER, EXPENSE, money("799.99"), "Еда"));
            equal(List.of(new WalletWarning.BudgetThresholdReached("Еда", money("800.00"), money("1000.00"))),
                    wallets.record(OWNER, EXPENSE, money("0.01"), "Еда"));
            equal(List.of(), wallets.record(OWNER, EXPENSE, money("5"), "Еда"));
            equal(List.of(new WalletWarning.BudgetExceeded("Еда", money("5.00"))),
                    wallets.record(OWNER, EXPENSE, money("200"), "Еда"));
        });
        checks.run("Оповещения о дефиците и переходе в ноль", () -> {
            WalletService wallets = walletService();
            wallets.load(OWNER);
            equal(List.of(new WalletWarning.ExpensesExceedIncome(money("10.00"))),
                    wallets.record(OWNER, EXPENSE, money("10"), "Еда"));
            equal(List.of(new WalletWarning.BalanceReachedZero()),
                    wallets.record(OWNER, INCOME, money("10"), "Бонус"));
        });
        checks.run("Выборка категорий и обе границы периода", () -> {
            WalletService wallets = walletService();
            wallets.load(OWNER);
            Wallet sample = new Wallet(OWNER);
            sample.add(operation(EXPENSE, "7", "Еда", DAY.minusDays(1)));
            sample.add(operation(INCOME, "20", "Еда", DAY));
            sample.add(operation(EXPENSE, "3", "Еда", DAY.plusDays(1)));
            sample.add(operation(EXPENSE, "10", "Такси", DAY));
            sample.add(operation(EXPENSE, "8", "Еда", DAY.plusDays(2)));
            wallets.replaceWallet(OWNER, sample);
            var report = new StatisticsService(wallets).summarize(OWNER, List.of("Еда", "Еда", "Неизвестная"),
                    new ReportPeriod(DAY, DAY.plusDays(1)));
            equal(money("20.00"), report.income());
            equal(money("3.00"), report.expenses());
            equal(List.of("Неизвестная"), report.unknownCategories());
            equal(1, report.categories().size());
        });
        checks.run("Перевёрнутый период отклоняется", () ->
                rejects(IllegalArgumentException.class, () -> new ReportPeriod(DAY, DAY.minusDays(1))));
    }

    private static Operation operation(Operation.Type type, String amount, String category, LocalDate date) {
        return new Operation(type, money(amount), category, date);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private static WalletService walletService() {
        return new WalletService(Clock.fixed(Instant.parse("2024-02-29T12:00:00Z"), ZoneOffset.UTC),
                new WalletRepository() {
                    @Override
                    public Optional<Wallet> load(String ownerLogin) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(Wallet wallet) {
                    }

                    @Override
                    public void saveTogether(List<Wallet> wallets) {
                    }
                }, new InMemoryAccountRepository());
    }

    private CoreChecks() {
    }
}
