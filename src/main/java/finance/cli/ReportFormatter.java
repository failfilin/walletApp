package finance.cli;

import finance.model.CategoryBudget;
import finance.model.Operation;
import finance.service.ReportPeriod;
import finance.service.StatisticsReport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ReportFormatter {
    private ReportFormatter() {
    }

    static String format(StatisticsReport report) {
        StringBuilder text = new StringBuilder();
        report.period().ifPresent(period -> appendPeriod(text, report, period));
        for (String name : report.unknownCategories()) {
            text.append("Категория «").append(TextTable.displayText(name)).append("» не найдена.\n");
        }

        if (report.period().isPresent()) {
            text.append("Доход за период — ").append(report.income().toPlainString()).append('\n')
                    .append("Расходы за период — ").append(report.expenses().toPlainString()).append('\n')
                    .append("Разница за период — ").append(report.balance().toPlainString()).append('\n');
            if (!report.hasOperations()) {
                text.append("За указанный период операций нет.\n");
            }
        } else {
            text.append("Общий доход — ").append(report.income().toPlainString()).append('\n')
                    .append("Общие расходы — ").append(report.expenses().toPlainString()).append('\n')
                    .append("Баланс — ").append(report.balance().toPlainString()).append('\n');
        }

        if (report.categories().isEmpty()) {
            return text.append("Нет категорий для отчёта.\n").toString();
        }

        TextTable categories = new TextTable(List.of("Категория", "Доходы", "Расходы", "Примечание"),
                Set.of(1, 2));
        for (StatisticsReport.CategoryTotals totals : report.categories()) {
            categories.addRow(totals.category(), totals.income().toPlainString(), totals.expenses().toPlainString(),
                    totals.hasOperations() ? "—" : "нет операций");
        }
        text.append(categories.format());

        if (report.period().isPresent()) {
            return text.toString();
        }
        if (report.budgets().isEmpty()) {
            return text.append("Бюджетов нет.\n").toString();
        }
        return text.append('\n').append(formatBudgets(report.budgets())).toString();
    }

    private static void appendPeriod(StringBuilder text, StatisticsReport report, ReportPeriod period) {
        text.append("Период — с ").append(period.start()).append(" по ").append(period.end())
                .append(" включительно.\n");
        if (!report.categories().isEmpty()) {
            text.append("Категории — ").append(String.join(", ", report.categories().stream()
                    .map(StatisticsReport.CategoryTotals::category).map(TextTable::displayText).toList())).append('\n');
        }
    }

    static String formatBudgets(List<CategoryBudget> budgets) {
        TextTable table = new TextTable(List.of("Категория", "Потрачено", "Лимит", "Остаток"), Set.of(1, 2, 3));
        for (CategoryBudget budget : budgets) {
            table.addRow(budget.category(), budget.spent().toPlainString(),
                    budget.limit().map(BigDecimal::toPlainString).orElse("—"),
                    budget.remaining().map(BigDecimal::toPlainString).orElse("—"));
        }
        return table.format();
    }

    static String formatHistory(List<Operation> operations) {
        if (operations.isEmpty()) {
            return "История операций пуста." + System.lineSeparator();
        }

        boolean hasTransfers = operations.stream().anyMatch(operation -> operation.transfer().isPresent());
        List<String> columns = hasTransfers ? List.of("Дата", "Тип", "Сумма", "Категория", "Перевод")
                : List.of("Дата", "Тип", "Сумма", "Категория");
        TextTable history = new TextTable(columns, Set.of(2));
        for (Operation operation : operations) {
            String type = operation.type() == Operation.Type.INCOME ? "Доход" : "Расход";
            List<String> cells = new ArrayList<>(List.of(operation.date().toString(), type,
                    operation.amount().toPlainString(), operation.category()));
            if (hasTransfers) {
                cells.add(operation.transfer().map(transfer -> transfer.counterparty() + " / " + transfer.id())
                        .orElse("—"));
            }
            history.addRow(cells.toArray(String[]::new));
        }
        return history.format();
    }
}
