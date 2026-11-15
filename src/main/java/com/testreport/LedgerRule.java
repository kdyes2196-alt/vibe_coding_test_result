package com.testreport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 원장 조회 규칙 한 줄 (config/ledger_rules.csv).
 *
 * <pre>
 * 전문구분,테스트케이스(포함 문구),최소건수,조건(SQL WHERE)
 * 980001,전체 조회,1,"TRX_DV_CD = '980001' AND TRX_STAT_CD = '00' AND ADD_INQ_COND IS NULL"
 * </pre>
 *
 * 기관별 시트에서 '전문구분'이 같고 '테스트 케이스'에 포함 문구가 들어 있는 행에 적용된다.
 * 조건을 만족하는 거래가 최소건수 이상이면 그 행의 확인 일시 · 거래고유번호를 채운다.
 */
record LedgerRule(String messageCode, String caseKeyword, int minCount, String where) {

    String label() {
        return messageCode + " " + caseKeyword;
    }

    boolean matches(String code, String testCase) {
        return messageCode.equals(code) && testCase.replace("\n", " ").contains(caseKeyword);
    }

    static List<LedgerRule> load(Path file) throws IOException {
        List<String[]> rows = CsvUtil.read(file);
        List<LedgerRule> rules = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) { // 0행은 헤더
            String[] r = rows.get(i);
            if (r.length < 4 || r[3].isBlank()) {
                throw new IllegalArgumentException(file + " " + (i + 1) + "행: 컬럼이 4개(전문구분,테스트케이스,최소건수,조건)가 아닙니다.");
            }
            rules.add(new LedgerRule(r[0], r[1], Integer.parseInt(r[2]), r[3]));
        }
        return rules;
    }
}
