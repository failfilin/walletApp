package finance.model;

public record Account(String login, String salt, String passwordHash) {
}
