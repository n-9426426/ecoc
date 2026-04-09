package com.ruoyi.vehicle.service;

import com.ruoyi.vehicle.domain.VehicleTemplate;
import com.ruoyi.vehicle.domain.vo.VehicleTemplateVo;

import java.util.List;

public interface IVehicleTemplateService {

    /** 查询模板列表 */
    List<VehicleTemplateVo> selectTemplateList(VehicleTemplate query);

    /** 查询模板详情 */
    VehicleTemplateVo selectTemplateDetail(Long templateId);

    /** 新增模板 */
    int insertTemplate(VehicleTemplate template);

    /** 修改模板 */
    int updateTemplate(VehicleTemplate template);

    /** 删除模板 */
    int deleteTemplates(List<Long> templateIds);
}