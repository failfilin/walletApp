package finance.cli;

import finance.infrastructure.CsvWalletSnapshot;
import finance.model.CalendarDate;
import finance.model.CategoryBudget;
import finance.model.Operation;
import finance.model.Wallet;
import finance.service.AuthenticationException;
import finance.service.AuthenticationService;
import finance.service.ReportPeriod;
import finance.service.StatisticsService;
import finance.service.WalletService;
import finance.service.WalletWarning;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandLoop {
    private static final Pattern MONEY_INPUT = Pattern.compile("-?[0-9]+(?:[.,][0-9]+)?");
    private static final Pattern REPORT_CATEGORY = Pattern.compile(
            "\\p{javaWhitespace}*(?:\"((?:[^\"]++|\"\")*+)\"|([^,\"]+))\\p{javaWhitespace}*(,|$)");

    private final BufferedReader input;
    private final PrintWriter output;
    private final Console console;
    private final AuthenticationService authentication;
    private final WalletService wallets;
    private final StatisticsService statistics;

    public CommandLoop(BufferedReader input, PrintWriter output, Console console,
                       AuthenticationService authentication, WalletService wallets, StatisticsService statistics) {
        this.input = input;
        this.output = output;
        this.console = console;
        this.authentication = authentication;
        this.wallets = wallets;
        this.statistics = statistics;
    }

    public void run() throws IOException {
        output.println("Личные финансы. Введите help для справки или exit для выхода.");

        String line;
        while ((line = readLine()) != null) {
            if (line.isBlank()) {
                showError("Введите команду. Для списка команд введите help.");
                continue;
            }

            String[] parts = line.strip().split("\\p{javaWhitespace}+", 2);
            try {
                switch (parts[0]) {
                    case "help" -> {
                        requireNoArguments(parts);
                        output.printf("%s", CliHelp.text(authentication.isLoggedIn()));
                    }
                    case "exit" -> {
                        requireNoArguments(parts);
                        return;
                    }
                    case "register", "login" -> {
                        if (!authenticate(parts)) {
                            return;
                        }
                    }
                    case "logout" -> {
                        requireNoArguments(parts);
                        authentication.logout();
                        output.println("Вы вышли из учётной записи.");
                    }
                    case "whoami" -> {
                        requireNoArguments(parts);
                        output.println("Текущий пользователь — " + authentication.requireLogin());
                    }
                    case "income" -> recordOperation(parts, Operation.Type.INCOME);
                    case "expense" -> recordOperation(parts, Operation.Type.EXPENSE);
                    case "transfer" -> transfer(parts);
                    case "balance" -> {
                        String ownerLogin = authentication.requireLogin();
                        requireNoArguments(parts);
                        output.println("Баланс — " + wallets.balance(ownerLogin).toPlainString());
                    }
                    case "history" -> showHistory(parts);
                    case "category" -> addCategory(parts);
                    case "category-rename" -> renameCategory(parts);
                    case "categories" -> showCategories(parts);
                    case "budget", "budget-change" -> setBudget(parts);
                    case "budget-remove" -> removeBudget(parts);
                    case "report", "report-period" -> output.printf("%s", createReport(parts));
                    case "report-file", "report-file-period" -> saveReport(parts);
                    case "export-csv" -> exportCsv(parts);
                    case "import-csv" -> importCsv(parts);
                    default -> showError("Неизвестная команда. Для списка команд введите help.");
                }
            } catch (AuthenticationException | IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        }
    }

    private boolean authenticate(String[] parts) throws IOException, AuthenticationException {
        String command = parts[0];
        if (parts.length != 2 || parts[1].codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Формат команды — " + command + " <логин>.");
        }

        output.println("Введите пароль.");
        char[] password = readPassword();
        if (password == null) {
            return false;
        }

        String login = parts[1];
        try {
            if (command.equals("register")) {
                authentication.register(login, password);
                output.println("Учётная запись создана. Для входа введите login " + login + ".");
            } else {
                authentication.login(login, password);
                output.println("Вход выполнен. Текущий пользователь — " + login);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
        return true;
    }

    private void recordOperation(String[] parts, Operation.Type type) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        String[] arguments = amountAndCategoryArguments(parts);
        List<WalletWarning> warnings = wallets.record(ownerLogin, type, parseAmount(arguments[0]), arguments[1]);
        output.println(type == Operation.Type.INCOME ? "Доход записан." : "Расход записан.");
        warnings.forEach(this::showWarning);
    }

    private void transfer(String[] parts) throws AuthenticationException {
        String sender = authentication.requireLogin();
        String[] arguments = parts.length == 1 ? new String[0] : parts[1].split("\\p{javaWhitespace}+");
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Формат команды — transfer <сумма> <логин получателя>.");
        }
        BigDecimal amount = parseAmount(arguments[0]);
        String recipient = arguments[1];
        List<WalletWarning> warnings;
        try {
            warnings = wallets.transfer(sender, recipient, amount);
        } catch (IOException exception) {
            showError("Не удалось загрузить кошелёк получателя. Файл недоступен или повреждён.");
            return;
        }
        output.println("Перевод пользователю «" + recipient + "» записан.");
        warnings.forEach(this::showWarning);
    }

    private void addCategory(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        if (parts.length == 1) {
            throw new IllegalArgumentException("Формат команды — category <название>.");
        }
        wallets.addCategory(ownerLogin, parts[1]);
        output.println("Категория создана.");
    }

    private void renameCategory(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        List<String> names = parts.length == 1 ? List.of() : parseCategoryNames(parts[1]);
        if (names.size() != 2) {
            throw new IllegalArgumentException("Формат команды — category-rename <старое>, <новое>.");
        }
        wallets.renameCategory(ownerLogin, names.get(0), names.get(1));
        output.println("Категория переименована.");
    }

    private void setBudget(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        String[] arguments = amountAndCategoryArguments(parts);
        BigDecimal limit = parseAmount(arguments[0]);
        boolean changing = parts[0].equals("budget-change");
        var warning = changing
                ? wallets.changeBudget(ownerLogin, arguments[1], limit)
                : wallets.setBudget(ownerLogin, arguments[1], limit);
        output.println(changing ? "Лимит изменён." : "Лимит установлен.");
        warning.ifPresent(this::showWarning);
    }

    private void removeBudget(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        if (parts.length == 1) {
            throw new IllegalArgumentException("Формат команды — budget-remove <категория>.");
        }
        wallets.removeBudget(ownerLogin, parts[1]);
        output.println("Лимит снят.");
    }

    private void showWarning(WalletWarning warning) {
        if (warning instanceof WalletWarning.BudgetExceeded exceeded) {
            output.println("Предупреждение. Лимит категории «" + exceeded.category()
                    + "» превышен на " + exceeded.amount().toPlainString() + ".");
        } else if (warning instanceof WalletWarning.BudgetThresholdReached threshold) {
            output.println("Предупреждение. В категории «" + threshold.category()
                    + "» израсходовано не менее 80% лимита — " + threshold.spent().toPlainString()
                    + " из " + threshold.limit().toPlainString() + ".");
        } else if (warning instanceof WalletWarning.ExpensesExceedIncome deficit) {
            output.println("Предупреждение. Общие расходы превысили доходы на "
                    + deficit.amount().toPlainString() + ".");
        } else if (warning instanceof WalletWarning.BalanceReachedZero) {
            output.println("Уведомление. Баланс кошелька равен 0.00.");
        }
    }

    private String[] amountAndCategoryArguments(String[] parts) {
        if (parts.length == 1) {
            throw new IllegalArgumentException("Формат команды — " + parts[0] + " <сумма> <категория>.");
        }
        String[] arguments = parts[1].split("\\p{javaWhitespace}+", 2);
        if (arguments.length == 1) {
            throw new IllegalArgumentException("Укажите категорию.");
        }
        return arguments;
    }

    private BigDecimal parseAmount(String value) {
        if (!MONEY_INPUT.matcher(value).matches()) {
            throw new IllegalArgumentException("Введите сумму числом, например 250 или 250,50.");
        }
        return new BigDecimal(value.replace(',', '.'));
    }

    private void showCategories(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        requireNoArguments(parts);
        List<CategoryBudget> categories = wallets.categoryBudgets(ownerLogin);
        if (categories.isEmpty()) {
            output.println("Категорий пока нет.");
            return;
        }

        output.printf("%s", ReportFormatter.formatBudgets(categories));
    }

    private String createReport(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        if (parts[0].endsWith("-period")) {
            return createPeriodReport(ownerLogin, parts);
        }
        List<String> categories = parts.length == 1 ? List.of() : parseCategoryNames(parts[1]);
        return ReportFormatter.format(statistics.summarize(ownerLogin, categories));
    }

    private String createPeriodReport(String ownerLogin, String[] parts) {
        String[] arguments = parts.length == 1 ? new String[0] : parts[1].split("\\p{javaWhitespace}+", 3);
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Формат команды — " + parts[0] + " <с> <по> [категории].");
        }
        ReportPeriod period = new ReportPeriod(CalendarDate.parse(arguments[0]), CalendarDate.parse(arguments[1]));
        List<String> categories = arguments.length == 2 ? List.of() : parseCategoryNames(arguments[2]);
        return ReportFormatter.format(statistics.summarize(ownerLogin, categories, period));
    }

    private List<String> parseCategoryNames(String input) {
        List<String> categories = new ArrayList<>();
        Matcher matcher = REPORT_CATEGORY.matcher(input);
        while (matcher.lookingAt()) {
            String quotedName = matcher.group(1);
            categories.add(quotedName == null ? matcher.group(2) : quotedName.replace("\"\"", "\""));
            if (matcher.group(3).isEmpty()) {
                return categories;
            }
            matcher.region(matcher.end(), input.length());
        }
        throw new IllegalArgumentException("Укажите названия категорий через запятую.");
    }

    private void saveReport(String[] parts) throws AuthenticationException, IOException {
        String report = createReport(parts);
        output.println("Введите путь к новому файлу отчёта.");
        String path = readLine();
        if (path == null) {
            return;
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("Укажите непустой путь к файлу отчёта.");
        }

        try {
            Path destination = Path.of(path);
            Files.writeString(destination, report, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
            output.println("Отчёт сохранён — " + destination);
        } catch (FileAlreadyExistsException exception) {
            showError("Файл уже существует. Укажите другой путь.");
        } catch (InvalidPathException | IOException exception) {
            showError("Не удалось сохранить отчёт. Проверьте путь и доступ к каталогу.");
        }
    }

    private void exportCsv(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        Path file = snapshotPath(parts);
        try {
            CsvWalletSnapshot.write(file, wallets.categoryLimits(ownerLogin), wallets.history(ownerLogin),
                    wallets.assignedTransferCategoryName(ownerLogin));
            output.println("CSV-снимок сохранён — " + file);
        } catch (FileAlreadyExistsException exception) {
            showError("Файл уже существует. Укажите другой путь.");
        } catch (IOException exception) {
            showError("Не удалось сохранить CSV-снимок. Проверьте путь и доступ к каталогу.");
        }
    }

    private void importCsv(String[] parts) throws AuthenticationException, IOException {
        String ownerLogin = authentication.requireLogin();
        Path file = snapshotPath(parts);
        Wallet replacement;
        try {
            replacement = CsvWalletSnapshot.read(file, ownerLogin);
        } catch (CharacterCodingException exception) {
            showError("CSV-снимок должен быть в кодировке UTF-8.");
            return;
        } catch (IOException exception) {
            showError("Не удалось прочитать CSV-снимок. Проверьте путь и доступ к файлу.");
            return;
        }

        output.println("Импорт заменит категории, бюджеты и операции текущего кошелька. Для подтверждения введите yes.");
        String confirmation = readLine();
        if (confirmation == null || !confirmation.strip().equalsIgnoreCase("yes")) {
            output.println("Импорт отменён.");
            return;
        }
        wallets.replaceWallet(ownerLogin, replacement);
        output.println("Кошелёк восстановлен из CSV-снимка.");
    }

    private Path snapshotPath(String[] parts) {
        if (parts.length == 1) {
            throw new IllegalArgumentException("Формат команды — " + parts[0] + " <путь>.");
        }
        try {
            return Path.of(parts[1]);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Некорректный путь к CSV-снимку.", exception);
        }
    }

    private void showHistory(String[] parts) throws AuthenticationException {
        String ownerLogin = authentication.requireLogin();
        requireNoArguments(parts);
        output.printf("%s", ReportFormatter.formatHistory(wallets.history(ownerLogin)));
    }

    private void requireNoArguments(String[] parts) {
        if (parts.length > 1) {
            throw new IllegalArgumentException("Команда " + parts[0] + " не принимает аргументы.");
        }
    }

    private String readLine() throws IOException {
        return console == null ? input.readLine() : console.readLine();
    }

    private char[] readPassword() throws IOException {
        if (console != null) {
            return console.readPassword();
        }
        String line = input.readLine();
        return line == null ? null : line.toCharArray();
    }

    private void showError(String message) {
        output.println("Ошибка. " + message);
    }
}
