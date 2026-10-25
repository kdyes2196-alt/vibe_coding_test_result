package com.testreport;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 프로그램 전체를 실행해 가짜 SMTP 서버(GreenMail)로 받은 메일을 검증한다. */
class EndToEndMailTest {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @TempDir
    Path dir;

    @Test
    void 보고서를_만들어_메일링_리스트에_첨부_발송한다() throws Exception {
        AppConfig config = TestFixtures.config(dir, ServerSetupTest.SMTP.getPort(),
                "rawCounts.file=" + TestFixtures.SAMPLE_RAW.toString().replace('\\', '/'));
        new App(config, false, false).runOnce();

        MimeMessage[] received = smtp.getReceivedMessages();
        assertEquals(2, received.length, "TO 1명 + CC 1명 (사용여부 N 제외)");
        MimeMessage msg = received[0];

        assertTrue(msg.getSubject().startsWith("[카드이동서비스 채널확대 통합테스트 현황]"), msg.getSubject());
        assertTrue(msg.getSubject().endsWith("전체 진척률 36.9%"), msg.getSubject());
        assertEquals(Set.of("owner@example.com"), emails(msg, Message.RecipientType.TO));
        assertEquals(Set.of("cc@example.com"), emails(msg, Message.RecipientType.CC));

        Multipart parts = (Multipart) msg.getContent();
        assertEquals(2, parts.getCount(), "HTML 본문 + 엑셀 첨부");
        String html = (String) parts.getBodyPart(0).getContent();
        assertTrue(html.contains("KB국민카드"));
        assertTrue(html.contains("36.9%"));

        BodyPart attachment = parts.getBodyPart(1);
        String fileName = MimeUtility.decodeText(attachment.getFileName());
        assertTrue(fileName.matches("테스트현황_\\d{8}_\\d{2}시\\.xlsx"), fileName);
        try (InputStream in = attachment.getInputStream(); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            assertTrue(wb.getSheetIndex("원천데이터") >= 0, "첨부 엑셀에 원천데이터 시트 포함");
            assertEquals(14, wb.getNumberOfSheets() - 1, "원본 14개 시트 유지");
        }
    }

    @Test
    void 변경이_없으면_두번째_실행은_발송하지_않는다() throws Exception {
        AppConfig config = TestFixtures.config(dir, ServerSetupTest.SMTP.getPort(), "mail.skipIfUnchanged=true");
        new App(config, false, false).runOnce();
        new App(config, false, false).runOnce();
        assertEquals(2, smtp.getReceivedMessages().length, "첫 실행분(수신자 2명)만 발송");

        new App(config, false, true).runOnce(); // --force
        assertEquals(4, smtp.getReceivedMessages().length);
    }

    @Test
    void dry_run이면_메일_대신_미리보기_HTML을_만든다() throws Exception {
        AppConfig config = TestFixtures.config(dir, ServerSetupTest.SMTP.getPort());
        new App(config, true, false).runOnce();
        assertEquals(0, smtp.getReceivedMessages().length);
        try (var files = Files.list(dir.resolve("output"))) {
            assertEquals(2, files.filter(p -> p.toString().endsWith(".xlsx") || p.toString().endsWith(".html")).count());
        }
    }

    @Test
    void 원본_파일이_없으면_실패를_반환하고_관리자에게_알린다() throws Exception {
        AppConfig config = TestFixtures.config(dir, ServerSetupTest.SMTP.getPort(),
                "source.file=" + dir.resolve("없는파일.xlsx").toString().replace('\\', '/'),
                "mail.adminOnError=admin@example.com");
        assertFalse(new App(config, false, false).runSafely());

        MimeMessage[] received = smtp.getReceivedMessages();
        assertEquals(1, received.length);
        assertTrue(received[0].getSubject().contains("자동 발송 실패"));
        assertEquals(Set.of("admin@example.com"), emails(received[0], Message.RecipientType.TO));
    }

    private static Set<String> emails(MimeMessage msg, Message.RecipientType type) throws Exception {
        var addresses = msg.getRecipients(type);
        return addresses == null ? Set.of() : Arrays.stream(addresses)
                .map(a -> ((InternetAddress) a).getAddress()).collect(Collectors.toSet());
    }
}
