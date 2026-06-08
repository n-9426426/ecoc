package com.ruoyi.vehicle.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JsonDictConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RemoteDictService remoteDictService;

    /**
     * 将 JSON 字符串的 key 转换为 dict_label，保持原始顺序
     */
    public Map<String, Object> convertJsonToMap(String json) {
        // 如果没有转换过，返回原始 JSON 解析结果（使用 LinkedHashMap 保持顺序）
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    private Long extractLastIdFromKey(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }

        int lastDotIndex = key.lastIndexOf('.');
        String idStr = (lastDotIndex == -1) ? key : key.substring(lastDotIndex + 1);

        try {
            return Long.parseLong(idStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getDictLabelByCode(Long dictCode) {
        try {
            SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
            return dictData != null ? dictData.getDictLabel() : null;
        } catch (Exception e) {
            return null;
        }
    }
}