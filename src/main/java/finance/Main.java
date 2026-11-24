package finance;

import finance.cli.CommandLoop;
import finance.infrastructure.FileAccountRepository;
import finance.infrastructure.FileWalletRepository;
import finance.service.AuthenticationService;
import finance.service.StatisticsService;
import finance.service.WalletService;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        PrintWriter errors = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        int status = run(errors);
        if (status != 0) {
            System.exit(status);
        }
    }

    private static int run(PrintWriter errors) {
        Path dataDirectory = Path.of(".finance-data").toAbsolutePath();
        try {
            Files.createDirectories(dataDirectory);
            try (FileChannel storage = FileChannel.open(dataDirectory.resolve(".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                if (storage.tryLock() == null) {
                    errors.println("Каталог данных уже используется другим экземпляром приложения.");
                    return 1;
                }
                return runCommands(dataDirectory, errors);
            }
        } catch (IOException exception) {
            errors.println("Не удалось открыть или закрыть хранилище в " + dataDirectory + ".");
            errors.println(exception.getMessage());
            return 1;
        }
    }

    private static int runCommands(Path dataDirectory, PrintWriter errors) throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        FileWalletRepository walletRepository = new FileWalletRepository(dataDirectory.resolve("wallets"));
        walletRepository.recoverPending();
        FileAccountRepository accounts = new FileAccountRepository(dataDirectory);
        WalletService wallets = new WalletService(Clock.systemDefaultZone(), walletRepository, accounts);
        AuthenticationService authentication = new AuthenticationService(accounts, wallets);
        StatisticsService statistics = new StatisticsService(wallets);

        int status = 0;
        try {
            new CommandLoop(input, output, System.console(), authentication, wallets, statistics).run();
        } catch (IOException | IOError exception) {
            errors.println("Ошибка чтения команд.");
            status = 1;
        }
        List<String> unsaved = wallets.saveChanged();
        if (!unsaved.isEmpty()) {
            errors.println("Не удалось сохранить изменения в кошельках пользователей:");
            for (String login : unsaved) {
                errors.println("  " + login);
            }
            return 1;
        }
        return status;
    }
}
