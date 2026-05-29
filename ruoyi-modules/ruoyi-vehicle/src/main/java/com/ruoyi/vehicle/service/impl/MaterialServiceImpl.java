package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.domain.MaterialHistory;
import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.mapper.MaterialHistoryMapper;
import com.ruoyi.vehicle.mapper.MaterialMapper;
import com.ruoyi.vehicle.mapper.VehicleTemplateMapper;
import com.ruoyi.vehicle.service.IMaterialService;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

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

    @Autowired
    private IVehicleInfoService vehicleInfoService;

    /**
     * 查询整车物料
     */
    @Override
    public Material selectMaterialById(Long id) {
        Material material = materialMapper.selectMaterialById(id);
        List<VehicleTemplate> vehicleTemplates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(
                null, material.getBrand(), material.getWeight(), material.getSaleName(), material.getTrie(), material.getTvv()
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
        List<VehicleTemplate> templates = vehicleTemplateMapper.selectVehicleTemplateIdByCondition(null,
                material.getBrand(), material.getWeight(), material.getSaleName(), material.getTrie(), material.getTvv()
        );
        if (templates.isEmpty()) {
            throw new RuntimeException("没有匹配的模版");
        }
        templates.sort(
                Comparator.comparing(
                        t -> t.getVersion() != null ? new BigDecimal(t.getVersion()) : BigDecimal.ZERO,
                        Comparator.reverseOrder()
                )
        );
        Optional<VehicleTemplate> first = templates.stream().findFirst();
        material.setVersion(first.map(t -> t.getVersion() == null ? null : t.getVersion()).orElse(null));
        material.setVehicleTemplateId(first.map(VehicleTemplate::getTemplateId).orElse(null));
        return materialMapper.insertMaterial(material);
    }

    /**
     * 修改整车物料
     */
    @Override
    public int updateMaterial(Material material) {
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
            history.setRemark(material.getRemark());
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
