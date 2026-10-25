package com.testreport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 요약 시트에서 읽어 온 현황 데이터. */
record StatusSummary(List<Condition> conditions, List<Agency> agencies, List<Bucket> buckets) {

    /** 요약 시트의 조건 컬럼 하나(예: 900N02 / 조건3). */
    record Condition(String messageCode, String name) {
    }

    /** 기관 한 줄. marks 는 conditions 와 같은 순서이며 "O", "X", ""(해당 없음) 중 하나. */
    record Agency(String acquirer, String issuer, String name, Double progress, List<String> marks) {

        long count(String mark) {
            return marks.stream().filter(mark::equals).count();
        }
    }

    /** 전체 현황 요약의 진척률 구간(예: 20%미만 → 3개 기관). */
    record Bucket(String label, String value) {
    }

    long total(String mark) {
        return agencies.stream().mapToLong(a -> a.count(mark)).sum();
    }

    /** 전체 기관 합산 진척률 = 완료(O) / (완료(O) + 미완료(X)). */
    double overallProgress() {
        long o = total("O");
        long x = total("X");
        return o + x == 0 ? 0 : Math.round(o * 1000.0 / (o + x)) / 10.0;
    }

    long countBelow(int threshold) {
        return agencies.stream().filter(a -> a.progress() != null && a.progress() < threshold).count();
    }

    long countCompleted() {
        return agencies.stream().filter(a -> a.progress() != null && a.progress() >= 100).count();
    }

    /** 전문코드 → 해당 전문의 조건 컬럼 인덱스 목록 (시트 순서 유지). */
    Map<String, List<Integer>> conditionGroups() {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < conditions.size(); i++) {
            groups.computeIfAbsent(conditions.get(i).messageCode(), k -> new ArrayList<>()).add(i);
        }
        return groups;
    }
}
