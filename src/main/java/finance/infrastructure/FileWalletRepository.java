package finance.infrastructure;

import finance.model.Operation;
import finance.model.Wallet;
import finance.service.WalletRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class FileWalletRepository implements WalletRepository {
    private static final String VERSION = "wallet-v3";
    private static final Set<String> READABLE_VERSIONS = Set.of("wallet-v1", "wallet-v2", VERSION);
    private static final String JOURNAL_VERSION = "wallet-transaction-v1";

    private final Path directory;
    private final Path journal;

    public FileWalletRepository(Path directory) {
        this.directory = directory;
        this.journal = directory.resolve("pending-transfer.txt");
    }

    @Override
    public Optional<Wallet> load(String ownerLogin) throws IOException {
        Path file = fileFor(ownerLogin);
        try {
            return StorageFiles.read(file, READABLE_VERSIONS).map(rows -> parseWallet(ownerLogin, rows));
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new IOException("Файл кошелька повреждён или имеет неподдерживаемый формат.", exception);
        }
    }

    @Override
    public void save(Wallet wallet) throws IOException {
        StorageFiles.write(fileFor(wallet.ownerLogin()), VERSION, walletRows(wallet));
    }

    @Override
    public void saveTogether(List<Wallet> wallets) throws IOException {
        List<String> rows = new ArrayList<>();
        for (Wallet wallet : wallets) {
            rows.add(StorageFiles.encode(wallet.ownerLogin()) + "\t"
                    + StorageFiles.encode(String.join("\n", walletRows(wallet))));
        }
        StorageFiles.write(journal, JOURNAL_VERSION, rows);
        applyJournal(wallets);
    }

    public void recoverPending() throws IOException {
        List<Wallet> wallets = new ArrayList<>();
        try {
            Optional<List<String>> data = StorageFiles.read(journal, JOURNAL_VERSION);
            if (data.isEmpty()) {
                return;
            }
            Set<String> owners = new HashSet<>();
            for (String row : data.get()) {
                String[] fields = row.split("\\t", -1);
                if (fields.length != 2) {
                    throw new IllegalArgumentException("Некорректная запись журнала.");
                }
                String owner = StorageFiles.decode(fields[0]);
                if (owner.isBlank() || owner.codePoints().anyMatch(Character::isWhitespace) || !owners.add(owner)) {
                    throw new IllegalArgumentException("Некорректный владелец кошелька в журнале.");
                }
                wallets.add(parseWallet(owner, StorageFiles.decode(fields[1]).lines().toList()));
            }
            if (wallets.size() < 2) {
                throw new IllegalArgumentException("В журнале недостаточно кошельков для восстановления.");
            }
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new IOException("Журнал незавершённых переводов повреждён.", exception);
        }
        try {
            applyJournal(wallets);
        } catch (IOException exception) {
            throw new IOException("Не удалось завершить сохранение переводов. Журнал сохранён для повтора.",
                    exception);
        }
    }

    private void applyJournal(List<Wallet> wallets) throws IOException {
        for (Wallet wallet : wallets) {
            save(wallet);
        }
        Files.delete(journal);
    }

    private static Wallet parseWallet(String ownerLogin, List<String> rows) {
        if (rows.isEmpty() || !ownerRow(ownerLogin).equals(rows.get(0))) {
            throw new IllegalArgumentException("Некорректный заголовок кошелька.");
        }
        Wallet wallet = new Wallet(ownerLogin);
        Set<String> categoryNames = new HashSet<>();
        List<Operation> operations = new ArrayList<>();
        Optional<String> transferCategory = Optional.empty();
        for (String line : rows.subList(1, rows.size())) {
            String[] fields = line.split("\\t", -1);
            switch (fields[0]) {
                case "category" -> loadCategory(wallet, categoryNames, fields);
                case "transfer-category" -> {
                    if (transferCategory.isPresent() || fields.length != 2) {
                        throw new IllegalArgumentException("Некорректная запись категории переводов.");
                    }
                    String category = StorageFiles.decode(fields[1]);
                    if (!categoryNames.contains(category)) {
                        throw new IllegalArgumentException("Неизвестная категория переводов.");
                    }
                    transferCategory = Optional.of(category);
                }
                case "operation" -> operations.add(loadOperation(categoryNames, fields, false));
                case "transfer" -> operations.add(loadOperation(categoryNames, fields, true));
                default -> throw new IllegalArgumentException("Неизвестная запись кошелька.");
            }
        }
        wallet.restoreOperations(operations, transferCategory);
        return wallet;
    }

    private static List<String> walletRows(Wallet wallet) {
        List<String> rows = new ArrayList<>();
        rows.add(ownerRow(wallet.ownerLogin()));
        wallet.categoryLimits().forEach((category, limit) -> rows.add("category\t" + StorageFiles.encode(category)
                + "\t" + limit.map(BigDecimal::toPlainString).orElse("")));
        wallet.assignedTransferCategoryName().ifPresent(category ->
                rows.add("transfer-category\t" + StorageFiles.encode(category)));
        for (Operation operation : wallet.history()) {
            String details = operation.type() + "\t" + operation.amount().toPlainString()
                    + "\t" + operation.date() + "\t" + StorageFiles.encode(operation.category());
            rows.add(operation.transfer().map(transfer -> "transfer\t" + details + "\t" + transfer.id()
                    + "\t" + StorageFiles.encode(transfer.counterparty())).orElse("operation\t" + details));
        }
        return rows;
    }

    private static void loadCategory(Wallet wallet, Set<String> categoryNames, String[] fields) {
        if (fields.length != 3) {
            throw new IllegalArgumentException("Некорректная запись категории.");
        }
        String name = StorageFiles.decode(fields[1]);
        if (!name.equals(name.strip())) {
            throw new IllegalArgumentException("Некорректное имя категории.");
        }
        wallet.addCategory(name);
        categoryNames.add(name);
        if (!fields[2].isEmpty()) {
            wallet.setBudget(name, parseAmount(fields[2]));
        }
    }

    private static Operation loadOperation(Set<String> categoryNames, String[] fields, boolean isTransfer) {
        if (fields.length != (isTransfer ? 7 : 5)) {
            throw new IllegalArgumentException("Некорректная запись операции.");
        }
        String category = StorageFiles.decode(fields[4]);
        if (!categoryNames.contains(category)) {
            throw new IllegalArgumentException("Неизвестная категория операции.");
        }
        Optional<Operation.Transfer> transfer = isTransfer
                ? Optional.of(new Operation.Transfer(UUID.fromString(fields[5]), StorageFiles.decode(fields[6])))
                : Optional.empty();
        return new Operation(Operation.Type.valueOf(fields[1]), parseAmount(fields[2]),
                category, LocalDate.parse(fields[3]), transfer);
    }

    private static BigDecimal parseAmount(String value) {
        BigDecimal amount = new BigDecimal(value);
        if (amount.scale() != 2 || !amount.toPlainString().equals(value)) {
            throw new IllegalArgumentException("Некорректная сумма в файле кошелька.");
        }
        return amount;
    }

    private static String ownerRow(String login) {
        return "owner\t" + StorageFiles.encode(login);
    }

    private Path fileFor(String login) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(login.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".txt");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Не удалось вычислить идентификатор файла кошелька.", exception);
        }
    }
}
