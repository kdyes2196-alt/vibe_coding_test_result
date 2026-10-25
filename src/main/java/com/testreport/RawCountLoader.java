package com.testreport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 900N02 / AC4111 건수 원천 CSV 를 읽는다.
 *
 * <pre>
 * 전문코드,매입사,발급사,구분,건수
 * 900N02,381,381,S,7
 * AC4111,374,092,T1,5
 * </pre>
 *
 * 상세 시트의 VLOOKUP 키(CONCATENATE(전문코드,매입사,발급사,구분))와 같은 형태로 합쳐서 반환한다.
 * 같은 키가 여러 줄이면 건수를 합산한다.
 */
final class RawCountLoader {

    private RawCountLoader() {
    }

    static Map<String, Long> load(Path file) throws IOException {
        List<String[]> rows = CsvUtil.read(file);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) { // 0행은 헤더
            String[] r = rows.get(i);
            if (r.length < 5) {
                throw new IllegalArgumentException(file + " " + (i + 1) + "행: 컬럼이 5개(전문코드,매입사,발급사,구분,건수)가 아닙니다.");
            }
            String key = r[0] + r[1] + r[2] + r[3];
            long count;
            try {
                count = Long.parseLong(r[4].replace(",", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(file + " " + (i + 1) + "행: 건수가 숫자가 아닙니다: " + r[4]);
            }
            counts.merge(key, count, Long::sum);
        }
        return counts;
    }
}
