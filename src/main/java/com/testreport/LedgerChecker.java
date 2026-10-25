package com.testreport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 실시간 전문 원장(ONLINE_LEDG)을 조회해 기관(매입사+발급사)별로 각 규칙의 충족 여부와 근거 거래를 구한다.
 * 테이블·컬럼명은 설정(ledger.*)으로 바꿀 수 있다.
 */
final class LedgerChecker {

    /** 규칙을 충족시킨 거래 (최소건수 번째로 들어온 거래). */
    record Evidence(String confirmedAt, String txNo, int count) {
    }

    private static final Logger LOG = Logger.getLogger(LedgerChecker.class.getName());
    private static final DateTimeFormatter SHEET_TS = DateTimeFormatter.ofPattern("M.d HH:mm:ss");
    private static final DateTimeFormatter SHEET_DATE = DateTimeFormatter.ofPattern("M.d");

    private final AppConfig config;

    LedgerChecker(AppConfig config) {
        this.config = config;
    }

    /**
     * @param asOf 이 시각까지 들어온 거래만 본다 (검증 시 --as-of 로 과거 시점 재현)
     * @return 기관키(매입사|발급사) → 규칙 → 근거 거래. 미충족 규칙은 들어 있지 않다.
     */
    Map<String, Map<LedgerRule, Evidence>> check(List<LedgerRule> rules, LocalDateTime asOf) throws SQLException {
        String table = config.get("ledger.table", "ONLINE_LEDG");
        String dateCol = config.get("ledger.col.txDate", "TRX_DT");
        String noCol = config.get("ledger.col.txNo", "TRX_UNQ_NO");
        String acqCol = config.get("ledger.col.acquirer", "ACQ_INST_CD");
        String issCol = config.get("ledger.col.issuer", "ISS_INST_CD");
        String extraWhere = config.get("ledger.extraWhere", null);

        Map<String, Map<LedgerRule, Evidence>> result = new HashMap<>();
        try (Connection con = DriverManager.getConnection(config.require("ledger.jdbc.url"),
                config.get("ledger.jdbc.user", ""), config.ledgerPassword())) {
            for (LedgerRule rule : rules) {
                String sql = "SELECT " + acqCol + ", " + issCol + ", " + dateCol + ", " + noCol
                        + " FROM " + table
                        + " WHERE (" + rule.where() + ")"
                        + (extraWhere == null ? "" : " AND (" + extraWhere + ")")
                        + " AND " + dateCol + " <= ?"
                        + " ORDER BY " + dateCol + ", " + noCol;
                int matched = 0;
                Map<String, Integer> counts = new HashMap<>();
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    bindAsOf(ps, asOf);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String key = agencyKey(rs.getString(1), rs.getString(2));
                            int n = counts.merge(key, 1, Integer::sum);
                            if (n == rule.minCount()) {
                                result.computeIfAbsent(key, k -> new HashMap<>())
                                        .put(rule, new Evidence(format(rs.getObject(3)), rs.getString(4), n));
                                matched++;
                            } else if (n > rule.minCount()) {
                                result.get(key).computeIfPresent(rule, (r, e) -> new Evidence(e.confirmedAt(), e.txNo(), n));
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new SQLException("원장 조회 실패 [" + rule.label() + "]: " + sql, e);
                }
                LOG.info("원장 조회 [" + rule.label() + "] 충족 기관 " + matched + "곳");
            }
        }
        return result;
    }

    /** ledger.dateType: TIMESTAMP(기본) / YYYYMMDD / YYYYMMDDHHMMSS (문자열 컬럼) */
    private void bindAsOf(PreparedStatement ps, LocalDateTime asOf) throws SQLException {
        switch (config.get("ledger.dateType", "TIMESTAMP")) {
            case "YYYYMMDD" -> ps.setString(1, asOf.format(DateTimeFormatter.BASIC_ISO_DATE));
            case "YYYYMMDDHHMMSS" -> ps.setString(1, asOf.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            default -> ps.setTimestamp(1, Timestamp.valueOf(asOf));
        }
    }

    /** 기관별 시트의 '확인 일시' 형식(예: 2.2 13:02:43)으로 바꾼다. */
    private static String format(Object value) {
        if (value instanceof Timestamp ts) {
            return SHEET_TS.format(ts.toLocalDateTime());
        }
        if (value instanceof LocalDateTime ldt) {
            return SHEET_TS.format(ldt);
        }
        if (value instanceof java.sql.Date d) {
            return SHEET_DATE.format(d.toLocalDate());
        }
        String s = String.valueOf(value).trim();
        if (s.matches("\\d{14}")) {
            return SHEET_TS.format(LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        }
        if (s.matches("\\d{8}")) {
            return SHEET_DATE.format(LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE));
        }
        return s;
    }

    /** 엑셀(381, '011')과 DB('381', '011' 또는 11) 표기 차이를 없앤 기관 키. */
    static String agencyKey(String acquirer, String issuer) {
        return norm(acquirer) + "|" + norm(issuer);
    }

    private static String norm(String code) {
        String s = code == null ? "" : code.trim();
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        s = s.replaceFirst("^0+(?=.)", "");
        return s;
    }
}
