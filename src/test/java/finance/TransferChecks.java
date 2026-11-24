package finance;

import finance.infrastructure.FileWalletRepository;
import finance.model.Operation;
import finance.model.Wallet;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static finance.CliChecks.inDirectory;
import static finance.CliChecks.launch;
import static finance.CliChecks.launchWithStatus;
import static finance.CliChecks.walletFile;
import static finance.ProjectChecks.contains;
import static finance.ProjectChecks.equal;
import static finance.ProjectChecks.rejects;
import static java.nio.charset.StandardCharsets.UTF_8;

final class TransferChecks {
    static void run(ProjectChecks checks) {
        checks.run("Перевод получателю вне сеанса и связь записей после перезапуска", () -> inDirectory(directory -> {
            setup(directory);
            String output = launch(directory, "login anna", "пароль", "category Переводы", "budget 300 Переводы",
                    "transfer 250 boris", "balance", "report Переводы", "exit");
            contains(output, "Перевод пользователю «boris» записан.");
            contains(output, "Баланс — 750.00");
            contains(output, "Общие расходы — 250.00");
            contains(output, "не менее 80% лимита — 250.00 из 300.00");
            FileWalletRepository repository = repository(directory);
            var expense = repository.load("anna").orElseThrow().history().get(1);
            var income = repository.load("boris").orElseThrow().history().get(0);
            equal(Operation.Type.EXPENSE, expense.type());
            equal(Operation.Type.INCOME, income.type());
            equal(new BigDecimal("250.00"), expense.amount());
            equal(expense.amount(), income.amount());
            equal(expense.date(), income.date());
            equal("boris", expense.transfer().orElseThrow().counterparty());
            equal("anna", income.transfer().orElseThrow().counterparty());
            UUID id = expense.transfer().orElseThrow().id();
            equal(id, income.transfer().orElseThrow().id());
            output = launch(directory, "login boris", "пароль", "balance", "history", "report Переводы", "exit");
            contains(output, "Баланс — 250.00");
            contains(output, "anna / " + id);
            contains(output, "Общий доход — 250.00");
            equal(false, Files.exists(journal(directory)));
        }));
        checks.run("Ошибочные переводы не меняют кошельки", () -> inDirectory(directory -> {
            setup(directory);
            String output = launch(directory, "transfer 1 boris", "login anna", "пароль",
                    "transfer 1 unknown", "transfer 1 anna", "transfer 1000.01 boris", "transfer 0 boris",
                    "transfer -1 boris", "transfer 1.001 boris", "transfer 1 boris extra", "balance", "exit");
            contains(output, "Сначала войдите в учётную запись.");
            contains(output, "Получатель «unknown» не найден.");
            contains(output, "Перевод самому себе недоступен.");
            contains(output, "Недостаточно средств для перевода.");
            contains(output, "Сумма должна быть больше нуля.");
            contains(output, "Укажите не более двух знаков после разделителя.");
            contains(output, "Формат команды — transfer <сумма> <логин получателя>.");
            contains(output, "Баланс — 1000.00");
            equal(false, Files.exists(walletFile(directory, "boris")));
        }));
        checks.run("Перевод до нуля и восстановление связи через CSV", () -> inDirectory(directory -> {
            setup(directory);
            String output = launch(directory, "login anna", "пароль", "transfer 1000 boris",
                    "export-csv Перевод.csv", "income 1 Бонус", "import-csv Перевод.csv", "yes", "balance");
            contains(output, "Баланс кошелька равен 0.00.");
            contains(output, "Баланс — 0.00");
            contains(output, "Кошелёк восстановлен из CSV-снимка.");
            contains(Files.readString(directory.resolve("Перевод.csv")), "wallet-snapshot,3");
            FileWalletRepository repository = repository(directory);
            Wallet sender = repository.load("anna").orElseThrow();
            Wallet recipient = repository.load("boris").orElseThrow();
            equal(2, sender.history().size());
            equal(recipient.history().get(0).transfer().orElseThrow().id(),
                    sender.history().get(1).transfer().orElseThrow().id());
        }));
        checks.run("Импорт отклоняет удаление, изменение и дублирование переводов", () -> inDirectory(directory -> {
            String initial = launch(directory, "register anna", "пароль", "register boris", "пароль",
                    "login anna", "пароль", "income 100 Зарплата", "export-csv old.csv",
                    "transfer 40 boris", "import-csv old.csv", "yes", "balance", "export-csv current.csv", "exit");
            contains(initial, "CSV-снимок должен сохранять все существующие переводы кошелька.");
            contains(initial, "Баланс — 60.00");
            String current = Files.readString(directory.resolve("current.csv"));
            Files.writeString(directory.resolve("changed.csv"), current.replace(",40.00,", ",41.00,"));
            String transferRow = current.lines().filter(row -> row.startsWith("transfer,")).findFirst().orElseThrow();
            Files.writeString(directory.resolve("duplicate.csv"), current.replace("end\r\n", transferRow + "\r\nend\r\n"));

            for (String snapshot : List.of("changed.csv", "duplicate.csv")) {
                String output = launch(directory, "login anna", "пароль", "import-csv " + snapshot, "yes", "balance", "exit");
                contains(output, "CSV-снимок должен сохранять все существующие переводы кошелька.");
                contains(output, "Баланс — 60.00");
            }

            FileWalletRepository repository = repository(directory);
            Wallet sender = repository.load("anna").orElseThrow();
            Wallet recipient = repository.load("boris").orElseThrow();
            equal(new BigDecimal("60.00"), sender.balance());
            equal(new BigDecimal("40.00"), recipient.balance());
            equal(2, sender.history().size());
            equal(1, recipient.history().size());
            UUID id = sender.history().get(1).transfer().orElseThrow().id();
            equal(id, recipient.history().get(0).transfer().orElseThrow().id());
            String output = launch(directory, "login anna", "пароль", "history",
                    "login boris", "пароль", "history", "exit");
            contains(output, "boris / " + id);
            contains(output, "anna / " + id);
        }));
        checks.run("Бюджет переводов действует после переименования, импорта и перезапуска", () -> inDirectory(directory -> {
            setup(directory);
            String output = launch(directory, "login anna", "пароль", "transfer 10 boris", "budget 15 Переводы",
                    "category-rename Переводы, Банк", "category Переводы", "transfer 10 boris",
                    "export-csv bank.csv", "expense 4 Банк", "balance", "import-csv bank.csv", "yes",
                    "balance", "categories", "report", "exit");
            contains(output, "Лимит категории «Банк» превышен на 5.00.");
            contains(output, "Баланс — 976.00");
            contains(output, "Кошелёк восстановлен из CSV-снимка.");
            contains(output, "Баланс — 980.00");
            contains(output, "Общие расходы — 20.00");
            contains(output.replaceAll(" +\\| +", " | "), "Банк | 20.00 | 15.00 | -5.00");

            FileWalletRepository repository = repository(directory);
            Wallet sender = repository.load("anna").orElseThrow();
            equal(List.of("Банк", "Банк"), sender.history().stream()
                    .filter(operation -> operation.transfer().isPresent()).map(Operation::category).toList());
            equal(new BigDecimal("-5.00"), sender.categoryBudget("Банк").remaining().orElseThrow());
            equal(new BigDecimal("0.00"), sender.categoryBudget("Переводы").spent());

            output = launch(directory, "login anna", "пароль", "transfer 1 boris", "balance", "categories", "exit");
            contains(output, "Лимит категории «Банк» превышен на 6.00.");
            contains(output, "Баланс — 979.00");
            contains(output.replaceAll(" +\\| +", " | "), "Банк | 21.00 | 15.00 | -6.00");
            equal("Банк", repository.load("anna").orElseThrow().history().get(3).category());
            equal("Переводы", repository.load("boris").orElseThrow().history().get(2).category());
        }));
        for (String scenario : List.of("в одном сеансе", "после перезапуска", "после импорта CSV")) {
            checks.run("Переименование категории до первого перевода " + scenario, () -> inDirectory(directory -> {
                launch(directory, "register anna", "пароль", "register boris", "пароль", "exit");
                List<String> commands = new ArrayList<>(List.of("login anna", "пароль", "income 100 Зарплата",
                        "category Переводы", "budget 15 Переводы", "category-rename Переводы, Банк"));
                if (scenario.equals("после перезапуска")) {
                    commands.add("exit");
                    launch(directory, commands.toArray(String[]::new));
                    commands = new ArrayList<>(List.of("login anna", "пароль"));
                } else if (scenario.equals("после импорта CSV")) {
                    commands.addAll(List.of("export-csv bank.csv", "category-rename Банк, Другое",
                            "import-csv bank.csv", "yes"));
                }
                commands.addAll(List.of("transfer 20 boris", "categories", "balance",
                        "login boris", "пароль", "balance", "exit"));
                String output = launch(directory, commands.toArray(String[]::new));
                contains(output, "Лимит категории «Банк» превышен на 5.00.");
                contains(output.replaceAll(" +\\| +", " | "), "Банк | 20.00 | 15.00 | -5.00");
                contains(output, "Баланс — 80.00");
                contains(output, "Баланс — 20.00");
                Wallet sender = repository(directory).load("anna").orElseThrow();
                equal(List.of("Зарплата", "Банк"), List.copyOf(sender.categoryLimits().keySet()));
            }));
        }
        checks.run("Старые файл и CSV учитывают разнесённые переводы в установленном бюджете", () -> inDirectory(directory -> {
            setup(directory);
            launch(directory, "login anna", "пароль", "transfer 10 boris", "budget 15 Переводы",
                    "category-rename Переводы, Банк", "category Переводы", "transfer 10 boris", "export-csv legacy.csv", "exit");
            FileWalletRepository repository = repository(directory);
            List<Operation> originalExpenses = repository.load("anna").orElseThrow().history().subList(1, 3);
            List<Operation> originalIncomes = repository.load("boris").orElseThrow().history();
            UUID secondId = originalExpenses.get(1).transfer().orElseThrow().id();
            Path snapshot = directory.resolve("legacy.csv");
            String csv = Files.readString(snapshot).replace("wallet-snapshot,3", "wallet-snapshot,2")
                    .replace("transfer-category,Банк\r\n", "").replace(",Банк," + secondId, ",Переводы," + secondId);
            contains(csv, ",Переводы," + secondId);
            Files.writeString(snapshot, csv);

            Path oldWallet = walletFile(directory, "anna");
            String bank = Base64.getEncoder().encodeToString("Банк".getBytes(UTF_8));
            String transfers = Base64.getEncoder().encodeToString("Переводы".getBytes(UTF_8));
            String oldRows = Files.readString(oldWallet).replace("wallet-v3", "wallet-v2")
                    .replace("transfer-category\t" + bank + System.lineSeparator(), "")
                    .replace("\t" + bank + "\t" + secondId, "\t" + transfers + "\t" + secondId);
            contains(oldRows, "\t" + transfers + "\t" + secondId);
            Files.writeString(oldWallet, oldRows);
            Wallet restored = repository.load("anna").orElseThrow();
            equal(new BigDecimal("20.00"), restored.categoryBudget("Банк").spent());
            equal(originalExpenses, restored.history().subList(1, 3));

            String output = launch(directory, "login anna", "пароль", "expense 1 Банк",
                    "import-csv legacy.csv", "yes", "categories", "exit");
            contains(output, "Кошелёк восстановлен из CSV-снимка.");
            contains(output.replaceAll(" +\\| +", " | "), "Банк | 20.00 | 15.00 | -5.00");
            output = launch(directory, "login anna", "пароль", "transfer 1 boris", "categories", "exit");
            contains(output, "Лимит категории «Банк» превышен на 6.00.");
            contains(output.replaceAll(" +\\| +", " | "), "Банк | 21.00 | 15.00 | -6.00");
            equal(originalExpenses, repository.load("anna").orElseThrow().history().subList(1, 3));
            equal(originalIncomes, repository.load("boris").orElseThrow().history().subList(0, 2));
        }));
        checks.run("Сбой между записями переводов восстанавливается без дублей", () -> inDirectory(directory -> {
            setup(directory);
            FileWalletRepository repository = repository(directory);
            Wallet sender = repository.load("anna").orElseThrow();
            Wallet recipient = new Wallet("boris");
            UUID id = UUID.randomUUID();
            LocalDate date = LocalDate.of(2024, 2, 29);
            sender.add(new Operation(Operation.Type.EXPENSE, new BigDecimal("250"), "Переводы", date,
                    Optional.of(new Operation.Transfer(id, "boris"))));
            recipient.add(new Operation(Operation.Type.INCOME, new BigDecimal("250"), "Переводы", date,
                    Optional.of(new Operation.Transfer(id, "anna"))));
            Path target = walletFile(directory, "boris");
            Files.createDirectory(target);
            rejects(IOException.class, () -> repository.saveTogether(List.of(sender, recipient)));
            equal(new BigDecimal("750.00"), repository.load("anna").orElseThrow().balance());
            equal(true, Files.isRegularFile(journal(directory)));
            contains(launchWithStatus(directory, 1, "exit"), "Журнал сохранён для повтора.");
            equal(true, Files.isRegularFile(journal(directory)));

            Files.delete(target);
            String output = launch(directory, "login anna", "пароль", "balance",
                    "login boris", "пароль", "balance", "history", "exit");
            contains(output, "Баланс — 750.00");
            contains(output, "Баланс — 250.00");
            contains(output, "anna / " + id);
            equal(false, Files.exists(journal(directory)));
            launch(directory, "exit");
            equal(2, repository.load("anna").orElseThrow().history().size());
            equal(1, repository.load("boris").orElseThrow().history().size());
        }));
        checks.run("Недоступный кошелёк получателя оставляет отправителя без списания", () -> inDirectory(directory -> {
            setup(directory);
            Path target = walletFile(directory, "boris");
            Files.writeString(target, "wallet-v1\n");
            String output = launch(directory, "login anna", "пароль", "transfer 250 boris", "balance", "exit");
            contains(output, "Не удалось загрузить кошелёк получателя.");
            contains(output, "Баланс — 1000.00");
            equal("wallet-v1\n", Files.readString(target));
        }));
        checks.run("Кошелёк предыдущей версии загружается и может отправлять переводы", () -> inDirectory(directory -> {
            setup(directory);
            Path wallet = walletFile(directory, "anna");
            Files.writeString(wallet, Files.readString(wallet).replaceFirst("wallet-v3", "wallet-v1"));
            contains(launch(directory, "login anna", "пароль", "transfer 250 boris", "exit"),
                    "Перевод пользователю «boris» записан.");
            contains(Files.readString(wallet), "wallet-v3");
        }));
    }

    private static void setup(Path directory) throws Exception {
        launch(directory, "register anna", "пароль", "register boris", "пароль",
                "login anna", "пароль", "income 1000 Зарплата", "exit");
    }

    private static FileWalletRepository repository(Path directory) {
        return new FileWalletRepository(directory.resolve(".finance-data/wallets"));
    }

    private static Path journal(Path directory) {
        return directory.resolve(".finance-data/wallets/pending-transfer.txt");
    }

    private TransferChecks() {
    }
}
