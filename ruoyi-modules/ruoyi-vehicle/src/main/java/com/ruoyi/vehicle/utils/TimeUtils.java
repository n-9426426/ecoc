package com.ruoyi.vehicle.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class TimeUtils {

    /**
     * 获取当前时间与传入时间的差值描述
     * - 同一小时内：返回分钟数
     * - 同一天内：返回小时数
     * - 同一年内：返回天数
     */
    public static String getDateDiffDesc(Date afterDate, Date beforeDate) {
        LocalDateTime after = afterDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        LocalDateTime before = beforeDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        // 同一小时：精确到分钟
        if (after.getYear() == before.getYear()
                && after.getDayOfYear() == before.getDayOfYear()
                && after.getHour() == before.getHour()) {
            long minutes = ChronoUnit.MINUTES.between(after, before);
            return minutes + ChronoUnit.MINUTES.name();
        }

        // 同一天：精确到小时
        if (after.getYear() == before.getYear()
                && after.getDayOfYear() == before.getDayOfYear()) {
            long hours = ChronoUnit.HOURS.between(after, before);
            return hours + ChronoUnit.HOURS.name();
        }

        // 同一年：精确到天
        if (after.getYear() == before.getYear()) {
            long days = ChronoUnit.DAYS.between(after.toLocalDate(), before.toLocalDate());
            return days + ChronoUnit.DAYS.name();
        }

        // 跨年兜底
        long days = ChronoUnit.DAYS.between(after.toLocalDate(), before.toLocalDate());
        return days + ChronoUnit.DAYS.name();
    }

    /**
     * 计算两个时间的差值，拆分为 [天, 小时, 分钟, 秒] 各维度
     * 只返回非零的部分，不足1秒按1秒算
     *
     * @param startDate 开始时间
     * @param endDate   结束时间
     * @return [[数值, ChronoUnit], ...] 例如 [[192, DAYS], [9, HOURS], [23, MINUTES], [17, SECONDS]]
     */
    public static Object[][] getDateDiffParts(Date startDate, Date endDate) {
        // 取绝对差值（毫秒），不足1秒按1秒算
        long diffMillis = Math.abs(endDate.getTime() - startDate.getTime());
        long totalSeconds = diffMillis / 1000;
        if (diffMillis % 1000 > 0) {
            totalSeconds += 1;
        }

        // 按从大到小拆分
        long days    = totalSeconds / (24 * 3600);
        long remain  = totalSeconds % (24 * 3600);
        long hours   = remain / 3600;
        remain       = remain % 3600;
        long minutes = remain / 60;
        long seconds = remain % 60;

        // 只收集非零部分
        List<Object[]> result = new ArrayList<>();
        if (days > 0)    result.add(new Object[]{days,    ChronoUnit.DAYS});
        if (hours > 0)   result.add(new Object[]{hours,   ChronoUnit.HOURS});
        if (minutes > 0) result.add(new Object[]{minutes, ChronoUnit.MINUTES});
        if (seconds > 0) result.add(new Object[]{seconds, ChronoUnit.SECONDS});

        // 差值为0时兜底返回 [[0, SECONDS]]
        if (result.isEmpty()) {
            result.add(new Object[]{0L, ChronoUnit.SECONDS});
        }

        return result.toArray(new Object[0][]);
    }
}
