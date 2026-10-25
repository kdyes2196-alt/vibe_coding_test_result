package com.testreport;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 원본 테스트현황 엑셀을 복사해 열고, (선택) 원천 건수를 넣은 뒤 모든 수식을 재계산한다.
 * 원본 파일은 수정하지 않는다.
 */
final class WorkbookUpdater {

    private static final Logger LOG = Logger.getLogger(WorkbookUpdater.class.getName());
    private static final int COPY_RETRY = 3;

    private WorkbookUpdater() {
    }

    /**
     * OneDrive 동기화 중이거나 엑셀로 열려 있어도 읽을 수 있도록 임시 파일로 복사한 뒤 연다.
     */
    static XSSFWorkbook open(Path source) throws IOException, InterruptedException {
        if (!Files.exists(source)) {
            throw new IOException("원본 파일이 없습니다: " + source);
        }
        Path temp = Files.createTempFile("test-report-", ".xlsx");
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (IOException e) {
                    if (attempt >= COPY_RETRY) {
                        throw e;
                    }
                    LOG.warning("원본 파일 복사 실패(" + attempt + "회), 5초 후 재시도: " + e.getMessage());
                    Thread.sleep(5_000);
                }
            }
            try (InputStream in = Files.newInputStream(temp)) {
                return new XSSFWorkbook(in);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 원천 건수를 별도 시트에 기록하고, 끊어져 있는 VLOOKUP 참조(#REF!)를 그 시트로 연결한다.
     *
     * @return 연결을 복구한 수식 개수
     */
    static int injectRawCounts(XSSFWorkbook wb, String sheetName, Map<String, Long> counts) {
        int existing = wb.getSheetIndex(sheetName);
        if (existing >= 0) {
            wb.removeSheetAt(existing);
        }
        Sheet raw = wb.createSheet(sheetName);
        Row header = raw.createRow(0);
        header.createCell(0).setCellValue("키(전문코드+매입사+발급사+구분)");
        header.createCell(1).setCellValue("건수");
        int r = 1;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Row row = raw.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue());
        }
        raw.setColumnWidth(0, 40 * 256);

        int lastRow = Math.max(2, r);
        String range = "'" + sheetName + "'!$A$2:$B$" + lastRow;
        int fixed = 0;
        for (Sheet sheet : wb) {
            if (sheet == raw) {
                continue;
            }
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) {
                        continue;
                    }
                    String f = cell.getCellFormula();
                    if (f.contains("VLOOKUP") && f.contains("#REF!")) {
                        cell.setCellFormula(f.replace("#REF!", range));
                        fixed++;
                    }
                }
            }
        }
        return fixed;
    }

    /** 모든 수식을 재계산해 캐시값을 갱신한다(메일 미리보기·모바일에서도 최신 값이 보이도록). */
    static void recalculate(XSSFWorkbook wb) {
        XSSFFormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
        evaluator.setIgnoreMissingWorkbooks(true);
        evaluator.evaluateAll();
        // 수신자가 엑셀로 열 때도 한 번 더 재계산되도록 표시
        wb.setForceFormulaRecalculation(true);
    }

    static void save(XSSFWorkbook wb, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            wb.write(out);
        }
    }
}
