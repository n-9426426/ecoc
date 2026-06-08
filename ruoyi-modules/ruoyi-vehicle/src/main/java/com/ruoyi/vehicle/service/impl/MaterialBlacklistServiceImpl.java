package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.MaterialBlacklist;
import com.ruoyi.vehicle.mapper.MaterialBlacklistMapper;
import com.ruoyi.vehicle.service.IMaterialBlacklistService;
import com.ruoyi.vehicle.service.IMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 物料黑名单 Service 实现
 */
@Service
@RequiredArgsConstructor
public class MaterialBlacklistServiceImpl implements IMaterialBlacklistService {

    private final MaterialBlacklistMapper materialBlacklistMapper;

    @Autowired
    private IMaterialService materialService;

    @Override
    public List<MaterialBlacklist> selectMaterialBlacklistList(MaterialBlacklist materialBlacklist) {
        return materialBlacklistMapper.selectMaterialBlacklistList(materialBlacklist);
    }

    @Override
    public MaterialBlacklist selectMaterialBlacklistById(Long id) {
        return materialBlacklistMapper.selectMaterialBlacklistById(id);
    }

    @Override
    public int insertMaterialBlacklist(MaterialBlacklist materialBlacklist) {
        MaterialBlacklist exist = selectMaterialBlacklistById(materialBlacklist.getId());
        if (exist != null) {
            throw new RuntimeException("该物料号已存在");
        }
        materialBlacklist.setCreateBy(SecurityUtils.getUsername());
        materialBlacklist.setCreateTime(DateUtils.getNowDate());
        return materialBlacklistMapper.insertMaterialBlacklist(materialBlacklist);
    }

    @Override
    public int updateMaterialBlacklist(MaterialBlacklist materialBlacklist) {
        MaterialBlacklist exist = selectMaterialBlacklistById(materialBlacklist.getId());
        if (exist == null) {
            throw new RuntimeException("该记录不存在");
        }
        materialBlacklist.setUpdateBy(SecurityUtils.getUsername());
        materialBlacklist.setUpdateTime(DateUtils.getNowDate());
        return materialBlacklistMapper.updateMaterialBlacklist(materialBlacklist);
    }

    @Override
    public int deleteMaterialBlacklistByIds(Long[] ids) {
        return materialBlacklistMapper.deleteMaterialBlacklistByIds(ids);
    }

    @Override
    public MaterialBlacklist selectMaterialBlacklistByMaterialNo(String materialNo) {
        return materialBlacklistMapper.selectMaterialBlacklistByMaterialNo(materialNo);
    }

    @Override
    public MaterialBlacklist updateMaterialBlacklistStatus(Long id) {
        MaterialBlacklist exist = selectMaterialBlacklistById(id);
        if (exist == null) {
            throw new RuntimeException("该记录不存在");
        }
        int status = exist.getStatus() == 0 ? 1 : 0;
        MaterialBlacklist update = new MaterialBlacklist();
        update.setId(id);
        update.setStatus(status);
        update.setUpdateBy(SecurityUtils.getUsername());
        materialBlacklistMapper.updateMaterialBlacklistStatus(update);
        return update;
    }

    @Override
    public Map<String, Object> removeToMaterial(Long[] ids) {
        int count = ids.length;
        if (count == 0) {
            throw new RuntimeException("选择的数据为空");
        }

        int fail = 0;
        int success = 0;
        // key: materialNo, value: 失败原因
        Map<String, String> failReasons = new LinkedHashMap<>();
        List<Long> successIds = new LinkedList<>();

        for (Long id : ids) {
            try {
                MaterialBlacklist materialBlacklist = materialBlacklistMapper.selectMaterialBlacklistById(id);
                if (materialBlacklist == null) {
                    fail++;
                    failReasons.put("id=" + id, "黑名单记录不存在");
                    continue;
                }
                if (materialBlacklist.getStatus() == 1) {
                    fail++;
                    failReasons.put(materialBlacklist.getMaterialNo(), "物料状态异常，不可移除");
                    continue;
                }

                Material material = new Material();
                material.setMaterialNo(materialBlacklist.getMaterialNo());
                material.setBrand(materialBlacklist.getBrand());

                int row = materialService.insertMaterial(material);
                if (row == 0) {
                    fail++;
                    failReasons.put(material.getMaterialNo(), "插入物料表失败");
                } else {
                    success++;
                    successIds.add(id);
                }
            } catch (DuplicateKeyException e) {
                fail++;
                failReasons.put("id=" + id, "物料编号已存在，重复插入");
            } catch (Exception e) {
                fail++;
                failReasons.put("id=" + id, "系统异常：" + e.getMessage());
            }
        }

        // 只删除成功插入物料的黑名单记录
        if (!successIds.isEmpty()) {
            materialBlacklistMapper.deleteMaterialBlacklistByIds(successIds.toArray(new Long[0]));
        }

        // 组装失败详情描述，例如："materialNo为M001的因为插入物料表失败移除失败"
        List<String> failDetails = failReasons.entrySet().stream()
                .map(entry -> "materialNo为 [" + entry.getKey() + "] 的因为 [" + entry.getValue() + "] 移除失败")
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("fail", fail);
        result.put("success", success);
        result.put("failDetails", failDetails);
        return result;
    }
}