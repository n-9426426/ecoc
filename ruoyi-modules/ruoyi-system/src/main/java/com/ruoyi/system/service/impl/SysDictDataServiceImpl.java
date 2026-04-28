package com.ruoyi.system.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.security.utils.DictUtils;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.service.ISysDictDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 字典 业务层处理
 */
@Service
public class SysDictDataServiceImpl implements ISysDictDataService {

    private static final String VEHICLE_ATTRIBUTE = "vehicle_attribute";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private SysDictDataMapper dictDataMapper;

    // ----------------------------------------------------------------
    // 查询：列表（vehicle_attribute 类型聚合还原JSON）
    // ----------------------------------------------------------------
    @Override
    public List<SysDictData> selectDictDataList(SysDictData dictData) {
        if (isVehicleAttributeQuery(dictData)) {
            // 获取 PageHelper 设置的分页参数
            Page<Object> page = PageHelper.getLocalPage();
            // 清除 PageHelper 线程变量，防止它拦截后续 SQL
            PageHelper.clearPage();

            long pageNum  = page != null ? page.getPageNum()  : 1;
            long pageSize = page != null ? page.getPageSize() : 10;
            long offset   = (pageNum - 1) * pageSize;

            // 手动查总数
            long total = dictDataMapper.countDictDataListAggregated(dictData);

            // 手动分页查询
            List<SysDictData> aggList = dictDataMapper.selectDictDataListAggregated(
                    dictData, offset, (int) pageSize);

            // 还原 JSON 字段
            List<SysDictData> result = convertAggFields(aggList);

            // 构造 PageInfo 让若依框架正确返回分页信息
            Page<SysDictData> resultPage = new Page<>((int) pageNum, (int) pageSize);
            resultPage.setTotal(total);
            resultPage.addAll(result);
            return resultPage;
        }
        return dictDataMapper.selectDictDataList(dictData);
    }

    // ----------------------------------------------------------------
    // 查询：单条（vehicle_attribute 类型聚合还原JSON后返回）
    // ----------------------------------------------------------------
    @Override
    public SysDictData selectDictDataById(Long dictCode) {
        SysDictData row = dictDataMapper.selectDictDataById(dictCode);
        if (row == null) {
            return null;
        }
        if (VEHICLE_ATTRIBUTE.equals(row.getDictType())) {
            List<SysDictData> siblings = dictDataMapper.selectSiblingRows(row.getUuid());
            List<SysDictData> aggregated = aggregateToJsonList(siblings);
            if (!aggregated.isEmpty()) {
                SysDictData result = aggregated.get(0);
                result.setDictCode(dictCode);
                result.setUuid(row.getUuid());
                return result;
            }
        }
        return row;
    }

    // ----------------------------------------------------------------
    // 新增（vehicle_attribute 类型拆分多行插入）
    // ----------------------------------------------------------------
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertDictData(SysDictData data) {
        if (VEHICLE_ATTRIBUTE.equals(data.getDictType())) {
            List<SysDictData> rows = splitToRows(data);
            int count = 0;
            for (SysDictData row : rows) {
                count += dictDataMapper.insertDictData(row);
            }
            refreshCache(data.getDictType());
            return count;
        }
        int row = dictDataMapper.insertDictData(data);
        if (row > 0) {
            refreshCache(data.getDictType());
        }
        return row;
    }

    // ----------------------------------------------------------------
    // 修改（vehicle_attribute 类型：删除同组旧行，重新插入拆分行）
    // ----------------------------------------------------------------
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateDictData(SysDictData data) {
        if (VEHICLE_ATTRIBUTE.equals(data.getDictType())) {
            SysDictData current = dictDataMapper.selectDictDataById(data.getDictCode());
            if (current == null) {
                throw new ServiceException("字典数据不存在，dictCode=" + data.getDictCode());
            }
            // 按uuid找同组所有旧行删除
            List<SysDictData> oldRows = dictDataMapper.selectSiblingRows(current.getUuid());
            for (SysDictData old : oldRows) {
                dictDataMapper.deleteDictDataById(old.getDictCode());
            }
            // 复用原uuid，保证前端持有的uuid不失效
            data.setUuid(current.getUuid());
            List<SysDictData> newRows = splitToRows(data);
            int count = 0;
            for (SysDictData row : newRows) {
                count += dictDataMapper.insertDictData(row);
            }
            refreshCache(data.getDictType());
            return count;
        }

        int row = dictDataMapper.updateDictData(data);
        if (row > 0) {
            refreshCache(data.getDictType());
        }
        return row;
    }

