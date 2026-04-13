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
    public Map<String, Object> convertJsonKeysToDictLabel(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }

        try {
            // 使用 LinkedHashMap 保持顺序
            Map<String, Object> originalMap = MAPPER.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {}
            );

            Map<String, Object> resultMap = new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                Long dictCode = extractLastIdFromKey(key);
                String dictLabel = null;

                if (dictCode != null) {
                    dictLabel = getDictLabelByCode(dictCode);
                }

                String newKey = StringUtils.isNotBlank(dictLabel) ? dictLabel : key;
                resultMap.put(newKey, value);
            }

            return resultMap;
        } catch (IOException e) {
            throw new RuntimeException("解析 JSON 失败: " + json, e);
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