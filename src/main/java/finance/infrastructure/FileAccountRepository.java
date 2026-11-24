package finance.infrastructure;

import finance.model.Account;
import finance.service.AccountRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FileAccountRepository implements AccountRepository {
    private static final String VERSION = "accounts-v1";

    private final Path file;
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public FileAccountRepository(Path directory) throws IOException {
        file = directory.resolve("accounts.txt");
        try {
            for (String line : StorageFiles.read(file, VERSION).orElseGet(List::of)) {
                Account account = parse(line);
                if (accounts.putIfAbsent(account.login(), account) != null) {
                    throw new IllegalArgumentException("Повторяющийся логин.");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Файл учётных записей повреждён или имеет неподдерживаемый формат.", exception);
        }
    }

    @Override
    public boolean add(Account account) throws IOException {
        if (accounts.containsKey(account.login())) {
            return false;
        }
        List<String> rows = new ArrayList<>();
        for (Account existing : accounts.values()) {
            rows.add(format(existing));
        }
        rows.add(format(account));
        StorageFiles.write(file, VERSION, rows);
        accounts.put(account.login(), account);
        return true;
    }

    @Override
    public Optional<Account> findByLogin(String login) {
        return Optional.ofNullable(accounts.get(login));
    }

    private static Account parse(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Некорректная запись учётной записи.");
        }
        String login = StorageFiles.decode(fields[0]);
        Base64.Decoder decoder = Base64.getDecoder();
        if (login.isBlank() || login.codePoints().anyMatch(Character::isWhitespace)
                || decoder.decode(fields[1]).length != 16 || decoder.decode(fields[2]).length != 32) {
            throw new IllegalArgumentException("Некорректная запись учётной записи.");
        }
        return new Account(login, fields[1], fields[2]);
    }

    private static String format(Account account) {
        return StorageFiles.encode(account.login()) + "\t" + account.salt() + "\t" + account.passwordHash();
    }
}
