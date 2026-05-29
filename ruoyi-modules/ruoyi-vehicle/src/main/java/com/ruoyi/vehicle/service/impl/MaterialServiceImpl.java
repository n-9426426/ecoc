package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.MaterialHistory;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.MaterialHistoryMapper;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.service.IMaterialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 整车物料 Service 业务层实现
 *
 * @author ruoyi
 */
@Service
public class MaterialServiceImpl implements IMaterialService {

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private MaterialHistoryMapper materialHistoryMapper;

    @Autowired
    private VehicleTemplateMapper vehicleTemplateMapper;

    /**
     * 查询整车物料
     */
    @Override
    public Material selectMaterialById(Long id) {
        Material material = materialMapper.selectMaterialById(id);
        List<VehicleTemplate> vehicleTemplates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(
                null, material.getBrand(), material.getWeight(), material.getSaleName(), material.getTire(), material.getTvv()
        );
        material.setVehicleTemplates(vehicleTemplates);
        material.setMaterialHistories(materialHistoryMapper.selectByMaterialId(id, material.getVersion()));
        return material;
    }

    /**
     * 查询整车物料列表
     */
    @Override
    public List<Material> selectMaterialList(Material material) {
        return materialMapper.selectMaterialList(material);
    }

    /**
     * 新增整车物料
     */
    @Override
    public int insertMaterial(Material material) {
        Material query = new Material();
        query.setMaterialNo(material.getMaterialNo());
        List<Material> existMaterial = materialMapper.selectMaterialList(query);
        if (!existMaterial.isEmpty()) {
            throw new RuntimeException("该物料号已经定义过版本，无法继续定义");
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        material.setCreateBy(loginUser.getUsername());
        material.setCreateTime(new Date());
        material.setRemark(StringUtils.isBlank(material.getSwitchRemark()) ? null : material.getSwitchRemark());
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(null,
                material.getBrand(), material.getWeight(), material.getSaleName(), material.getTire(), material.getTvv()
        );
        if (templates.isEmpty()) {
            throw new RuntimeException("没有匹配的模版");
        }
        Map<String, VehicleTemplate> templateMap = templates.stream()
                .collect(Collectors.toMap(
                        VehicleTemplate::getUuid,
                        t -> t,
                        (existing, replacement) -> existing
                ));
        if (templateMap.size() > 1) {
            throw new RuntimeException("请输入正确的数据以匹配模版");
        }
        templateMap = templates.stream()
                .sorted(Comparator.comparing(
                        t -> new BigDecimal(t.getVersion()),
                        Comparator.reverseOrder()
                ))
                .collect(Collectors.toMap(
                        VehicleTemplate::getVersion,
                        t -> t,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
        Map.Entry<String, VehicleTemplate> firstEntry = templateMap.entrySet().iterator().next();
        String version = firstEntry.getKey();
        VehicleTemplate template = firstEntry.getValue();
        material.setVersion(template.getVersion());
        material.setVehicleTemplateId(template.getTemplateId());
        return materialMapper.insertMaterial(material);
    }

    /**
     * 修改整车物料
     */
    @Override
    public int updateMaterial(Material material) {
        Material query = new Material();
        query.setMaterialNo(material.getMaterialNo());
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(null,
                material.getBrand(), material.getWeight(), material.getSaleName(), material.getTire(), material.getTvv()
        );
        if (templates.isEmpty()) {
            throw new RuntimeException("没有匹配的模版");
        }
        Map<String, VehicleTemplate> templateMap = templates.stream()
                .collect(Collectors.toMap(
                        VehicleTemplate::getUuid,
                        t -> t,
                        (existing, replacement) -> existing
                ));
        if (templateMap.size() > 1) {
            throw new RuntimeException("请输入正确的数据以匹配模版");
        }
        Map<String, VehicleTemplate> map = templates.stream()
                .collect(Collectors.toMap(
                        VehicleTemplate::getVersion,
                        t -> t,
                        (existing, replacement) -> existing  // version重复时保留第一个
                ));
        VehicleTemplate template = map.get(material.getNewVersion());
        if (template == null) {
            throw new RuntimeException("该模版不存在");
        }
        material.setVersion(template.getVersion());
        material.setVehicleTemplateId(template.getTemplateId());
        LoginUser loginUser = SecurityUtils.getLoginUser();
        material.setUpdateBy(loginUser.getUsername());
        material.setUpdateTime(new Date());
        int row = materialMapper.updateMaterial(material);
        if (!material.getVersion().equals(material.getNewVersion())) {
            MaterialHistory history = new MaterialHistory();
            history.setMaterialId(material.getId());
            history.setOldVersion(material.getVersion());
            history.setNewVersion(material.getNewVersion());
            history.setChangeTime(new Date());
            history.setOperator(loginUser.getUsername());
            history.setRemark(material.getSwitchRemark());
            materialHistoryMapper.insert(history);
        }
        return row;
    }

    /**
     * 批量删除整车物料
     */
    @Override
    public int deleteMaterialByIds(Long[] ids) {
        return materialMapper.deleteMaterialByIds(ids);
    }
}
