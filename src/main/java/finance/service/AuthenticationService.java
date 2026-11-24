package finance.service;

import finance.model.Account;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.CharBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class AuthenticationService {
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BITS = 256;
    private static final int HASH_ITERATIONS = 600_000;

    private final AccountRepository accounts;
    private final WalletService wallets;
    private final SecureRandom random = new SecureRandom();
    private String currentLogin;

    public AuthenticationService(AccountRepository accounts, WalletService wallets) {
        this.accounts = accounts;
        this.wallets = wallets;
    }

    public void register(String login, char[] password) throws AuthenticationException {
        validateCredentials(login, password);

        byte[] salt = new byte[SALT_LENGTH_BYTES];
        random.nextBytes(salt);
        Base64.Encoder encoder = Base64.getEncoder();
        Account account = new Account(login, encoder.encodeToString(salt),
                encoder.encodeToString(hashPassword(password, salt)));

        try {
            if (!accounts.add(account)) {
                throw new AuthenticationException("Этот логин уже занят.");
            }
        } catch (IOException exception) {
            throw new AuthenticationException("Не удалось сохранить учётную запись.", exception);
        }
    }

    public void login(String login, char[] password) throws AuthenticationException {
        validateCredentials(login, password);

        Account account = accounts.findByLogin(login)
                .orElseThrow(() -> new AuthenticationException("Неверный логин или пароль."));
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] enteredHash = hashPassword(password, decoder.decode(account.salt()));
        if (!MessageDigest.isEqual(decoder.decode(account.passwordHash()), enteredHash)) {
            throw new AuthenticationException("Неверный логин или пароль.");
        }

        try {
            wallets.load(account.login());
        } catch (IOException exception) {
            throw new AuthenticationException("Не удалось загрузить кошелёк пользователя «" + login
                    + "». Файл недоступен или повреждён.", exception);
        }
        currentLogin = account.login();
    }

    public String requireLogin() throws AuthenticationException {
        if (!isLoggedIn()) {
            throw new AuthenticationException("Сначала войдите в учётную запись.");
        }
        return currentLogin;
    }

    public boolean isLoggedIn() {
        return currentLogin != null;
    }

    public void logout() throws AuthenticationException {
        requireLogin();
        currentLogin = null;
    }

    private void validateCredentials(String login, char[] password) throws AuthenticationException {
        if (login.isBlank() || login.codePoints().anyMatch(Character::isWhitespace)) {
            throw new AuthenticationException("Укажите непустой логин без пробельных символов.");
        }
        if (CharBuffer.wrap(password).codePoints().allMatch(Character::isWhitespace)) {
            throw new AuthenticationException("Введите непустой пароль.");
        }
    }

    private byte[] hashPassword(char[] password, byte[] salt) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, HASH_ITERATIONS, HASH_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось вычислить хеш пароля.", exception);
        } finally {
            specification.clearPassword();
        }
    }
}
