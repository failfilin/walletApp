package finance;

import java.util.Objects;

public final class ProjectChecks {
    private int passed;
    private int failed;

    public static void main(String[] args) {
        ProjectChecks checks = new ProjectChecks();
        CoreChecks.run(checks);
        CliChecks.run(checks);
        TransferChecks.run(checks);
        System.out.printf("Проверки: %d пройдено, %d ошибок.%n", checks.passed, checks.failed);
        if (checks.failed != 0) {
            throw new AssertionError("Есть непройденные проверки.");
        }
    }

    void run(String name, Scenario scenario) {
        try {
            scenario.run();
            passed++;
            System.out.println("OK " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            System.err.println("FAIL " + name);
            failure.printStackTrace(System.err);
        }
    }

    static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Ожидалось " + expected + ", получено " + actual);
        }
    }

    static void contains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("Не найдено: " + expected + "\n" + text);
        }
    }

    static void rejects(Class<? extends Exception> type, Scenario scenario) throws Exception {
        try {
            scenario.run();
        } catch (Exception exception) {
            if (type.isInstance(exception)) {
                return;
            }
            throw exception;
        }
        throw new AssertionError("Ожидалось исключение " + type.getSimpleName());
    }

    @FunctionalInterface
    interface Scenario {
        void run() throws Exception;
    }
}
