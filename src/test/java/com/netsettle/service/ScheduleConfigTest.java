package com.netsettle.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** application.yml에 적힌 기본 크론이 의도한 시각(평일 16:30 마감, 평일 11:00 결제)에 도는지 확인합니다. */
class ScheduleConfigTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Properties config = load();

    @Test
    void cutoffFiresAt1630OnWeekdaysOnly() {
        CronExpression cron = CronExpression.parse(config.getProperty("netsettle.cutoff-cron"));
        // 금요일 오후 → 같은 날 16:30
        assertThat(cron.next(at(2026, 10, 9, 13, 0))).isEqualTo(at(2026, 10, 9, 16, 30));
        // 금요일 마감 직후 → 주말을 건너뛴 월요일 16:30
        assertThat(cron.next(at(2026, 10, 9, 16, 30))).isEqualTo(at(2026, 10, 12, 16, 30));
    }

    @Test
    void settlementFiresAt1100OnWeekdaysOnly() {
        CronExpression cron = CronExpression.parse(config.getProperty("netsettle.settlement-cron"));
        // 금요일 마감분 → 다음 영업일인 월요일 11:00에 결제
        assertThat(cron.next(at(2026, 10, 9, 16, 30))).isEqualTo(at(2026, 10, 12, 11, 0));
    }

    private static ZonedDateTime at(int y, int m, int d, int hh, int mm) {
        return ZonedDateTime.of(y, m, d, hh, mm, 0, 0, SEOUL);
    }

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }
}
