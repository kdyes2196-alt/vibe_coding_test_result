package com.testreport;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 담당자 승인 후 발송: 실행 → 승인 대기 → 승인(--approve) 시에만 메일링 리스트로 발송. */
class ApprovalFlowTest {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @TempDir
    Path dir;

    private AppConfig config(String... extra) throws Exception {
        String[] base = {"mail.approval.required=true"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return TestFixtures.config(dir, ServerSetupTest.SMTP.getPort(), all);
    }

    @Test
    void 실행하면_발송하지_않고_승인_대기_보고서를_만든다() throws Exception {
        AppConfig config = config();
        new App(config, false, false).runOnce();

        assertEquals(0, smtp.getReceivedMessages().length, "승인 전에는 아무 메일도 나가지 않음");
        PendingReport.Info p = PendingReport.load(config);
        assertNotNull(p);
        assertTrue(Files.exists(p.report()), "승인 대기 엑셀 보관");
        assertTrue(p.subject().contains("전체 진척률"));
    }

    @Test
    void 승인하면_메일링_리스트로_첨부_발송하고_대기를_비운다() throws Exception {
        AppConfig config = config();
        new App(config, false, false).runOnce();
        String reportName = PendingReport.load(config).report().getFileName().toString();

        new App(config, false, false).approve();

        MimeMessage[] mails = smtp.getReceivedMessages();
        assertEquals(2, mails.length, "TO 1명 + CC 1명");
        Set<String> to = Arrays.stream(mails[0].getRecipients(Message.RecipientType.TO))
                .map(a -> ((InternetAddress) a).getAddress()).collect(Collectors.toSet());
        assertEquals(Set.of("owner@example.com"), to);
        assertEquals("담당자 <owner@example.com>", jakarta.mail.internet.MimeUtility.decodeText(mails[0].getHeader("To", ",")));
        Multipart parts = (Multipart) mails[0].getContent();
        assertEquals(reportName, MimeUtility.decodeText(parts.getBodyPart(1).getFileName()), "승인한 그 엑셀이 첨부됨");

        assertNull(PendingReport.load(config), "발송 후 승인 대기 비움");
        assertTrue(Files.exists(dir.resolve("state/last_status.tsv")), "발송 시점 상태를 '직전 발송' 기준으로 저장");
    }

    @Test
    void 폐기하면_발송하지_않는다() throws Exception {
        AppConfig config = config();
        new App(config, false, false).runOnce();
        assertTrue(new App(config, false, false).reject());

        assertNull(PendingReport.load(config));
        assertThrows(IllegalStateException.class, () -> new App(config, false, false).approve(),
                "승인 대기 건이 없으면 승인 불가");
        assertEquals(0, smtp.getReceivedMessages().length);
    }

    @Test
    void 다시_실행하면_승인_대기_보고서가_최신으로_교체된다() throws Exception {
        AppConfig config = config();
        new App(config, false, false, java.time.LocalDateTime.parse("2026-01-01T09:00")).runOnce();
        new App(config, false, false, java.time.LocalDateTime.parse("2026-01-01T15:00")).runOnce();

        PendingReport.Info p = PendingReport.load(config);
        assertEquals("테스트현황_20260101_15시.xlsx", p.report().getFileName().toString());
        try (var files = Files.list(PendingReport.dir(config))) {
            assertEquals(1, files.filter(f -> f.toString().endsWith(".xlsx")).count());
        }
    }

    @Test
    void 담당자_주소가_있으면_승인_요청_메일만_담당자에게_보낸다() throws Exception {
        AppConfig config = config("mail.approval.notifyTo=approver@example.com");
        new App(config, false, false).runOnce();

        MimeMessage[] mails = smtp.getReceivedMessages();
        assertEquals(1, mails.length);
        assertTrue(mails[0].getSubject().startsWith("[발송 승인 요청]"));
        assertEquals("approver@example.com", ((InternetAddress) mails[0].getRecipients(Message.RecipientType.TO)[0]).getAddress());
        assertFalse(((String) ((Multipart) mails[0].getContent()).getBodyPart(0).getContent()).isEmpty());
    }
}
