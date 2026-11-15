package com.testreport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 테스트 공용: samples/ 의 실제 현황 엑셀과 원천 건수 예시를 사용한다. */
final class TestFixtures {

    static final Path SAMPLE_XLSX = Path.of("samples/test-status-sample.xlsx").toAbsolutePath();
    static final Path SAMPLE_RAW = Path.of("samples/raw_counts_sample.csv").toAbsolutePath();

    private TestFixtures() {
    }

    /** 임시 폴더에 설정 파일과 메일링 리스트를 만들고 AppConfig 를 돌려준다. extra 는 "키=값" 줄들. */
    static AppConfig config(Path dir, int smtpPort, String... extra) throws IOException {
        Path mailingList = dir.resolve("mailing_list.csv");
        Files.writeString(mailingList, String.join("\n",
                "이름,이메일,수신구분,사용여부",
                "담당자,owner@example.com,TO,Y",
                "참조자,cc@example.com,CC,Y",
                "제외자,off@example.com,TO,N"), StandardCharsets.UTF_8);

        StringBuilder p = new StringBuilder();
        p.append("source.file=").append(slash(SAMPLE_XLSX)).append('\n');
        p.append("output.dir=").append(slash(dir.resolve("output"))).append('\n');
        p.append("state.dir=").append(slash(dir.resolve("state"))).append('\n');
        p.append("log.dir=").append(slash(dir.resolve("logs"))).append('\n');
        p.append("output.fileNamePattern=테스트현황_{date}_{hour}시.xlsx\n");
        p.append("report.title=카드이동서비스 채널확대 통합테스트 현황\n");
        p.append("mailingList.file=").append(slash(mailingList)).append('\n');
        p.append("mail.subject=[{title}] {datetime} 기준 - 전체 진척률 {progress}%\n");
        p.append("mail.from=bot@example.com\n");
        p.append("mail.fromName=통합테스트 현황 알림\n");
        p.append("mail.smtp.host=127.0.0.1\n");
        p.append("mail.smtp.port=").append(smtpPort).append('\n');
        p.append("mail.smtp.security=NONE\n");
        p.append("mail.smtp.auth=false\n");
        for (String line : extra) {
            p.append(line).append('\n');
        }
        Path file = dir.resolve("application.properties");
        Files.writeString(file, p.toString(), StandardCharsets.UTF_8);
        return new AppConfig(file);
    }

    private static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
