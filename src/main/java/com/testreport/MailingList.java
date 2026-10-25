package com.testreport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 메일링 리스트 CSV.
 *
 * <pre>
 * 이름,이메일,수신구분,사용여부
 * 홍길동,hong@example.com,TO,Y
 * 김철수,kim@example.com,CC,Y
 * </pre>
 * 수신구분: TO(받는사람) / CC(참조) / BCC(숨은참조), 사용여부가 N 인 줄은 제외한다.
 */
final class MailingList {

    enum Type { TO, CC, BCC }

    record Recipient(String name, String email, Type type) {
    }

    private MailingList() {
    }

    static List<Recipient> load(Path file) throws IOException {
        List<String[]> rows = CsvUtil.read(file);
        List<Recipient> recipients = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) { // 0행은 헤더
            String[] r = rows.get(i);
            String email = r.length > 1 ? r[1] : "";
            if (email.isEmpty()) {
                continue;
            }
            if (r.length > 3 && r[3].equalsIgnoreCase("N")) {
                continue;
            }
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new IllegalArgumentException(file + " " + (i + 1) + "행: 이메일 형식이 아닙니다: " + email);
            }
            recipients.add(new Recipient(r[0], email, parseType(r.length > 2 ? r[2] : "")));
        }
        if (recipients.stream().noneMatch(r -> r.type() == Type.TO)) {
            throw new IllegalArgumentException(file + ": 수신구분이 TO 인 수신자가 최소 1명 필요합니다.");
        }
        return recipients;
    }

    private static Type parseType(String s) {
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "CC", "참조" -> Type.CC;
            case "BCC", "숨은참조" -> Type.BCC;
            default -> Type.TO;
        };
    }
}
