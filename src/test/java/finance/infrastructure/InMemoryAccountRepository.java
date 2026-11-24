package finance.infrastructure;

import finance.model.Account;
import finance.service.AccountRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryAccountRepository implements AccountRepository {
    private final Map<String, Account> accounts = new HashMap<>();

    @Override
    public boolean add(Account account) {
        return accounts.putIfAbsent(account.login(), account) == null;
    }

    @Override
    public Optional<Account> findByLogin(String login) {
        return Optional.ofNullable(accounts.get(login));
    }
}
