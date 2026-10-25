package com.testreport;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** config/application.properties 를 UTF-8 로 읽어 설정값을 제공한다. */
public final class AppConfig {

    private final Properties props = new Properties();

    public AppConfig(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
    }

    public String get(String key, String defaultValue) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public String require(String key) {
        String value = get(key, null);
        if (value == null) {
            throw new IllegalStateException("설정값이 없습니다: " + key);
        }
        return value;
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    /** 값이 비어 있으면 null. */
    public Path getPath(String key) {
        String value = get(key, null);
        return value == null ? null : Path.of(value);
    }

    public Path sourceFile()        { return Path.of(require("source.file")); }
    public Path rawCountsFile()     { return getPath("rawCounts.file"); }
    public String rawSheetName()    { return get("rawCounts.sheetName", "원천데이터"); }
    public Path outputDir()         { return Path.of(get("output.dir", "output")); }
    public String outputPattern()   { return get("output.fileNamePattern", "테스트현황_{datetime}.xlsx"); }
    public int outputKeepDays()     { return getInt("output.keepDays", 30); }
    public Path stateDir()          { return Path.of(get("state.dir", "state")); }
    public Path logDir()            { return Path.of(get("log.dir", "logs")); }

    public String reportTitle()     { return get("report.title", "통합테스트 현황"); }
    public String summarySheet()    { return get("report.summarySheet", null); }
    public int warnThreshold()      { return getInt("report.warnThreshold", 60); }

    public boolean mailEnabled()    { return getBool("mail.enabled", true); }
    public boolean skipIfUnchanged(){ return getBool("mail.skipIfUnchanged", false); }
    public Path mailingListFile()   { return Path.of(get("mailingList.file", "config/mailing_list.csv")); }
    public String adminEmail()      { return get("mail.adminOnError", null); }

    /** 비밀번호는 환경변수 MAIL_PASSWORD 를 우선 사용한다. */
    public String mailPassword() {
        String env = System.getenv("MAIL_PASSWORD");
        return env != null && !env.isBlank() ? env : get("mail.password", "");
    }

    public boolean ledgerEnabled()  { return getBool("ledger.enabled", false); }
    public Path ledgerRulesFile()   { return Path.of(get("ledger.rules.file", "config/ledger_rules.csv")); }
    public boolean ledgerClearUnmatched() { return getBool("ledger.clearUnmatched", true); }

    /** 원장 DB 비밀번호는 환경변수 LEDGER_DB_PASSWORD 를 우선 사용한다. */
    public String ledgerPassword() {
        String env = System.getenv("LEDGER_DB_PASSWORD");
        return env != null && !env.isBlank() ? env : get("ledger.jdbc.password", "");
    }

    public List<LocalTime> scheduleTimes() {
        List<LocalTime> times = new ArrayList<>();
        for (String t : get("schedule.times", "09:00").split(",")) {
            if (!t.isBlank()) {
                times.add(LocalTime.parse(t.trim()));
            }
        }
        times.sort(null);
        return times;
    }

    public boolean weekdaysOnly()   { return getBool("schedule.weekdaysOnly", true); }
}
