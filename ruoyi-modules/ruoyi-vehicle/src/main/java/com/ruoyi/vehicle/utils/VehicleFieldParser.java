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
import java.util.stream.Collectors;

/**
 * 列表字段解析工具类
 * JSON 的 key 格式为 "20.98.12"，需匹配 sys_dict_data.keyMap 字段
 * 通过判断 value 是否为 List 类型来识别列表字段
 */
public class VehicleFieldParser {

    private static final Logger log = LoggerFactory.getLogger(VehicleFieldParser.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 JSON Map 中解析所有列表字段
     *
     * @param jsonMap           已解析的 JSON Map（key 格式为 "20.98.12"）
     * @param remoteDictService 字典服务，一次性查询 vehicle_attribute 类型所有字典数据
     * @return key=dict_label（字段名）, value=列表内容
     */
    @SuppressWarnings("unchecked")
    public static Map<String, List<Map<String, Object>>> parseListFieldsFromMap(
            Map<String, Object> jsonMap,
            RemoteDictService remoteDictService) {

        Map<String, List<Map<String, Object>>> result = new HashMap<>();

        if (jsonMap == null || jsonMap.isEmpty()) {
            return result;
        }

        // ✅ 第一步：一次性查询所有字典数据，构建 keyMap → SysDictData 的本地映射表
        Map<String, SysDictData> keyMapIndex = buildKeyMapIndex(remoteDictService);

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

            // ✅ 直接用完整 key 匹配 keyMapIndex
            String listKey = resolveDictLabel(key, keyMapIndex);

            // 解析列表中每个元素（每个元素也是一个 Map）
            List<Map<String, Object>> parsedList = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map) {
                    Map<String, Object> convertedItem = convertItemKeys(
                            (Map<String, Object>) item, keyMapIndex);
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
     * 将列表元素中的 key 通过 keyMapIndex 转换为 dict_label
     * 例如: {"20.98.12": "Y"} → {"BrakedAxleIndicator": "Y"}
     *
     * @param item        列表元素 Map
     * @param keyMapIndex 本地字典索引（keyMap → SysDictData）
     */
    private static Map<String, Object> convertItemKeys(
            Map<String, Object> item,
            Map<String, SysDictData> keyMapIndex) {

        Map<String, Object> converted = new HashMap<>();

        for (Map.Entry<String, Object> entry : item.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // ✅ 用完整 key 匹配 keyMapIndex
            String fieldName = resolveDictLabel(key, keyMapIndex);
            converted.put(fieldName, value);
        }

        return converted;
    }

    /**
     * 构建 keyMap → SysDictData 的本地索引
     * 一次性查询所有 vehicle_attribute 类型字典数据，避免循环内频繁 RPC 调用
     *
     * @param remoteDictService 字典远程服务
     * @return keyMap 到 SysDictData 的映射 Map
     */
    private static Map<String, SysDictData> buildKeyMapIndex(RemoteDictService remoteDictService) {
        try {
            List<SysDictData> allDictData = remoteDictService
                    .getDictDataByType("vehicle_attribute")
                    .getData();

            if (allDictData == null || allDictData.isEmpty()) {
                log.warn("vehicle_attribute 字典数据为空");
                return new HashMap<>();
            }

            // ✅ 以 keyMap 字段为 key 建立索引（过滤 keyMap 为空的记录）
            return allDictData.stream()
                    .filter(d -> d.getKeyMap() != null && !d.getKeyMap().isEmpty())
                    .collect(Collectors.toMap(
                            SysDictData::getKeyMap,
                            d -> d,
                            (existing, duplicate) -> {
                                log.warn("存在重复 keyMap: {}", existing.getKeyMap());
                                return existing; // 保留第一个
                            }
                    ));

        } catch (Exception e) {
            log.error("查询 vehicle_attribute 字典数据失败", e);
            return new HashMap<>();
        }
    }

    /**
     * 根据 JSON key 从 keyMapIndex 中解析 dict_label
     * 若找不到则返回原始 key 作为兜底
     *
     * @param key         JSON 原始 key（如 "20.98.12"）
     * @param keyMapIndex 本地字典索引
     * @return dict_label 或原始 key
     */
    private static String resolveDictLabel(String key, Map<String, SysDictData> keyMapIndex) {
        SysDictData dictData = keyMapIndex.get(key);
        if (dictData != null && dictData.getDictLabel() != null) {
            return dictData.getDictLabel();
        }
        log.warn("未找到 keyMap 对应字典数据，使用原始 key 兜底: {}", key);
        return key;
    }
}