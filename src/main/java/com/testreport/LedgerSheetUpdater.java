package com.testreport;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 원장 조회 결과를 기관별 시트의 '확인 일시' · '거래고유번호' 칸에 기록한다.
 * 통과여부(O/X)와 진척률은 엑셀 수식이 계산하므로 여기서는 입력 칸만 채운다.
 */
final class LedgerSheetUpdater {

    private static final Logger LOG = Logger.getLogger(LedgerSheetUpdater.class.getName());
    private static final int HEADER_SEARCH_ROWS = 10;

    private final DataFormatter formatter = new DataFormatter();
    private final boolean clearUnmatched;

    /** @param clearUnmatched true 면 원장에서 충족되지 않은 행의 기존 입력값을 지운다 (원장을 기준으로 삼음). */
    LedgerSheetUpdater(boolean clearUnmatched) {
        this.clearUnmatched = clearUnmatched;
    }

    /** @return 충족으로 기록한 행 수 */
    int apply(Workbook wb, List<LedgerRule> rules, Map<String, Map<LedgerRule, LedgerChecker.Evidence>> results) {
        Sheet detail = findDetailSheet(wb);
        Cell nameHeader = find(detail, "기관명");
        Cell acqHeader = find(detail, "매입사");
        Cell issHeader = find(detail, "발급사");
        if (nameHeader == null || acqHeader == null || issHeader == null) {
            throw new IllegalStateException("'" + detail.getSheetName() + "' 시트에서 매입사/발급사/기관명 머리글을 찾지 못했습니다.");
        }

        int filled = 0;
        for (int r = nameHeader.getRowIndex() + 1; r <= detail.getLastRowNum(); r++) {
            Row row = detail.getRow(r);
            String name = row == null ? "" : text(row.getCell(nameHeader.getColumnIndex()));
            if (name.isEmpty()) {
                break;
            }
            Sheet agencySheet = wb.getSheet(name);
            if (agencySheet == null) {
                LOG.warning("기관 시트가 없습니다: " + name);
                continue;
            }
            String key = LedgerChecker.agencyKey(text(row.getCell(acqHeader.getColumnIndex())),
                    text(row.getCell(issHeader.getColumnIndex())));
            filled += applyToAgency(agencySheet, rules, results.getOrDefault(key, Map.of()));
        }
        return filled;
    }

    private int applyToAgency(Sheet sheet, List<LedgerRule> rules, Map<LedgerRule, LedgerChecker.Evidence> evidence) {
        Cell codeHeader = find(sheet, "전문구분");
        Cell caseHeader = find(sheet, "테스트 케이스");
        Cell atHeader = find(sheet, "확인 일시");
        Cell noHeader = find(sheet, "거래고유번호");
        if (codeHeader == null || caseHeader == null || atHeader == null || noHeader == null) {
            LOG.warning("'" + sheet.getSheetName() + "' 시트 머리글(전문구분/테스트 케이스/확인 일시/거래고유번호)을 찾지 못했습니다.");
            return 0;
        }
        int filled = 0;
        for (int r = codeHeader.getRowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String code = row == null ? "" : text(row.getCell(codeHeader.getColumnIndex()));
            if (code.isEmpty()) {
                continue; // 병합된 머리글 아래의 빈 행
            }
            String testCase = text(row.getCell(caseHeader.getColumnIndex()));
            LedgerRule rule = rules.stream().filter(x -> x.matches(code, testCase)).findFirst().orElse(null);
            if (rule == null) {
                LOG.warning("'" + sheet.getSheetName() + "' " + (r + 1) + "행에 맞는 원장 규칙이 없습니다: " + code + " " + testCase);
                continue;
            }
            LedgerChecker.Evidence e = evidence.get(rule);
            if (e != null) {
                cell(row, atHeader.getColumnIndex()).setCellValue(e.confirmedAt());
                cell(row, noHeader.getColumnIndex()).setCellValue(e.txNo());
                filled++;
            } else if (clearUnmatched) {
                cell(row, atHeader.getColumnIndex()).setBlank();
                cell(row, noHeader.getColumnIndex()).setBlank();
            }
        }
        return filled;
    }

    private static Sheet findDetailSheet(Workbook wb) {
        for (Sheet s : wb) {
            if (s.getSheetName().contains("(상세)")) {
                return s;
            }
        }
        throw new IllegalStateException("이름에 '(상세)'가 들어간 시트를 찾지 못했습니다.");
    }

    private Cell find(Sheet sheet, String header) {
        for (int r = 0; r < HEADER_SEARCH_ROWS && r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell c : row) {
                if (header.equals(text(c))) {
                    return c;
                }
            }
        }
        return null;
    }

    private static Cell cell(Row row, int col) {
        Cell c = row.getCell(col);
        return c != null ? c : row.createCell(col);
    }

    private String text(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
}
