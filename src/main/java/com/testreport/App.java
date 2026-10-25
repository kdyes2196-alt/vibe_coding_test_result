package com.testreport;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

/**
 * 통합테스트 현황 자동 갱신 · 메일 발송 프로그램.
 *
 * <pre>
 * java -jar test-report-mailer.jar [--config 설정파일] [--daemon] [--dry-run] [--force] [--as-of 시각]
 *   (옵션 없음)  1회 실행 후 종료 (Windows 작업 스케줄러에서 호출하는 방식)
 *   --daemon     프로그램을 켜 둔 채 schedule.times 시각마다 실행
 *   --dry-run    엑셀만 만들고 메일은 보내지 않음 (본문은 output/*.html 로 저장)
 *   --force      mail.skipIfUnchanged 설정과 관계없이 발송
 *   --as-of      원장을 이 시각 기준으로 조회 (예: 2026-01-01T09:00, 검증용)
 * </pre>
 */
public final class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FILE_HOUR = DateTimeFormatter.ofPattern("HH");

    private final AppConfig config;
    private final boolean dryRun;
    private final boolean force;
    private final LocalDateTime asOf;

    App(AppConfig config, boolean dryRun, boolean force) {
        this(config, dryRun, force, null);
    }

    /** @param asOf 원장 조회 기준 시각. null 이면 실행 시점 (검증 시 과거 시점 재현용) */
    App(AppConfig config, boolean dryRun, boolean force, LocalDateTime asOf) {
        this.config = config;
        this.dryRun = dryRun;
        this.force = force;
        this.asOf = asOf;
    }

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of("config/application.properties");
        boolean daemon = false;
        boolean dryRun = false;
        boolean force = false;
        LocalDateTime asOf = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configFile = Path.of(args[++i]);
                case "--daemon" -> daemon = true;
                case "--dry-run" -> dryRun = true;
                case "--force" -> force = true;
                case "--as-of" -> asOf = LocalDateTime.parse(args[++i]);
                default -> {
                    System.err.println("알 수 없는 옵션: " + args[i]);
                    System.exit(2);
                }
            }
        }

        AppConfig config = new AppConfig(configFile);
        setupLogging(config.logDir());
        App app = new App(config, dryRun, force, asOf);

        if (daemon) {
            app.runForever();
        } else {
            System.exit(app.runSafely() ? 0 : 1);
        }
    }

    /** 한 번 실행하고, 실패하면 로그를 남기고 관리자에게 알린다. */
    boolean runSafely() {
        try {
            runOnce();
            return true;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "실행 실패", e);
            notifyAdmin(e);
            return false;
        }
    }

    void runOnce() throws Exception {
        LocalDateTime now = (asOf != null ? asOf : LocalDateTime.now()).truncatedTo(ChronoUnit.MINUTES);
        Path source = config.sourceFile();
        Path rawFile = config.rawCountsFile();
        LOG.info("현황 갱신 시작: " + source + (asOf != null ? " (기준시각 " + now + ")" : ""));

        Path output;
        StatusSummary summary;
        List<StatusSnapshot.Change> changes;
        Path statusFile = config.stateDir().resolve("last_status.tsv");
        try (XSSFWorkbook wb = WorkbookUpdater.open(source)) {
            if (config.ledgerEnabled()) {
                List<LedgerRule> rules = LedgerRule.load(config.ledgerRulesFile());
                var results = new LedgerChecker(config).check(rules, now);
                int filled = new LedgerSheetUpdater(config.ledgerClearUnmatched()).apply(wb, rules, results);
                LOG.info("원장 조회 결과 반영: 충족 " + filled + "건 (규칙 " + rules.size() + "개)");
            }
            if (rawFile != null) {
                Map<String, Long> counts = RawCountLoader.load(rawFile);
                int fixed = WorkbookUpdater.injectRawCounts(wb, config.rawSheetName(), counts);
                LOG.info("원천 건수 " + counts.size() + "건 반영, 연결 복구한 수식 " + fixed + "개");
            }
            WorkbookUpdater.recalculate(wb);
            summary = new SummaryExtractor(wb).extract(config.summarySheet());

            Map<String, String> before = StatusSnapshot.load(statusFile);
            changes = StatusSnapshot.diff(before, StatusSnapshot.toMap(summary));
            if (changes != null) {
                for (StatusSnapshot.Change c : changes) {
                    LOG.info(String.format("변경: %s %s %s  %s → %s", c.agency(), c.messageCode(), c.condition(),
                            c.before().isEmpty() ? "-" : c.before(), c.after().isEmpty() ? "-" : c.after()));
                }
            }
            if (config.skipIfUnchanged() && !force && !dryRun && changes != null && changes.isEmpty()) {
                LOG.info("직전 실행 대비 테스트 현황 변경이 없어 발송을 건너뜁니다.");
                return;
            }

            String fileName = config.outputPattern()
                    .replace("{datetime}", FILE_TS.format(now))
                    .replace("{date}", FILE_DATE.format(now))
                    .replace("{hour}", FILE_HOUR.format(now));
            output = config.outputDir().resolve(fileName);
            WorkbookUpdater.save(wb, output);
        }
        LOG.info(String.format("보고서 생성: %s (기관 %d, 전체 진척률 %s%%, 직전 대비 변경 %d건)",
                output, summary.agencies().size(), MailBodyBuilder.fmt(summary.overallProgress()), changes == null ? 0 : changes.size()));

        MailBodyBuilder builder = new MailBodyBuilder(config.reportTitle(), config.warnThreshold());
        String subject = builder.subject(
                config.get("mail.subject", "[{title}] {datetime} 기준 - 전체 진척률 {progress}%"), summary, now);
        String html = builder.html(summary, changes, now, output.getFileName().toString());

        if (dryRun || !config.mailEnabled()) {
            Path preview = Path.of(output.toString().replaceFirst("\\.xlsx$", "") + "_메일미리보기.html");
            Files.writeString(preview, "<!-- 제목: " + subject + " -->\n" + html, StandardCharsets.UTF_8);
            LOG.info("메일 발송 생략(dry-run). 본문 미리보기: " + preview);
        } else {
            List<MailingList.Recipient> recipients = MailingList.load(config.mailingListFile());
            new MailSender(config).send(recipients, subject, html, output);
            LOG.info("메일 발송 완료: " + recipients.size() + "명 / 제목: " + subject);
        }
        StatusSnapshot.save(statusFile, StatusSnapshot.toMap(summary));

        cleanupOldOutputs();
    }

    /** schedule.times 의 다음 시각까지 대기 후 실행을 반복한다. */
    void runForever() throws InterruptedException {
        LOG.info("상주 모드 시작. 실행 시각: " + config.scheduleTimes()
                + (config.weekdaysOnly() ? " (평일만)" : ""));
        while (true) {
            LocalDateTime next = nextRun(LocalDateTime.now());
            LOG.info("다음 실행: " + next);
            Duration wait = Duration.between(LocalDateTime.now(), next);
            if (!wait.isNegative()) {
                Thread.sleep(wait.toMillis());
            }
            runSafely();
        }
    }

    LocalDateTime nextRun(LocalDateTime from) {
        List<LocalTime> times = config.scheduleTimes();
        for (int day = 0; day <= 7; day++) {
            LocalDateTime date = from.toLocalDate().plusDays(day).atStartOfDay();
            DayOfWeek dow = date.getDayOfWeek();
            if (config.weekdaysOnly() && (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)) {
                continue;
            }
            for (LocalTime t : times) {
                LocalDateTime candidate = date.with(t);
                if (candidate.isAfter(from)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("schedule.times 설정을 확인해 주세요.");
    }

    private void notifyAdmin(Exception e) {
        String admin = config.adminEmail();
        if (admin == null || dryRun || !config.mailEnabled()) {
            return;
        }
        try {
            new MailSender(config).sendText(admin, "[" + config.reportTitle() + "] 자동 발송 실패",
                    "현황 메일 자동 발송 중 오류가 발생했습니다.\n\n" + e + "\n\n자세한 내용은 "
                            + config.logDir().toAbsolutePath() + " 의 로그를 확인해 주세요.");
        } catch (Exception mailError) {
            LOG.log(Level.WARNING, "관리자 오류 알림 메일 발송도 실패했습니다.", mailError);
        }
    }

    private void cleanupOldOutputs() throws IOException {
        int keepDays = config.outputKeepDays();
        Path dir = config.outputDir();
        if (keepDays <= 0 || !Files.isDirectory(dir)) {
            return;
        }
        Instant limit = Instant.now().minus(Duration.ofDays(keepDays));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toInstant().isBefore(limit)) {
                    Files.delete(p);
                    LOG.info("보관 기간 경과 파일 삭제: " + p.getFileName());
                }
            }
        }
    }

    private static void setupLogging(Path logDir) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %5$s%6$s%n");
        Files.createDirectories(logDir);
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        ConsoleHandler console = new ConsoleHandler();
        console.setEncoding(StandardCharsets.UTF_8.name());
        console.setFormatter(new SimpleFormatter());
        FileHandler file = new FileHandler(logDir.resolve("app-%g.log").toString(), 5_000_000, 5, true);
        file.setEncoding(StandardCharsets.UTF_8.name());
        file.setFormatter(new SimpleFormatter());
        root.addHandler(console);
        root.addHandler(file);
    }
}
