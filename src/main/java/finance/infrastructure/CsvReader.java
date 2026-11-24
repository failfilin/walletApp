package finance.infrastructure;

import java.util.ArrayList;
import java.util.List;

final class CsvReader {
    private final String text;
    private int offset;
    private int recordNumber;

    CsvReader(String text) {
        this.text = text;
        if (text.startsWith("\uFEFF")) {
            offset = 1;
        }
    }

    int recordNumber() {
        return recordNumber;
    }

    List<String> next() {
        if (offset == text.length()) {
            return null;
        }
        recordNumber++;
        List<String> fields = new ArrayList<>();
        while (true) {
            fields.add(readField());
            if (offset == text.length()) {
                return fields;
            }

            char separator = text.charAt(offset++);
            switch (separator) {
                case ',' -> { }
                case '\r' -> {
                    if (offset < text.length() && text.charAt(offset) == '\n') {
                        offset++;
                    }
                    return fields;
                }
                case '\n' -> {
                    return fields;
                }
                default -> throw new IllegalArgumentException("После закрывающей кавычки ожидалась запятая или конец записи.");
            }
        }
    }

    private String readField() {
        if (offset < text.length() && text.charAt(offset) == '"') {
            offset++;
            return readQuotedField();
        }

        int start = offset;
        while (offset < text.length()) {
            char character = text.charAt(offset);
            if (character == ',' || character == '\r' || character == '\n') {
                break;
            }
            if (character == '"') {
                throw new IllegalArgumentException("Кавычка внутри поля должна быть экранирована.");
            }
            offset++;
        }
        return text.substring(start, offset);
    }

    private String readQuotedField() {
        StringBuilder value = new StringBuilder();
        while (offset < text.length()) {
            char character = text.charAt(offset++);
            if (character == '"') {
                if (offset == text.length() || text.charAt(offset) != '"') {
                    return value.toString();
                }
                offset++;
            }
            value.append(character);
        }
        throw new IllegalArgumentException("Поле с кавычками не закрыто.");
    }
}
