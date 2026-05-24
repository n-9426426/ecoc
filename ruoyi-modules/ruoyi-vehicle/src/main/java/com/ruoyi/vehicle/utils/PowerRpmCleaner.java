package com.ruoyi.vehicle.utils;

import com.ruoyi.common.core.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;

public class PowerRpmCleaner {

    public static String clean(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }

        String[] parts = value.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                int power = Integer.parseInt(parts[0]);
                int rpm = Integer.parseInt(parts[1]);
                return power + " " + rpm;
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }
}
