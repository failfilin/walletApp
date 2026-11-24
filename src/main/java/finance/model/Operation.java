package finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Operation(Type type, BigDecimal amount, String category, LocalDate date, Optional<Transfer> transfer) {
    public enum Type {
        INCOME,
        EXPENSE
    }

    public record Transfer(UUID id, String counterparty) {
        public Transfer {
            Objects.requireNonNull(id);
            if (counterparty.isBlank() || counterparty.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Некорректный логин участника перевода.");
            }
        }
    }

    public Operation(Type type, BigDecimal amount, String category, LocalDate date) {
        this(type, amount, category, date, Optional.empty());
    }

    public Operation {
        Objects.requireNonNull(type);
        Objects.requireNonNull(date);
        Objects.requireNonNull(transfer);

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Сумма должна быть больше нуля.");
        }
        amount = Money.normalize(amount);
        category = Category.normalizeName(category);
    }
}
