package com.testreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleTest {

    @TempDir
    Path dir;

    @Test
    void 다음_실행시각은_같은날_다음_시각_또는_다음_평일이다() throws Exception {
        App app = new App(TestFixtures.config(dir, 0,
                "schedule.times=18:00,09:00", "schedule.weekdaysOnly=true"), false, false);

        // 2026-10-22(목) 10:00 → 같은 날 18:00
        assertEquals(LocalDateTime.of(2026, 10, 22, 18, 0), app.nextRun(LocalDateTime.of(2026, 10, 22, 10, 0)));
        // 2026-10-23(금) 18:30 → 주말 건너뛰고 10-26(월) 09:00
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), app.nextRun(LocalDateTime.of(2026, 10, 23, 18, 30)));
    }
}
