package com.multind.bitpongo.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretScanTest {
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("(?i)binance[_-]?secret\\s*[:=]\\s*['\"][A-Za-z0-9]{20,}"));

    @Test
    void trackedProductionSourcesContainNoCredentials() throws Exception {
        List<Path> roots = List.of(Path.of("src/main"), Path.of("Dockerfile"), Path.of("compose.yml"));
        for (Path root : roots) {
            assertThat(root).exists();
            try (var files = Files.isDirectory(root) ? Files.walk(root) : java.util.stream.Stream.of(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String content = Files.readString(file);
                    for (Pattern pattern : FORBIDDEN) {
                        assertThat(pattern.matcher(content).find()).as("敏感信息: %s", file).isFalse();
                    }
                }
            }
        }
    }
}
