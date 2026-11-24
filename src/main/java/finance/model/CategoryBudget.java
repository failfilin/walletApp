package finance.model;

import java.math.BigDecimal;
import java.util.Optional;

public record CategoryBudget(String category, BigDecimal spent, Optional<BigDecimal> limit) {
    public Optional<BigDecimal> remaining() {
        return limit.map(amount -> amount.subtract(spent));
    }
}
