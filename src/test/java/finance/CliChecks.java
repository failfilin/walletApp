package finance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static finance.ProjectChecks.contains;
import static finance.ProjectChecks.equal;

final class CliChecks {
    static void run(ProjectChecks checks) {
        checks.run("CLI продолжает работу после ошибок", () -> inDirectory(directory -> {
            String output = launch(directory, "", "unknown", "help extra", "help", "balance", "exit");
            contains(output, "Неизвестная команда.");
            contains(output, "Команда help не принимает аргументы.");
            contains(output, "register <логин>");
            contains(output, "Сначала войдите в учётную запись.");
        }));
        checks.run("Пример задания и остатки бюджетов", () -> inDirectory(directory -> {
            String output = launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "expense 300 Еда", "expense 500 Еда", "expense 3000 Развлечения",
                    "expense 3000 Коммунальные услуги", "expense 1500 Такси",
                    "income 20000 Зарплата", "income 40000 Зарплата", "income 3000 Бонус",
                    "budget 4000 Еда", "budget 3000 Развлечения", "budget 2500 Коммунальные услуги",
                    "report", "report Еда, Такси, Еда", "exit");
            contains(output, "Общий доход — 63000.00\nОбщие расходы — 8300.00\nБаланс — 54700.00");
            contains(output, "Общие расходы — 2300.00");
            String compact = output.replaceAll(" +\\| +", " | ");
            contains(compact, "Еда | 800.00 | 4000.00 | 3200.00");
            contains(compact, "Развлечения | 3000.00 | 3000.00 | 0.00");
            contains(compact, "Коммунальные услуги | 3000.00 | 2500.00 | -500.00");
        }));
        checks.run("Два кошелька восстанавливаются после выхода и EOF", () -> inDirectory(directory -> {
            launch(directory, "register anna", "пароль", "register boris", "пароль",
                    "login anna", "пароль", "income 100 Зарплата", "logout",
                    "login boris", "пароль", "income 200 Бонус", "exit");
            String output = launch(directory, "login anna", "пароль", "balance", "expense 5 Такси",
                    "login boris", "пароль", "balance", "login anna", "пароль", "balance");
            contains(output, "Баланс — 100.00");
            contains(output, "Баланс — 200.00");
            contains(output, "Баланс — 95.00");
            contains(launch(directory, "login anna", "пароль", "balance", "exit"), "Баланс — 95.00");
        }));
        checks.run("Повреждённый кошелёк сохраняется и вход отклоняется", () -> inDirectory(directory -> {
            launch(directory, "register anna", "пароль", "login anna", "пароль", "income 100 Salary", "exit");
            Path wallet = walletFile(directory, "anna");
            Files.writeString(wallet, "wallet-v1\n");
            String output = launch(directory, "login anna", "пароль", "whoami", "exit");
            contains(output, "Файл недоступен или повреждён.");
            contains(output, "Сначала войдите в учётную запись.");
            equal("wallet-v1\n", Files.readString(wallet));
        }));
        checks.run("История пустого кошелька и таблица обычных операций", () -> inDirectory(directory -> {
            String output = launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "history", "income 10 Бонус", "expense 1 Еда", "history");
            contains(output, "История операций пуста.");
            contains(output, "Дата       | Тип    | Сумма | Категория\n");
            contains(output, " | Доход  | 10.00 | Бонус\n");
            contains(output, " | Расход |  1.00 | Еда\n");
        }));
        checks.run("Текстовый отчёт совпадает с терминальным", () -> inDirectory(directory -> {
            String output = launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "expense 20 Коммунальные услуги", "report Коммунальные услуги",
                    "report-file Коммунальные услуги", "Мой отчёт.txt", "exit");
            String file = Files.readString(directory.resolve("Мой отчёт.txt"), StandardCharsets.UTF_8);
            contains(output, file);
            contains(file, "Общие расходы — 20.00");
        }));
        checks.run("CSV проходит цикл экспорта, отмены, импорта и перезапуска", () -> inDirectory(directory -> {
            launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "income 150 Бонус", "expense 15.25 Еда, кафе", "budget 20 Еда, кафе",
                    "export-csv Мой кошелёк.csv", "expense 2 Прочее", "exit");
            String output = launch(directory, "login anna", "пароль", "import-csv Мой кошелёк.csv", "no",
                    "balance", "import-csv Мой кошелёк.csv", "yes", "import-csv Мой кошелёк.csv", "yes",
                    "balance", "export-csv Восстановленный.csv", "exit");
            contains(output, "Импорт отменён.\nБаланс — 132.75");
            contains(output, "Баланс — 134.75");
            equal(-1L, Files.mismatch(directory.resolve("Мой кошелёк.csv"), directory.resolve("Восстановленный.csv")));
            contains(launch(directory, "login anna", "пароль", "balance", "exit"), "Баланс — 134.75");
        }));
        checks.run("Ошибочная запись CSV не изменяет кошелёк", () -> inDirectory(directory -> {
            Files.writeString(directory.resolve("broken.csv"),
                    "wallet-snapshot,1\ncategory,Еда,10\noperation,EXPENSE,2024-02-29,2,Чужая\nend\n");
            String output = launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "income 99 Бонус", "import-csv broken.csv", "balance", "exit");
            contains(output, "Запись CSV №3");
            contains(output, "Баланс — 99.00");
        }));
        checks.run("Пустой период и ошибки календарных дат", () -> inDirectory(directory -> {
            String output = launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "report-period 2024-02-29 2024-02-29", "report-period 2023-02-29 2024-02-29",
                    "report-period 2024-03-01 2024-02-29", "exit");
            contains(output, "За указанный период операций нет.");
            contains(output, "Некорректная дата «2023-02-29».");
            contains(output, "Начальная дата не может быть позже конечной.");
        }));
        checks.run("Неверные даты отклоняются при импорте и на обеих границах периода", () -> inDirectory(directory -> {
            launch(directory, "register anna", "пароль", "login anna", "пароль", "income 100 Зарплата",
                    "expense 5 Еда", "budget 15 Еда", "export-csv original.csv", "exit");
            for (String date : List.of("0000-01-01", "2023-02-29", "+10000-01-01", "10000-01-01")) {
                Files.writeString(directory.resolve("invalid.csv"), """
                        wallet-snapshot,1
                        category,Еда,99
                        operation,INCOME,2024-02-29,12,Еда
                        operation,INCOME,%s,20,Еда
                        end
                        """.formatted(date));
                String reason = date.equals("2023-02-29") ? "Некорректная дата «2023-02-29»."
                        : "Укажите дату в формате ГГГГ-ММ-ДД с годом от 0001 до 9999.";
                String restored = "after-" + date + ".csv";
                String output = launch(directory, "login anna", "пароль", "import-csv invalid.csv",
                        "report-period " + date + " 9999-12-31", "report-period 0001-01-01 " + date,
                        "balance", "export-csv " + restored, "exit");
                contains(output, "Запись CSV №4 — " + reason);
                contains(output, "Ошибка. " + reason + "\nОшибка. " + reason + "\nБаланс — 95.00");
                equal(-1L, Files.mismatch(directory.resolve("original.csv"), directory.resolve(restored)));
            }
        }));
        checks.run("Допустимые граничные даты импортируются и включаются в отчёты за день", () -> inDirectory(directory -> {
            Files.writeString(directory.resolve("dates.csv"), """
                    wallet-snapshot,1
                    category,Выплаты,
                    operation,INCOME,0001-01-01,11,Выплаты
                    operation,INCOME,2024-02-29,22,Выплаты
                    operation,INCOME,9999-12-31,33,Выплаты
                    end
                    """);
            String output = launch(directory, "register anna", "пароль", "login anna", "пароль",
                    "import-csv dates.csv", "yes", "balance", "exit");
            contains(output, "Кошелёк восстановлен из CSV-снимка.");
            contains(output, "Баланс — 66.00");
            output = launch(directory, "login anna", "пароль", "report-period 0001-01-01 0001-01-01",
                    "report-period 2024-02-29 2024-02-29", "report-period 9999-12-31 9999-12-31", "exit");
            contains(output, "Период — с 0001-01-01 по 0001-01-01 включительно.\n"
                    + "Категории — Выплаты\nДоход за период — 11.00");
            contains(output, "Период — с 2024-02-29 по 2024-02-29 включительно.\n"
                    + "Категории — Выплаты\nДоход за период — 22.00");
            contains(output, "Период — с 9999-12-31 по 9999-12-31 включительно.\n"
                    + "Категории — Выплаты\nДоход за период — 33.00");
        }));
    }

    static String launch(Path directory, String... commands) throws Exception {
        return launchWithStatus(directory, 0, commands);
    }

    static String launchWithStatus(Path directory, int expectedStatus, String... commands) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path log = directory.resolve("cli-output.txt");
        Process process = new ProcessBuilder(java.toString(), "-cp", classes.toString(), "finance.Main")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write((String.join("\n", commands) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new AssertionError("CLI не завершился вовремя.");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() != expectedStatus) {
                throw new AssertionError("Ожидался код " + expectedStatus + ", CLI вернул "
                        + process.exitValue() + "\n" + output);
            }
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static Path walletFile(Path directory, String login) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(login.getBytes(StandardCharsets.UTF_8));
        return directory.resolve(".finance-data/wallets").resolve(HexFormat.of().formatHex(digest) + ".txt");
    }

    static void inDirectory(DirectoryScenario scenario) throws Exception {
        Path directory = Files.createTempDirectory("finance-check-");
        try {
            scenario.run(directory);
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @FunctionalInterface
    interface DirectoryScenario {
        void run(Path directory) throws Exception;
    }

    private CliChecks() {
    }
}
