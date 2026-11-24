package finance.service;

import java.time.LocalDate;

public record ReportPeriod(LocalDate start, LocalDate end) {
    public ReportPeriod {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Начальная дата не может быть позже конечной.");
        }
    }

    public boolean includes(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }
}
