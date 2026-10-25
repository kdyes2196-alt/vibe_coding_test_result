package com.testreport;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.List;

/**
 * 재계산된 통합테스트 현황 엑셀의 "(요약)" 시트에서 메일 본문에 쓸 값을 읽는다.
 *
 * <p>셀 주소를 고정하지 않고 "기관명", "구분" 같은 머리글을 찾아 위치를 잡으므로
 * 기관이나 조건 컬럼이 늘어나도 그대로 동작한다.
 */
final class SummaryExtractor {

    private static final int HEADER_SEARCH_ROWS = 30;

    private final Workbook wb;
    private final FormulaEvaluator evaluator;
    private final DataFormatter formatter = new DataFormatter();

    SummaryExtractor(Workbook wb) {
        this.wb = wb;
        this.evaluator = wb.getCreationHelper().createFormulaEvaluator();
        this.evaluator.setIgnoreMissingWorkbooks(true);
    }

    StatusSummary extract(String sheetName) {
        Sheet sheet = findSummarySheet(sheetName);
        Cell nameHeader = findCell(sheet, "기관명");
        if (nameHeader == null) {
            throw new IllegalStateException("'" + sheet.getSheetName() + "' 시트에서 '기관명' 머리글을 찾지 못했습니다.");
        }
        int headerRowIdx = nameHeader.getRowIndex();
        int nameCol = nameHeader.getColumnIndex();
        int progressCol = nameCol + 1;
        int firstCondCol = nameCol + 2;

        Row headerRow = sheet.getRow(headerRowIdx);
        Row codeRow = sheet.getRow(headerRowIdx - 1);
        List<StatusSummary.Condition> conditions = new ArrayList<>();
        String currentCode = "";
        for (int c = firstCondCol; ; c++) {
            String condName = text(headerRow.getCell(c));
            if (!condName.startsWith("조건")) {
                break;
            }
            String code = codeRow == null ? "" : text(codeRow.getCell(c));
            if (!code.isEmpty()) {
                currentCode = code; // 병합 셀이면 첫 칸에만 값이 있다
            }
            conditions.add(new StatusSummary.Condition(currentCode, condName));
        }

        List<StatusSummary.Agency> agencies = new ArrayList<>();
        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String name = row == null ? "" : text(row.getCell(nameCol));
            if (name.isEmpty()) {
                break;
            }
            List<String> marks = new ArrayList<>();
            for (int i = 0; i < conditions.size(); i++) {
                String v = text(row.getCell(firstCondCol + i)).toUpperCase();
                marks.add(v.equals("O") || v.equals("X") ? v : "");
            }
            agencies.add(new StatusSummary.Agency(
                    nameCol >= 2 ? text(row.getCell(nameCol - 2)) : "",
                    nameCol >= 1 ? text(row.getCell(nameCol - 1)) : "",
                    name,
                    number(row.getCell(progressCol)),
                    marks));
        }

        return new StatusSummary(conditions, agencies, readBuckets(sheet));
    }

    /** "■ 전체 현황 요약" 표: '구분' 행의 구간명과 바로 아래 행의 기관 수. */
    private List<StatusSummary.Bucket> readBuckets(Sheet sheet) {
        List<StatusSummary.Bucket> buckets = new ArrayList<>();
        Cell label = findCell(sheet, "구분");
        if (label == null) {
            return buckets;
        }
        Row labels = sheet.getRow(label.getRowIndex());
        Row values = sheet.getRow(label.getRowIndex() + 1);
        for (int c = label.getColumnIndex() + 1; ; c++) {
            String l = text(labels.getCell(c));
            if (l.isEmpty()) {
                break;
            }
            if (l.equals("1")) {
                l = "100%"; // 시트에는 백분율 서식의 1 로 들어 있음
            }
            buckets.add(new StatusSummary.Bucket(l, values == null ? "" : text(values.getCell(c))));
        }
        return buckets;
    }

    private Sheet findSummarySheet(String sheetName) {
        if (sheetName != null) {
            Sheet s = wb.getSheet(sheetName);
            if (s == null) {
                throw new IllegalStateException("요약 시트를 찾지 못했습니다: " + sheetName);
            }
            return s;
        }
        for (Sheet s : wb) {
            if (s.getSheetName().contains("(요약)")) {
                return s;
            }
        }
        return wb.getSheetAt(0);
    }

    private Cell findCell(Sheet sheet, String value) {
        for (int r = 0; r < HEADER_SEARCH_ROWS && r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (value.equals(text(cell))) {
                    return cell;
                }
            }
        }
        return null;
    }

    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    /** 숫자가 아니거나(#DIV/0! 등) 비어 있으면 null. */
    private Double number(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cell.getCellType() == CellType.FORMULA) {
            CellValue v = evaluator.evaluate(cell);
            return v != null && v.getCellType() == CellType.NUMERIC ? v.getNumberValue() : null;
        }
        return null;
    }
}
