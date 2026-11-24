package finance.service;

import finance.model.Wallet;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface WalletRepository {
    Optional<Wallet> load(String ownerLogin) throws IOException;

    void save(Wallet wallet) throws IOException;

    void saveTogether(List<Wallet> wallets) throws IOException;
}
