package com.testreport;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 샘플 엑셀을 재계산했을 때 요약 값이 엑셀과 같게 나오는지 검증한다. */
class WorkbookCalculationTest {

    private static StatusSummary summarize(boolean withRawCounts) throws Exception {
        try (XSSFWorkbook wb = WorkbookUpdater.open(TestFixtures.SAMPLE_XLSX)) {
            if (withRawCounts) {
                Map<String, Long> counts = RawCountLoader.load(TestFixtures.SAMPLE_RAW);
                assertEquals(113, WorkbookUpdater.injectRawCounts(wb, "원천데이터", counts),
                        "끊긴 VLOOKUP(#REF!) 수식 113개가 모두 연결되어야 함");
            }
            WorkbookUpdater.recalculate(wb);
            return new SummaryExtractor(wb).extract(null);
        }
    }

    private static double progressOf(StatusSummary s, String name) {
        return s.agencies().stream().filter(a -> a.name().equals(name)).findFirst()
                .orElseThrow().progress();
    }

    @Test
    void 원본_그대로_재계산하면_엑셀에_저장된_값과_같다() throws Exception {
        StatusSummary s = summarize(false);

        assertEquals(12, s.agencies().size());
        assertEquals(15, s.conditions().size());
        assertEquals(List.of("980001", "980002", "980003", "980004", "900N02", "AC4111"),
                List.copyOf(s.conditionGroups().keySet()));

        assertEquals(40, progressOf(s, "KB국민카드"));
        assertEquals(36, progressOf(s, "삼성카드"));
        assertEquals(33, progressOf(s, "비씨카드"));
        assertEquals(0, progressOf(s, "전북은행카드"));
        assertEquals(25, progressOf(s, "토스뱅크"));
        assertEquals(10, progressOf(s, "전북은행"));

        assertEquals(List.of("3", "5", "4", "0", "0", "0"),
                s.buckets().stream().map(StatusSummary.Bucket::value).toList());
    }

    @Test
    void 원천_건수를_넣으면_900N02와_AC4111_조건이_반영된다() throws Exception {
        StatusSummary s = summarize(true);

        assertEquals(100, progressOf(s, "KB국민카드"), "KB국민카드는 모든 건수 조건을 충족");
        assertEquals(50, progressOf(s, "삼성카드"), "삼성카드는 900N02 조건1·2만 충족");
        assertEquals(40, progressOf(s, "롯데카드"), "롯데카드 S=3건은 5건 미만이라 미충족");
        assertEquals(58, s.total("O"));
        assertEquals(157, s.total("O") + s.total("X"));
        assertEquals(36.9, s.overallProgress());
        assertEquals(1, s.countCompleted());
        assertTrue(s.buckets().get(5).label().contains("100"));
        assertEquals("1", s.buckets().get(5).value());
    }
}
