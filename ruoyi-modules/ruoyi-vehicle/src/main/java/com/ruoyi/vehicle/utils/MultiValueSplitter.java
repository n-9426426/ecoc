package com.ruoyi.vehicle.utils;

import com.ruoyi.common.core.utils.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class MultiValueSplitter {

    public static String clean(Object value) {
        if (value == null) {
            return "";
        }

        String str = value.toString();
        if (!str.contains(";")) {
            return str;
        }

        String[] arr = str.split(";");
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            String trimmed = s.trim();
            if (StringUtils.isNotBlank(trimmed)) {
                sb.append(trimmed).append(";");
            }
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
