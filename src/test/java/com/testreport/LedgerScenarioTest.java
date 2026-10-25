package com.testreport;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사용자 예시 시나리오: samples/ledger_scenario.sql 원장을 1.1 09:00 / 15:00 에 조회했을 때의 현황.
 *
 * <p>조건 인덱스(요약 시트 순서): 0~2 980001 조건1~3, 3 980002, 4 980003, 5 980004, 6~11 900N02, 12~14 AC4111
 */
class LedgerScenarioTest {

    private static final Path SCENARIO_SQL = Path.of("samples/ledger_scenario.sql").toAbsolutePath();
    private static final Path RULES = Path.of("config/ledger_rules.csv").toAbsolutePath();

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @TempDir
    Path dir;

    private App appAt(String asOf, String... extra) throws Exception {
        String[] lines = {
                "ledger.enabled=true",
                "ledger.jdbc.url=jdbc:h2:mem:" + dir.getFileName() + ";MODE=Oracle;INIT=RUNSCRIPT FROM '"
                        + SCENARIO_SQL.toString().replace('\\', '/') + "'",
                "ledger.jdbc.user=sa",
                "ledger.rules.file=" + RULES.toString().replace('\\', '/'),
        };
        String[] all = new String[lines.length + extra.length];
        System.arraycopy(lines, 0, all, 0, lines.length);
        System.arraycopy(extra, 0, all, lines.length, extra.length);
        return new App(TestFixtures.config(dir, ServerSetupTest.SMTP.getPort(), all), false, false,
                LocalDateTime.parse(asOf));
    }

    /** 해당 시각에 생성된 보고서를 다시 열어 요약을 읽는다. */
    private StatusSummary report(String hhmm) throws Exception {
        Path file = dir.resolve("output/테스트현황_20260101_" + hhmm + ".xlsx");
        assertTrue(Files.exists(file), file + " 생성");
        try (InputStream in = Files.newInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            return new SummaryExtractor(wb).extract(null);
        }
    }

    private static StatusSummary.Agency agency(StatusSummary s, String name) {
        return s.agencies().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertDone(StatusSummary s, String name, double progress, List<Integer> doneIdx) {
        StatusSummary.Agency a = agency(s, name);
        assertEquals(progress, a.progress(), name + " 진척률");
        for (int i = 0; i < a.marks().size(); i++) {
            if (doneIdx.contains(i)) {
                assertEquals("O", a.marks().get(i), name + " 조건#" + i + " 충족");
            } else {
                assertFalse(a.marks().get(i).equals("O"), name + " 조건#" + i + " 미충족");
            }
        }
    }

    @Test
    void 시나리오1_1월1일_9시_조회() throws Exception {
        appAt("2026-01-01T09:00").runOnce();
        StatusSummary s = report("0900");

        assertDone(s, "KB국민카드", 13, List.of(0, 1));     // 980001 조건1·2 → 2/15
        assertDone(s, "삼성카드", 21, List.of(0, 2, 3));     // 980001 조건1·3 + 980002 → 3/14 (삼성은 조건2 대상 아님)
        assertEquals("", agency(s, "삼성카드").marks().get(1), "삼성카드 980001 조건2는 해당 없음");
        for (StatusSummary.Agency a : s.agencies()) {
            if (!a.name().equals("KB국민카드") && !a.name().equals("삼성카드")) {
                assertDone(s, a.name(), 0, List.of());
            }
        }

        // 기관별 시트에 원장 거래가 근거로 기록됨
        Path file = dir.resolve("output/테스트현황_20260101_0900.xlsx");
        try (InputStream in = Files.newInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet kb = wb.getSheet("KB국민카드");
            DataFormatter f = new DataFormatter();
            assertEquals("1.1 08:10:11", f.formatCellValue(kb.getRow(4).getCell(6)));
            assertEquals("381000000001", f.formatCellValue(kb.getRow(4).getCell(7)));
            assertEquals("", f.formatCellValue(kb.getRow(7).getCell(6)), "980002 는 비정상 거래뿐이라 비어 있어야 함");
        }
    }

    @Test
    void 시나리오2_1월1일_15시_조회와_직전_대비_변경() throws Exception {
        appAt("2026-01-01T09:00").runOnce();
        appAt("2026-01-01T15:00").runOnce();
        StatusSummary s = report("1500");

        assertDone(s, "KB국민카드", 13, List.of(0, 1));     // 추가 진척 없음 (13시 980003 은 비정상)
        assertDone(s, "삼성카드", 29, List.of(0, 2, 3, 4));  // 980003 추가 → 4/14
        assertDone(s, "토스뱅크", 25, List.of(4));          // 980003 → 1/4
        assertDone(s, "하나카드", 0, List.of());            // 비정상 거래만
        assertDone(s, "롯데카드", 0, List.of());            // 16시 거래는 아직 반영 안 됨

        MimeMessage[] mails = smtp.getReceivedMessages();
        assertEquals(4, mails.length, "9시·15시 각각 수신자 2명");
        MimeMessage at15 = null;
        for (MimeMessage m : mails) {
            if (m.getSubject().contains("2026-01-01 15:00")) {
                at15 = m;
            }
        }
        assertTrue(at15 != null, "15시 메일 수신");
        String html = (String) ((Multipart) at15.getContent()).getBodyPart(0).getContent();
        assertTrue(html.contains("직전 발송 대비 변경"));
        assertTrue(html.contains("삼성카드</td><td") && html.contains("980003 조건1"), "삼성카드 신규 충족 표시");
        assertTrue(html.contains("토스뱅크</td><td"), "토스뱅크 신규 충족 표시");
        assertFalse(html.contains("KB국민카드</td><td style=\"padding:5px 8px;border:1px solid #c0c0c0;text-align:center;background:#e2efda"),
                "KB국민카드는 신규 충족 없음");
    }

    @Test
    void 진척이_없으면_다음_회차_메일은_건너뛴다() throws Exception {
        appAt("2026-01-01T15:00", "mail.skipIfUnchanged=true").runOnce();
        appAt("2026-01-01T15:30", "mail.skipIfUnchanged=true").runOnce();
        assertEquals(2, smtp.getReceivedMessages().length, "15:30 에는 변경이 없어 발송 생략");
        assertFalse(Files.exists(dir.resolve("output/테스트현황_20260101_1530.xlsx")));

        appAt("2026-01-01T16:30", "mail.skipIfUnchanged=true").runOnce();
        assertEquals(4, smtp.getReceivedMessages().length, "16시 롯데카드 거래로 변경 발생 → 발송");
    }
}
