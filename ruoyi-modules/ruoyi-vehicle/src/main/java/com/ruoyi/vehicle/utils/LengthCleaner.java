package com.ruoyi.vehicle.utils;

import com.ruoyi.common.core.utils.StringUtils;

public class LengthCleaner {

    public static String clean(String value, int maxLength) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}
