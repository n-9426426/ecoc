package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.vehicle.domain.XmlTemplate;
import com.ruoyi.vehicle.domain.vo.XmlTemplateVo;
import com.ruoyi.vehicle.service.IXmlTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/xml/template")
public class XmlTemplateController extends BaseController {

    @Autowired
    private IXmlTemplateService xmlTemplateService;

    /**
     * 查询模板列表
     */
    @Operation(summary = "查询xml模板列表")
    @RequiresPermissions("xml:template:query")
    @GetMapping("/list")
    public AjaxResult list(XmlTemplate query) {
        List<XmlTemplateVo> list = xmlTemplateService.selectTemplateList(query);
        return AjaxResult.success(list);
    }

    @Operation(summary = "查询xml模板（不带分页）")
    @RequiresPermissions("xml:template:query")
    @GetMapping("/optionselect")
    public AjaxResult optionselect() {
        List<XmlTemplateVo> list = xmlTemplateService.selectTemplateAll();
        return AjaxResult.success(list);
    }

    /**
     * 查询模板详情
     */
    @Operation(summary = "查询xml模板详情")
    @RequiresPermissions("xml:template:get")
    @GetMapping("/{templateId}")
    public AjaxResult getDetail(@PathVariable Long templateId) {
        return AjaxResult.success(xmlTemplateService.selectTemplateDetail(templateId));
    }

    /**
     * 新增模板
     */
    @Operation(summary = "新增xml模板")
    @Log(title = "XML模板管理", businessType = BusinessType.INSERT)
    @RequiresPermissions("xml:template:add")
    @PostMapping
    public AjaxResult add(@RequestBody XmlTemplate template) {
        return toAjax(xmlTemplateService.insertTemplate(template));
    }

    /**
     * 修改模板
     */
    @Operation(summary = "修改xml模板")
    @Log(title = "XML模板管理", businessType = BusinessType.UPDATE)
    @RequiresPermissions("xml:template:edit")
    @PutMapping
    public AjaxResult edit(@RequestBody XmlTemplate template) {
        return toAjax(xmlTemplateService.updateTemplate(template));
    }

    /**
     * 批量删除模板（逻辑删除）
     *
     * 请求方式：DELETE /vehicle/template/1,2,3
     */
    @Operation(summary = "批量删除xml模板")
    @Log(title = "XML模板管理", businessType = BusinessType.DELETE)
    @RequiresPermissions("xml:template:delete")
    @DeleteMapping("/{templateIds}")
    public AjaxResult remove(@PathVariable List<Long> templateIds) {
        return toAjax(xmlTemplateService.deleteTemplates(templateIds));
    }

    @RequiresPermissions("xml:template:history")
    @PostMapping("/history")
    public AjaxResult historyVersion(@RequestBody XmlTemplate template) {
        return AjaxResult.success(xmlTemplateService.historyVersion(template));
    }
}