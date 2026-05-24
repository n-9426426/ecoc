package com.ruoyi.vehicle.utils;

import com.ruoyi.common.core.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;

public class AddressCleaner {

    public static String clean(String address) {
        if (StringUtils.isBlank(address)) {
            return "";
        }

        String[] lines = address.split(",");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (StringUtils.isNotBlank(trimmed)) {
                sb.append(trimmed).append(", ");
            }
        }

        String result = sb.toString();
        if (result.endsWith(", ")) {
            result = result.substring(0, result.length() - 2);
        }

        return LengthCleaner.clean(result, 80);
    }
}
