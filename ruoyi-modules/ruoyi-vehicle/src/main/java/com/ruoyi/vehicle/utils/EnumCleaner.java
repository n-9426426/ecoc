package com.ruoyi.vehicle.utils;

import com.ruoyi.common.core.utils.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class EnumCleaner {

    private static final Map<String, String> LOCATION_MARKING_MAP = new HashMap<>();

    static {
        LOCATION_MARKING_MAP.put("STAT", "S");
        LOCATION_MARKING_MAP.put("BPILR", "R");
        LOCATION_MARKING_MAP.put("RIGHTSIDE", "RS");
        LOCATION_MARKING_MAP.put("MIDDLE", "M");
    }

    public static String clean(String dictLabel, String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }

        if (dictLabel.startsWith("LocationMarkings")) {
            String[] parts = value.split(";");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                String trimmed = part.trim();
                if (StringUtils.isNotBlank(trimmed)) {
                    String mapped = LOCATION_MARKING_MAP.get(trimmed);
                    sb.append(mapped == null ? trimmed : mapped).append(";");
                }
            }
            if (sb.length() > 0) {
                sb.deleteCharAt(sb.length() - 1);
            }
            return sb.toString();
        }
        return value;
    }
}
