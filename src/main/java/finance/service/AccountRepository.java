package finance.service;

import finance.model.Account;

import java.io.IOException;
import java.util.Optional;

public interface AccountRepository {
    boolean add(Account account) throws IOException;

    Optional<Account> findByLogin(String login);
}
