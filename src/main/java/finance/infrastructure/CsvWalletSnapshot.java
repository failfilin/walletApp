package finance.infrastructure;

import finance.model.CalendarDate;
import finance.model.Operation;
import finance.model.Wallet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CsvWalletSnapshot {
    private static final List<String> HEADER_V1 = List.of("wallet-snapshot", "1");
    private static final List<String> HEADER_V2 = List.of("wallet-snapshot", "2");
    private static final List<String> HEADER_V3 = List.of("wallet-snapshot", "3");
    private static final List<String> END = List.of("end");
    private static final Pattern AMOUNT = Pattern.compile("-?[0-9]+(?:\\.[0-9]{1,2})?");

    private CsvWalletSnapshot() {
    }

    public static void write(Path file, Map<String, Optional<BigDecimal>> limits,
                             List<Operation> operations, Optional<String> transferCategory) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (writer) {
            List<String> header = HEADER_V1;
            if (transferCategory.isPresent()) {
                header = HEADER_V3;
            } else if (operations.stream().anyMatch(operation -> operation.transfer().isPresent())) {
                header = HEADER_V2;
            }
            writeRow(writer, header);
            for (var entry : limits.entrySet()) {
                writeRow(writer, List.of("category", entry.getKey(),
                        entry.getValue().map(BigDecimal::toPlainString).orElse("")));
            }
            if (transferCategory.isPresent()) {
                writeRow(writer, List.of("transfer-category", transferCategory.get()));
            }
            for (Operation operation : operations) {
                if (operation.transfer().isPresent()) {
                    Operation.Transfer transfer = operation.transfer().get();
                    writeRow(writer, List.of("transfer", operation.type().name(), operation.date().toString(),
                            operation.amount().toPlainString(), operation.category(),
                            transfer.id().toString(), transfer.counterparty()));
                } else {
                    writeRow(writer, List.of("operation", operation.type().name(), operation.date().toString(),
                            operation.amount().toPlainString(), operation.category()));
                }
            }
            writeRow(writer, END);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    public static Wallet read(Path file, String ownerLogin) throws IOException {
        CsvReader reader = new CsvReader(Files.readString(file, StandardCharsets.UTF_8));
        Wallet wallet = new Wallet(ownerLogin);
        Set<String> categories = new HashSet<>();
        List<Operation> operations = new ArrayList<>();
        Optional<String> transferCategory = Optional.empty();
        try {
            List<String> header = reader.next();
            boolean transferCategoryAllowed = HEADER_V3.equals(header);
            boolean transfersAllowed = transferCategoryAllowed || HEADER_V2.equals(header);
            if (!transfersAllowed && !HEADER_V1.equals(header)) {
                throw new IllegalArgumentException("Ожидался заголовок wallet-snapshot с версией 1, 2 или 3.");
            }
            List<String> row;
            while ((row = reader.next()) != null) {
                if (END.equals(row)) {
                    if (reader.next() != null) {
                        throw new IllegalArgumentException("Лишние данные после конца снимка.");
                    }
                    wallet.restoreOperations(operations, transferCategory);
                    return wallet;
                }
                switch (row.get(0)) {
                    case "category" -> readCategory(wallet, categories, row);
                    case "transfer-category" -> {
                        if (!transferCategoryAllowed || transferCategory.isPresent() || row.size() != 2) {
                            throw new IllegalArgumentException("Некорректная запись категории переводов.");
                        }
                        String category = row.get(1).strip();
                        if (!categories.contains(category)) {
                            throw new IllegalArgumentException("Категория переводов «" + category + "» не объявлена.");
                        }
                        transferCategory = Optional.of(category);
                    }
                    case "operation" -> operations.add(readOperation(categories, row, false));
                    case "transfer" -> {
                        if (!transfersAllowed) {
                            throw new IllegalArgumentException("Для записей перевода требуется версия 2.");
                        }
                        operations.add(readOperation(categories, row, true));
                    }
                    default -> throw new IllegalArgumentException("Неизвестный тип записи.");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw invalidRecord(Math.max(1, reader.recordNumber()), exception.getMessage(), exception);
        }
        throw invalidRecord(reader.recordNumber() + 1, "Отсутствует завершающая запись end.", null);
    }

    private static void readCategory(Wallet wallet, Set<String> categories, List<String> row) {
        if (row.size() != 3) {
            throw new IllegalArgumentException("Для категории ожидаются название и лимит.");
        }
        String category = row.get(1).strip();
        wallet.addCategory(category);
        categories.add(category);
        if (!row.get(2).isEmpty()) {
            wallet.setBudget(category, parseAmount(row.get(2)));
        }
    }

    private static Operation readOperation(Set<String> categories, List<String> row, boolean isTransfer) {
        if (row.size() != (isTransfer ? 7 : 5)) {
            if (isTransfer) {
                throw new IllegalArgumentException("Для перевода ожидаются тип, дата, сумма, категория, ID и логин.");
            }
            throw new IllegalArgumentException("Для операции ожидаются тип, дата, сумма и категория.");
        }
        Operation.Type type = switch (row.get(1)) {
            case "INCOME" -> Operation.Type.INCOME;
            case "EXPENSE" -> Operation.Type.EXPENSE;
            default -> throw new IllegalArgumentException("Неизвестный тип операции. Укажите INCOME или EXPENSE.");
        };
        LocalDate date = CalendarDate.parse(row.get(2));
        String category = row.get(4).strip();
        if (!categories.contains(category)) {
            throw new IllegalArgumentException("Категория операции «" + category + "» не объявлена.");
        }
        Optional<Operation.Transfer> transfer = Optional.empty();
        if (isTransfer) {
            UUID id;
            try {
                id = UUID.fromString(row.get(5));
                if (!id.toString().equalsIgnoreCase(row.get(5))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Некорректный ID перевода.", exception);
            }
            transfer = Optional.of(new Operation.Transfer(id, row.get(6)));
        }
        return new Operation(type, parseAmount(row.get(3)), category, date, transfer);
    }

    private static BigDecimal parseAmount(String value) {
        if (!AMOUNT.matcher(value).matches()) {
            throw new IllegalArgumentException("Укажите сумму с точкой и не более чем двумя знаками после неё.");
        }
        return new BigDecimal(value);
    }

    private static IllegalArgumentException invalidRecord(int number, String reason, Throwable cause) {
        return new IllegalArgumentException("Запись CSV №" + number + " — " + reason, cause);
    }

    private static void writeRow(BufferedWriter writer, List<String> fields) throws IOException {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            String field = fields.get(index);
            if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
                writer.write('"');
                writer.write(field.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(field);
            }
        }
        writer.write("\r\n");
    }
}