    // ----------------------------------------------------------------
    // 删除（单行按 dictCode 删除，vehicle_attribute 同样支持单行删除）
    // ----------------------------------------------------------------
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictDataByIds(Long[] dictCodes) {
        for (Long dictCode : dictCodes) {
            // 先查出当前行
            SysDictData data = dictDataMapper.selectDictDataById(dictCode);
            if (data == null) {
                continue;
            }

            // vehicle_attribute 类型：按 uuid 找同组所有行，逐一校验引用后删除
            if (VEHICLE_ATTRIBUTE.equals(data.getDictType()) && StringUtils.isNotBlank(data.getUuid())) {
                List<SysDictData> siblings = dictDataMapper.selectSiblingRows(data.getUuid());
                for (SysDictData sibling : siblings) {
                    int refCount = dictDataMapper.countVehicleTemplateAttributeByDictCode(sibling.getDictCode());
                    if (refCount > 0) {
                        throw new ServiceException(String.format(
                                "字典数据【%s】已被模板属性引用，无法删除！", sibling.getDictLabel()));
                    }
                }
                for (SysDictData sibling : siblings) {
                    dictDataMapper.deleteDictDataById(sibling.getDictCode());
                }
            } else {
                // 非 vehicle_attribute 类型：单行校验删除
                int refCount = dictDataMapper.countVehicleTemplateAttributeByDictCode(dictCode);
                if (refCount > 0) {
                    throw new ServiceException(String.format(
                            "字典数据【%s】已被模板属性引用，无法删除！", data.getDictLabel()));
                }
                dictDataMapper.deleteDictDataById(dictCode);
            }

            refreshCache(data.getDictType());
        }
    }

    // ----------------------------------------------------------------
    // 其他原有方法
    // ----------------------------------------------------------------
    @Override
    public String selectDictLabel(String dictType, String dictValue) {
        return dictDataMapper.selectDictLabel(dictType, dictValue);
    }

    // ================================================================
    // 私有工具方法
    // ================================================================

    /**
     * 判断是否是 vehicle_attribute 的查询（需要聚合）
     */
    private boolean isVehicleAttributeQuery(SysDictData dictData) {
        return VEHICLE_ATTRIBUTE.equals(dictData.getDictType())
                || (dictData.getDictType() == null && Boolean.TRUE.equals(dictData.getVehicle()));
    }

