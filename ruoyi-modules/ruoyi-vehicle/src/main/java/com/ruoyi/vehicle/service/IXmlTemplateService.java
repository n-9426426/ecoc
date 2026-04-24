package com.ruoyi.vehicle.service;


import com.ruoyi.vehicle.domain.XmlTemplate;
import com.ruoyi.vehicle.domain.vo.XmlTemplateVo;

import java.util.List;

public interface IXmlTemplateService {

    /** 查询模板列表 */
    List<XmlTemplateVo> selectTemplateList(XmlTemplate query);

    /** 查询模板详情 */
    XmlTemplateVo selectTemplateDetail(Long templateId);

    /** 新增模板 */
    int insertTemplate(XmlTemplate template);

    /** 修改模板 */
    int updateTemplate(XmlTemplate template);

    /** 删除模板 */
    int deleteTemplates(List<Long> templateIds);

    List<XmlTemplateVo> selectTemplateAll();

    List<XmlTemplate> historyVersion(XmlTemplate template);
}