package com.ruoyi.vehicle.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表字段解析工具类
 * JSON 的 key 格式为 "20.98.12"，最后一位是 dict_code
 * 通过判断 value 是否为 List 类型来识别列表字段
 */
public class VehicleFieldParser {

    private static final Logger log = LoggerFactory.getLogger(VehicleFieldParser.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 JSON 字符串中解析所有列表字段
     *
     * @param jsonMap   已解析的 JSON Map（key 格式为 "20.98.12"）
     * @param remoteDictService 字典表服务，用于通过 dict_code 查字段名
     * @return key=dict_label（字段名）, value=列表内容
     */
    @SuppressWarnings("unchecked")
    public static Map<String, List<Map<String, Object>>> parseListFieldsFromMap(Map<String, Object> jsonMap, RemoteDictService remoteDictService) {

        Map<String, List<Map<String, Object>>> result = new HashMap<>();

        if (jsonMap == null || jsonMap.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 只处理 value 是 List 的字段
            if (!(value instanceof List)) {
                continue;
            }

            List<?> rawList = (List<?>) value;
            if (rawList.isEmpty()) {
                continue;
            }

            // 提取 dict_code（key 最后一段数字）
            Long dictCode = extractDictCode(key);
            if (dictCode == null) {
                log.warn("无法解析 dict_code, key={}", key);
                continue;
            }

            // 查字典表获取字段名（dict_label）
            SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
            String listKey = (dictData != null && dictData.getDictLabel() != null)
                    ? dictData.getDictLabel()
                    : key; // 查不到时用原始 key 兜底

            // 解析列表中每个元素（每个元素也是一个 Map）
            List<Map<String, Object>> parsedList = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map) {
                    // 列表元素的 key 同样是 dict_code 格式，转换为 dict_label
                    Map<String, Object> convertedItem = convertItemKeys((Map<String, Object>) item, remoteDictService);
                    parsedList.add(convertedItem);
                }
            }

            if (!parsedList.isEmpty()) {
                result.put(listKey, parsedList);
                log.debug("解析列表字段: listKey={}, size={}", listKey, parsedList.size());
            }
        }

        return result;
    }

    /**
     * 将列表元素中的 key（dict_code 格式）转换为 dict_label
     * 例如: {"20.98.12": "Y"} → {"BrakedAxleIndicator": "Y"}
     */
    private static Map<String, Object> convertItemKeys(Map<String, Object> item, RemoteDictService remoteDictService) {

        Map<String, Object> converted = new HashMap<>();

        for (Map.Entry<String, Object> entry : item.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            Long dictCode = extractDictCode(key);
            if (dictCode == null) {
                converted.put(key, value);
                continue;
            }

            SysDictData dictData = remoteDictService.getDataByDictCode(dictCode).getData();
            String fieldName = (dictData != null && dictData.getDictLabel() != null)
                    ? dictData.getDictLabel()
                    : key;

            converted.put(fieldName, value);
        }

        return converted;
    }

    /**
     * 从 key 中提取最后一段数字作为 dict_code
     * 例如: "20.98.12" → 12
     */
    private static Long extractDictCode(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] parts = key.split("\\.");
        try {
            return Long.parseLong(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}