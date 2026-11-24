package finance.model;

import java.math.BigDecimal;

final class Money {
    private Money() {
    }

    static BigDecimal normalize(BigDecimal amount) {
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Укажите не более двух знаков после разделителя.");
        }
        return amount.setScale(2);
    }
}