    /**
     * 将前端传入的 JSON 格式 keyMap/valueMap 拆分为多行 SysDictData
     *
     * keyMap  JSON: {"原系统1":"原值1", "原系统2":"原值2"}
     * valueMap JSON: {"原值1":"新值1", "原值2":"新值2"}
     *
     * 拆分后每行：
     *   original_system = "原系统N"
     *   key_map         = "原值N"          （单个原值字符串）
     *   value_map       = {"原值N":"新值N"} （只含当前行的键值对）
     */
    private List<SysDictData> splitToRows(SysDictData data) {
        try {
            // 新增/修改时生成uuid，修改时复用原uuid
            String groupUuid = StringUtils.isNotBlank(data.getUuid())
                    ? data.getUuid()
                    : java.util.UUID.randomUUID().toString().replace("-", "");

            List<Map.Entry<String, String>> keyMapEntries = Collections.emptyList();
            if (StringUtils.isNotBlank(data.getKeyMap())) {
                Map<String, String> keyMapObj = MAPPER.readValue(
                        data.getKeyMap(), new TypeReference<LinkedHashMap<String, String>>() {});
                keyMapEntries = new ArrayList<>(keyMapObj.entrySet());
            }

            List<Map.Entry<String, String>> valueMapEntries = Collections.emptyList();
            if (StringUtils.isNotBlank(data.getValueMap())) {
                Map<String, String> valueMapObj = MAPPER.readValue(
                        data.getValueMap(), new TypeReference<LinkedHashMap<String, String>>() {});
                valueMapEntries = new ArrayList<>(valueMapObj.entrySet());
            }

            int rowCount = Math.max(keyMapEntries.size(), valueMapEntries.size());
            if (rowCount == 0) {
                SysDictData row = copyBaseFields(data);
                row.setDictCode(null);
                row.setUuid(groupUuid);
                return Collections.singletonList(row);
            }

            List<SysDictData> rows = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                SysDictData row = copyBaseFields(data);
                row.setDictCode(null);
                row.setUuid(groupUuid); // 同组共享同一uuid

                if (i < keyMapEntries.size()) {
                    row.setOriginalSystem(keyMapEntries.get(i).getKey());
                    row.setKeyMap(keyMapEntries.get(i).getValue());
                } else {
                    row.setOriginalSystem(null);
                    row.setKeyMap(null);
                }

                if (i < valueMapEntries.size()) {
                    Map.Entry<String, String> valueEntry = valueMapEntries.get(i);
                    Map<String, String> singleValueMap = new LinkedHashMap<>();
                    singleValueMap.put(valueEntry.getKey(), valueEntry.getValue());
                    row.setValueMap(MAPPER.writeValueAsString(singleValueMap));
                } else {
                    row.setValueMap(null);
                }

                rows.add(row);
            }
            return rows;
        } catch (Exception e) {
            throw new ServiceException("keyMap/valueMap JSON 格式错误：" + e.getMessage());
        }
    }

    /**
     * 将多行原始数据按 dictLabel+dictType+dictTypeAffiliation 聚合，
     * 还原 keyMap 为 {"原系统":"原值",...}，valueMap 为 {"原值":"新值",...}
     */
    private List<SysDictData> aggregateToJsonList(List<SysDictData> rawList) {
        // 按 dictLabel + dictTypeAffiliation 分组（dictType 固定 vehicle_attribute）
        Map<String, List<SysDictData>> grouped = rawList.stream().collect(
                Collectors.groupingBy(
                        r -> r.getDictLabel() + "$$" + r.getDictTypeAffiliation(),
                        LinkedHashMap::new,
                        Collectors.toList()
                )
        );

        List<SysDictData> result = new ArrayList<>();
        for (List<SysDictData> group : grouped.values()) {
            try {
                Map<String, String> keyMapObj   = new LinkedHashMap<>();
                Map<String, String> valueMapObj = new LinkedHashMap<>();

                for (SysDictData row : group) {
                    // key_map 字段存的是原值字符串，original_system 存的是原系统名
                    if (row.getOriginalSystem() != null && row.getKeyMap() != null) {
                        keyMapObj.put(row.getOriginalSystem(), row.getKeyMap());
                    }
                    // value_map 字段存的是 {"原值":"新值"} 单条JSON，合并到总 valueMap
                    if (row.getValueMap() != null && !StringUtils.isBlank(row.getValueMap())) {
                        Map<String, String> singleMap = MAPPER.readValue(
                                row.getValueMap(), new TypeReference<LinkedHashMap<String, String>>() {});
                        valueMapObj.putAll(singleMap);
                    }
                }

                // 以组内第一行为基准构建聚合对象
                SysDictData agg = copyBaseFields(group.get(0));
                agg.setDictCode(group.get(0).getDictCode()); // 取第一行的code（前端编辑时用）
                agg.setKeyMap(MAPPER.writeValueAsString(keyMapObj));
                agg.setValueMap(MAPPER.writeValueAsString(valueMapObj));
                agg.setOriginalSystem(null); // 聚合后不需要单独的originalSystem
                result.add(agg);
            } catch (Exception e) {
                // JSON 解析失败时原样返回第一行，不影响其他数据
                result.add(group.get(0));
            }
        }
        return result;
    }

    /**
     * 复制基础字段（不含 dictCode、keyMap、valueMap、originalSystem）
     */
    private SysDictData copyBaseFields(SysDictData src) {
        SysDictData dest = new SysDictData();
        dest.setDictSort(src.getDictSort());
        dest.setDictLabel(src.getDictLabel());
        dest.setDictValue(src.getDictValue());
        dest.setDictType(src.getDictType());
        dest.setCssClass(src.getCssClass());
        dest.setRule(src.getRule());
        dest.setRangeRule(src.getRangeRule());
        dest.setIsDefault(src.getIsDefault());
        dest.setStatus(src.getStatus());
        dest.setRemark(src.getRemark());
        dest.setDictTypeAffiliation(src.getDictTypeAffiliation());
        dest.setRuleType(src.getRuleType());
        dest.setCreateBy(src.getCreateBy());
        dest.setUpdateBy(src.getUpdateBy());
        // 聚合展示字段
        dest.setDictTypeStr(src.getDictTypeStr());
        dest.setDictTypeAffiliationStr(src.getDictTypeAffiliationStr());
        dest.setUuid(src.getUuid());
        return dest;
    }

    private void refreshCache(String dictType) {
        List<SysDictData> dictDatas = dictDataMapper.selectDictDataByType(dictType);
        DictUtils.setDictCache(dictType, dictDatas);
    }

    /**
     * 将 SQL GROUP_CONCAT 聚合结果转换为标准 JSON 字符串
     *
     * key_map_agg   格式: "原系统1|原值1|||原系统2|原值2"
     * value_map_agg 格式: '"key1":"val1","key2":"val2"'  (各行{}内容用逗号拼接)
     */
    private List<SysDictData> convertAggFields(List<SysDictData> aggList) {
        for (SysDictData agg : aggList) {

            // ---- 还原 keyMap ----
            // SQL返回格式: [{"system":"原系统1","key":"原值1"}, null, ...]
            String keyMapAgg = agg.getKeyMap();
            if (StringUtils.isNotBlank(keyMapAgg)) {
                try {
                    List<Map<String, String>> keyMapArr = MAPPER.readValue(
                            keyMapAgg, new TypeReference<List<Map<String, String>>>() {});
                    Map<String, String> keyMapObj = new LinkedHashMap<>();
                    for (Map<String, String> item : keyMapArr) {
                        if (item == null) continue; // CASE WHEN 产生的 null 元素
                        String sys = item.get("system");
                        String key = item.get("key");
                        if (StringUtils.isNotBlank(sys) && StringUtils.isNotBlank(key)) {
                            keyMapObj.put(sys, key);
                        }
                    }
                    agg.setKeyMap(MAPPER.writeValueAsString(keyMapObj));
                } catch (Exception e) {
                    agg.setKeyMap("{}");
                }
            } else {
                agg.setKeyMap("{}");
            }

            // ---- 还原 valueMap ----
            // SQL返回格式: ["{\"k1\":\"v1\"}", null, "{\"k2\":\"v2\"}", ...]
            String valueMapAgg = agg.getValueMap();
            if (StringUtils.isNotBlank(valueMapAgg)) {
                try {
                    // JSON_ARRAYAGG 返回的是 JSON 数组，元素是字符串或 null
                    List<String> valueMapArr = MAPPER.readValue(
                            valueMapAgg, new TypeReference<List<String>>() {});
                    Map<String, String> valueMapObj = new LinkedHashMap<>();
                    for (String singleJson : valueMapArr) {
                        if (StringUtils.isBlank(singleJson)) continue; // 跳过 null 元素
                        Map<String, String> singleMap = MAPPER.readValue(
                                singleJson, new TypeReference<LinkedHashMap<String, String>>() {});
                        valueMapObj.putAll(singleMap);
                    }
                    agg.setValueMap(MAPPER.writeValueAsString(valueMapObj));
                } catch (Exception e) {
                    agg.setValueMap("{}");
                }
            } else {
                agg.setValueMap("{}");
            }
        }
        return aggList;
    }
}