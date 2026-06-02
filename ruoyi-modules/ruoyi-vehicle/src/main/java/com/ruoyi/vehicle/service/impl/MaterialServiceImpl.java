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
import com.ruoyi.vehicle.utils.ExcelUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 整车物料 Service 业务层实现
 *
 * @author ruoyi
 */
@Slf4j
@Service
public class MaterialServiceImpl implements IMaterialService {

    @Autowired
    private ExcelUtil excelUtil;

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
        Material existMaterial = materialMapper.selectByMaterialNo(material.getMaterialNo());
        if (existMaterial != null) {
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importMaterial(MultipartFile file, boolean updateSupport) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
        }

        // 解析语言（由 ExcelUtil 从请求上下文自动获取）
        String lang = excelUtil.resolveCurrentLang();

        // 调用通用工具类解析 Excel -> 实体列表，跳过说明行和示例行
        List<Material> materialList = excelUtil.importExcel(
                file.getInputStream(),
                "material",
                Material.class,
                lang,
                2
        );

        if (materialList.isEmpty()) {
            return "未解析到有效数据，请检查文件内容是否从第4行开始填写";
        }

        int successCount = 0;
        int updateCount  = 0;
        int skipCount    = 0;
        List<String> failMessages = new ArrayList<>();

        for (int i = 0; i < materialList.size(); i++) {
            Material material = materialList.get(i);
            // Excel 第几行（列头=1，说明=2，示例=3，数据从4开始）
            int rowNum = i + 4;

            // 必填校验：material_no
            if (material.getMaterialNo() == null || material.getMaterialNo().trim().isEmpty()) {
                failMessages.add("第" + rowNum + "行：整车物料号（Material No）不能为空，已跳过");
                skipCount++;
                continue;
            }

            try {
                // 根据 material_no 唯一键判断是否已存在
                Material existing = materialMapper.selectByMaterialNo(material.getMaterialNo());

                if (existing == null) {
                    // 新增
                    materialMapper.insertMaterial(material);
                    successCount++;
                } else if (updateSupport) {
                    // 允许更新：保留原主键
                    material.setId(existing.getId());
                    materialMapper.updateMaterial(material);
                    updateCount++;
                } else {
                    // 不允许更新：跳过
                    failMessages.add("第" + rowNum + "行：物料号[" + material.getMaterialNo() + "]已存在，已跳过");
                    skipCount++;
                }
            } catch (Exception e) {
                log.error("导入第{}行失败，material_no={}，原因：{}", rowNum, material.getMaterialNo(), e.getMessage());
                failMessages.add("第" + rowNum + "行：导入失败，" + e.getMessage());
                skipCount++;
            }
        }

        // 汇总结果
        StringBuilder sb = new StringBuilder();
        sb.append("导入完成！新增 ").append(successCount).append(" 条");
        if (updateSupport) {
            sb.append("，更新 ").append(updateCount).append(" 条");
        }
        sb.append("，跳过 ").append(skipCount).append(" 条");
        if (!failMessages.isEmpty()) {
            sb.append("。详情：").append(String.join("；", failMessages));
        }
        return sb.toString();
    }
}
