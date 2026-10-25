package com.testreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailingListTest {

    @TempDir
    Path dir;

    @Test
    void 사용여부_N과_주석줄은_제외하고_수신구분을_읽는다() throws Exception {
        Path f = dir.resolve("list.csv");
        Files.writeString(f, """
                이름,이메일,수신구분,사용여부
                # 주석
                가,a@example.com,TO,Y
                나,b@example.com,참조,Y
                다,c@example.com,BCC,Y
                라,d@example.com,TO,N
                """);
        List<MailingList.Recipient> list = MailingList.load(f);
        assertEquals(3, list.size());
        assertEquals(MailingList.Type.CC, list.get(1).type());
        assertEquals(MailingList.Type.BCC, list.get(2).type());
    }

    @Test
    void 이메일_형식이_틀리면_줄번호와_함께_실패한다() throws Exception {
        Path f = dir.resolve("list.csv");
        Files.writeString(f, "이름,이메일,수신구분,사용여부\n가,not-an-email,TO,Y\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MailingList.load(f));
        assertEquals(true, e.getMessage().contains("2행"));
    }

    @Test
    void MS949로_저장된_CSV도_읽는다() throws Exception {
        Path f = dir.resolve("list.csv");
        Files.write(f, "이름,이메일,수신구분,사용여부\n홍길동,h@example.com,TO,Y\n".getBytes("MS949"));
        assertEquals("홍길동", MailingList.load(f).get(0).name());
    }
}
