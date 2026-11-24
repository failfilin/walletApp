package finance.model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class CalendarDate {
    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private CalendarDate() {
    }

    public static LocalDate parse(String input) {
        if (!ISO_DATE.matcher(input).matches() || input.startsWith("0000")) {
            throw new IllegalArgumentException("Укажите дату в формате ГГГГ-ММ-ДД с годом от 0001 до 9999.");
        }
        try {
            return LocalDate.parse(input);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Некорректная дата «" + input + "».", exception);
        }
    }
}
