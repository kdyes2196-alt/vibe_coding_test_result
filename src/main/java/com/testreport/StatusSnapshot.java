package com.testreport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 직전 실행의 기관별 O/X 상태를 state/last_status.tsv 에 저장해 두고, 이번 결과와 비교한다.
 * "9시 → 15시 사이에 새로 충족된 항목"을 메일에 보여 주고, 변경이 없으면 발송을 건너뛰는 데 쓴다.
 */
final class StatusSnapshot {

    /** 상태가 바뀐 조건 하나. before/after 는 "O", "X", ""(해당 없음). */
    record Change(String agency, String messageCode, String condition, String before, String after) {

        boolean isNewlyDone() {
            return after.equals("O") && !before.equals("O");
        }
    }

    private StatusSnapshot() {
    }

    static Map<String, String> toMap(StatusSummary s) {
        Map<String, String> map = new LinkedHashMap<>();
        for (StatusSummary.Agency a : s.agencies()) {
            for (int i = 0; i < s.conditions().size(); i++) {
                StatusSummary.Condition c = s.conditions().get(i);
                map.put(a.name() + "\t" + c.messageCode() + "\t" + c.name(), a.marks().get(i));
            }
        }
        return map;
    }

    /** 저장된 상태가 없으면 null. */
    static Map<String, String> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int last = line.lastIndexOf('\t');
            if (last > 0) {
                map.put(line.substring(0, last), line.substring(last + 1));
            }
        }
        return map;
    }

    static void save(Path file, Map<String, String> status) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        StringBuilder sb = new StringBuilder();
        status.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 직전 상태가 없으면(첫 실행) null. */
    static List<Change> diff(Map<String, String> before, Map<String, String> after) {
        if (before == null) {
            return null;
        }
        List<Change> changes = new ArrayList<>();
        after.forEach((key, now) -> {
            String prev = before.getOrDefault(key, "");
            if (!prev.equals(now)) {
                String[] k = key.split("\t", 3);
                changes.add(new Change(k[0], k[1], k[2], prev, now));
            }
        });
        return changes;
    }
}
