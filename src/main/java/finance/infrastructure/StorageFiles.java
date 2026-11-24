package finance.infrastructure;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class StorageFiles {
    private static final String END = "end";

    private StorageFiles() {
    }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Некорректный текст в файле данных.", exception);
        }
    }

    static Optional<List<String>> read(Path source, String version) throws IOException {
        return read(source, Set.of(version));
    }

    static Optional<List<String>> read(Path source, Set<String> versions) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        }
        int last = lines.size() - 1;
        if (last < 1 || !versions.contains(lines.get(0)) || !END.equals(lines.get(last))) {
            throw new IllegalArgumentException("Некорректная версия или неполный файл данных.");
        }
        return Optional.of(lines.subList(1, last));
    }

    static void write(Path target, String version, Iterable<String> rows) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".finance-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(version);
                writer.newLine();
                for (String row : rows) {
                    writer.write(row);
                    writer.newLine();
                }
                writer.write(END);
                writer.newLine();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
