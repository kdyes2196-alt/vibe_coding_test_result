package com.testreport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 엑셀에서 저장한 CSV(UTF-8 또는 MS949)를 읽는 간단한 유틸. */
final class CsvUtil {

    private CsvUtil() {
    }

    /** 빈 줄과 '#' 으로 시작하는 줄을 제외한 모든 행(헤더 포함)을 반환한다. */
    static List<String[]> read(Path file) throws IOException {
        String text = decode(Files.readAllBytes(file));
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            rows.add(parseLine(line));
        }
        return rows;
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 한글 윈도우 엑셀의 기본 CSV 인코딩
            return new String(bytes, Charset.forName("MS949"));
        }
    }

    private static String[] parseLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells.toArray(new String[0]);
    }
}
