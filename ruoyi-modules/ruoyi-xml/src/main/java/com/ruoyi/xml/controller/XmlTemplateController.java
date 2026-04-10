package com.ruoyi.xml.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.xml.domain.XmlTemplate;
import com.ruoyi.xml.domain.vo.XmlTemplateVo;
import com.ruoyi.xml.service.IXmlTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/xml/template")
public class XmlTemplateController extends BaseController {

    @Autowired
    private IXmlTemplateService vehicleTemplateService;

    /**
     * 查询模板列表
     */
    @Operation(summary = "查询xml模板列表")
    @Log(title = "xml模板", businessType = BusinessType.OTHER)
    @RequiresPermissions("vehicle:template:query")
    @GetMapping("/list")
    public AjaxResult list(XmlTemplate query) {
        List<XmlTemplateVo> list = vehicleTemplateService.selectTemplateList(query);
        return AjaxResult.success(list);
    }

    /**
     * 查询模板详情
     */
    @Operation(summary = "查询xml模板详情")
    @Log(title = "xml模板", businessType = BusinessType.OTHER)
    @RequiresPermissions("vehicle:template:get")
    @GetMapping("/{templateId}")
    public AjaxResult getDetail(@PathVariable Long templateId) {
        return AjaxResult.success(vehicleTemplateService.selectTemplateDetail(templateId));
    }

    /**
     * 新增模板
     */
    @Operation(summary = "新增xml模板")
    @Log(title = "xml模板", businessType = BusinessType.INSERT)
    @RequiresPermissions("vehicle:template:add")
    @PostMapping
    public AjaxResult add(@RequestBody XmlTemplate template) {
        return toAjax(vehicleTemplateService.insertTemplate(template));
    }

    /**
     * 修改模板
     */
    @Operation(summary = "修改xml模板")
    @Log(title = "xml模板", businessType = BusinessType.UPDATE)
    @RequiresPermissions("vehicle:template:edit")
    @PutMapping
    public AjaxResult edit(@RequestBody XmlTemplate template) {
        return toAjax(vehicleTemplateService.updateTemplate(template));
    }

    /**
     * 批量删除模板（逻辑删除）
     *
     * 请求方式：DELETE /vehicle/template/1,2,3
     */
    @Operation(summary = "批量删除xml模板")
    @Log(title = "xml模板", businessType = BusinessType.DELETE)
    @RequiresPermissions("vehicle:template:delete")
    @DeleteMapping("/{templateIds}")
    public AjaxResult remove(@PathVariable List<Long> templateIds) {
        return toAjax(vehicleTemplateService.deleteTemplates(templateIds));
    }
}