package com.testreport;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 요약 데이터를 메일 제목과 HTML 본문으로 만든다. 메일 클라이언트 호환을 위해 인라인 스타일만 쓴다. */
final class MailBodyBuilder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String TH = "style=\"background:#1f4e78;color:#fff;padding:6px 8px;border:1px solid #c0c0c0;font-weight:bold\"";
    private static final String TD = "padding:5px 8px;border:1px solid #c0c0c0;text-align:center";
    private static final String RED = "#c00000";

    private final String title;
    private final int warnThreshold;

    MailBodyBuilder(String title, int warnThreshold) {
        this.title = title;
        this.warnThreshold = warnThreshold;
    }

    String subject(String pattern, StatusSummary s, LocalDateTime at) {
        return pattern
                .replace("{title}", title)
                .replace("{datetime}", TS.format(at))
                .replace("{date}", at.toLocalDate().toString())
                .replace("{progress}", fmt(s.overallProgress()));
    }

    /** @param changes 직전 실행 대비 변경 (첫 실행이면 null) */
    String html(StatusSummary s, List<StatusSnapshot.Change> changes, LocalDateTime at, String attachmentName) {
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-family:'Malgun Gothic','맑은 고딕',sans-serif;font-size:13px;color:#222\">");
        h.append("<h2 style=\"margin:0 0 4px\">").append(esc(title)).append("</h2>");
        h.append("<div style=\"color:#666;margin-bottom:16px\">기준일시: ").append(TS.format(at)).append("</div>");

        // 핵심 지표
        h.append("<table style=\"border-collapse:collapse;margin-bottom:16px\"><tr>");
        kpi(h, "전체 진척률", fmt(s.overallProgress()) + "%", null);
        kpi(h, "완료 기관", s.countCompleted() + " / " + s.agencies().size(), null);
        kpi(h, "진척률 " + warnThreshold + "% 미만", s.countBelow(warnThreshold) + "개 기관",
                s.countBelow(warnThreshold) > 0 ? RED : null);
        kpi(h, "완료 조건", s.total("O") + " / " + (s.total("O") + s.total("X")), null);
        h.append("</tr></table>");

        if (changes != null) {
            changes(h, changes);
        }

        // 진척률 구간별 기관 수 (엑셀 '전체 현황 요약' 표)
        if (!s.buckets().isEmpty()) {
            h.append("<h3 style=\"margin:8px 0\">■ 진척률 구간별 기관 수</h3>");
            h.append("<table style=\"border-collapse:collapse;margin-bottom:16px\"><tr>");
            for (StatusSummary.Bucket b : s.buckets()) {
                h.append("<th ").append(TH).append(">").append(esc(b.label())).append("</th>");
            }
            h.append("</tr><tr>");
            for (StatusSummary.Bucket b : s.buckets()) {
                h.append("<td style=\"").append(TD).append("\">").append(esc(b.value())).append("</td>");
            }
            h.append("</tr></table>");
        }

        // 기관별 · 전문별 완료 현황
        Map<String, List<Integer>> groups = s.conditionGroups();
        h.append("<h3 style=\"margin:8px 0\">■ 기관별 현황 <span style=\"font-weight:normal;font-size:12px;color:#666\">")
                .append("(전문별 완료 조건 수 / 대상 조건 수, '-' 는 해당 없음)</span></h3>");
        h.append("<table style=\"border-collapse:collapse;margin-bottom:16px\"><tr>");
        h.append("<th ").append(TH).append(">기관명</th><th ").append(TH).append(">진척률</th>");
        for (String code : groups.keySet()) {
            h.append("<th ").append(TH).append(">").append(esc(code)).append("</th>");
        }
        h.append("</tr>");
        for (StatusSummary.Agency a : s.agencies()) {
            boolean warn = a.progress() == null || a.progress() < warnThreshold;
            String color = warn ? "color:" + RED + ";font-weight:bold;" : "";
            h.append("<tr><td style=\"").append(TD).append(";text-align:left;").append(color).append("\">")
                    .append(esc(a.name())).append("</td>");
            h.append("<td style=\"").append(TD).append(";").append(color).append("\">")
                    .append(a.progress() == null ? "-" : fmt(a.progress()) + "%").append("</td>");
            for (List<Integer> idx : groups.values()) {
                long done = idx.stream().filter(i -> a.marks().get(i).equals("O")).count();
                long target = idx.stream().filter(i -> !a.marks().get(i).isEmpty()).count();
                String bg = target == 0 ? "#f2f2f2" : done == target ? "#e2efda" : done == 0 ? "#fce4e4" : "#fff2cc";
                h.append("<td style=\"").append(TD).append(";background:").append(bg).append("\">")
                        .append(target == 0 ? "-" : done + "/" + target).append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");

        h.append("<div style=\"color:#666\">상세 내용은 첨부파일(").append(esc(attachmentName))
                .append(")을 확인해 주세요.<br>본 메일은 자동 발송되었습니다.</div></div>");
        return h.toString();
    }

    /** 직전 실행 대비 새로 충족 / 충족 해제된 조건을 기관별로 묶어 보여 준다. */
    private static void changes(StringBuilder h, List<StatusSnapshot.Change> changes) {
        h.append("<h3 style=\"margin:8px 0\">■ 직전 발송 대비 변경</h3>");
        if (changes.isEmpty()) {
            h.append("<div style=\"margin-bottom:16px;color:#666\">변경 없음</div>");
            return;
        }
        Map<String, List<String>> done = new LinkedHashMap<>();
        Map<String, List<String>> undone = new LinkedHashMap<>();
        for (StatusSnapshot.Change c : changes) {
            String item = c.messageCode() + " " + c.condition();
            if (c.isNewlyDone()) {
                done.computeIfAbsent(c.agency(), k -> new ArrayList<>()).add(item);
            } else if (c.before().equals("O")) {
                undone.computeIfAbsent(c.agency(), k -> new ArrayList<>()).add(item);
            }
        }
        h.append("<table style=\"border-collapse:collapse;margin-bottom:16px\">");
        h.append("<tr><th ").append(TH).append(">기관명</th><th ").append(TH).append(">구분</th><th ")
                .append(TH).append(">조건</th></tr>");
        changeRows(h, done, "신규 충족", "#e2efda", "#222");
        changeRows(h, undone, "충족 해제", "#fce4e4", RED);
        if (done.isEmpty() && undone.isEmpty()) {
            h.append("<tr><td colspan=\"3\" style=\"").append(TD).append("\">충족 여부 변경 없음</td></tr>");
        }
        h.append("</table>");
    }

    private static void changeRows(StringBuilder h, Map<String, List<String>> items, String label, String bg, String color) {
        items.forEach((agency, list) -> h.append("<tr><td style=\"").append(TD).append(";text-align:left\">")
                .append(esc(agency)).append("</td><td style=\"").append(TD).append(";background:").append(bg)
                .append(";color:").append(color).append("\">").append(label).append("</td><td style=\"")
                .append(TD).append(";text-align:left\">").append(esc(String.join(", ", list))).append("</td></tr>"));
    }

    private static void kpi(StringBuilder h, String label, String value, String color) {
        h.append("<td style=\"border:1px solid #c0c0c0;padding:10px 18px;text-align:center\">")
                .append("<div style=\"color:#666;font-size:12px\">").append(esc(label)).append("</div>")
                .append("<div style=\"font-size:20px;font-weight:bold;").append(color == null ? "" : "color:" + color)
                .append("\">").append(esc(value)).append("</div></td>");
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
