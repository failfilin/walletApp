package finance.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

final class TextTable {
    private final List<String> headings;
    private final Set<Integer> numericColumns;
    private final List<List<String>> rows = new ArrayList<>();

    TextTable(List<String> headings, Set<Integer> numericColumns) {
        this.headings = headings;
        this.numericColumns = numericColumns;
    }

    void addRow(String... cells) {
        rows.add(Arrays.stream(cells).map(TextTable::displayText).toList());
    }

    String format() {
        int[] widths = headings.stream().mapToInt(TextTable::width).toArray();
        for (List<String> row : rows) {
            for (int index = 0; index < widths.length; index++) {
                widths[index] = Math.max(widths[index], width(row.get(index)));
            }
        }

        StringBuilder text = new StringBuilder();
        appendRow(text, headings, widths);
        StringJoiner separator = new StringJoiner("-+-");
        for (int width : widths) {
            separator.add("-".repeat(width));
        }
        text.append(separator).append('\n');
        for (List<String> row : rows) {
            appendRow(text, row, widths);
        }
        return text.toString();
    }

    private void appendRow(StringBuilder text, List<String> cells, int[] widths) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                text.append(" | ");
            }
            String cell = cells.get(index);
            String padding = " ".repeat(widths[index] - width(cell));
            if (numericColumns.contains(index)) {
                text.append(padding).append(cell);
            } else {
                text.append(cell);
                if (index < cells.size() - 1) {
                    text.append(padding);
                }
            }
        }
        text.append('\n');
    }

    static String displayText(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("\t", "\\t").replace("|", "\\|");
    }

    private static int width(String value) {
        return value.codePointCount(0, value.length());
    }
}
