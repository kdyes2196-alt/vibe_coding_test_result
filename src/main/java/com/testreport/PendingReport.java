package com.testreport;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 담당자 승인을 기다리는 보고서 (state/pending/).
 * 보고서 엑셀 사본, 메일 제목·본문, 발송 시 기준이 될 O/X 상태를 함께 보관한다.
 */
final class PendingReport {

    record Info(Path report, String subject, String html, LocalDateTime createdAt, Map<String, String> status) {
    }

    private static final String META = "meta.properties";
    private static final String HTML = "mail.html";
    private static final String STATUS = "status.tsv";

    private PendingReport() {
    }

    static Path dir(AppConfig config) {
        return config.stateDir().resolve("pending");
    }

    /** 기존 승인 대기 건이 있으면 새 보고서로 교체한다. */
    static Path save(AppConfig config, Path report, String subject, String html, LocalDateTime createdAt,
                     Map<String, String> status) throws IOException {
        clear(config);
        Path dir = dir(config);
        Files.createDirectories(dir);
        Path copy = dir.resolve(report.getFileName());
        Files.copy(report, copy, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve(HTML), html, StandardCharsets.UTF_8);
        StatusSnapshot.save(dir.resolve(STATUS), status);

        Properties meta = new Properties();
        meta.setProperty("report", copy.getFileName().toString());
        meta.setProperty("subject", subject);
        meta.setProperty("createdAt", createdAt.toString());
        try (Writer w = Files.newBufferedWriter(dir.resolve(META), StandardCharsets.UTF_8)) {
            meta.store(w, "승인 대기 보고서");
        }
        return copy;
    }

    /** 승인 대기 건이 없으면 null. */
    static Info load(AppConfig config) throws IOException {
        Path dir = dir(config);
        Path metaFile = dir.resolve(META);
        if (!Files.exists(metaFile)) {
            return null;
        }
        Properties meta = new Properties();
        try (Reader r = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            meta.load(r);
        }
        return new Info(dir.resolve(meta.getProperty("report")), meta.getProperty("subject"),
                Files.readString(dir.resolve(HTML), StandardCharsets.UTF_8),
                LocalDateTime.parse(meta.getProperty("createdAt")),
                StatusSnapshot.load(dir.resolve(STATUS)));
    }

    static void clear(AppConfig config) throws IOException {
        Path dir = dir(config);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
