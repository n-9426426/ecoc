package com.ruoyi.system.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.domain.SysI18n;
import com.ruoyi.system.service.ISysI18nService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 国际化翻译Controller
 */
@Tag(name = "国际化管理")
@RestController
@RequestMapping("/i18n")
public class SysI18nController extends BaseController {

    @Autowired
    private ISysI18nService i18nService;

    @GetMapping("/translate")
    String translate(@RequestParam(value = "langKey", required = false, defaultValue = "") String langKey,
                     @RequestParam(value = "langCode", required = false) String langCode) {
        return i18nService.translate(langKey, langCode);
    }

    /**
     * 查询翻译列表
     * GET /i18n/list
     * 支持：like翻译键、eq模块、eq语言代码、like翻译内容、eq状态
     */
    @Operation(summary = "查询翻译列表")
    @GetMapping("/list")
    public TableDataInfo list(SysI18n sysI18n) {
        startPage();
        List<SysI18n> list = i18nService.selectSysI18nList(sysI18n);
        return getDataTable(list);
    }

    @Operation(summary = "查询翻译列表")
    @GetMapping("/list/all")
    public TableDataInfo allList(SysI18n sysI18n) {
        return getDataTable(i18nService.selectSysI18nList(sysI18n));
    }

    /**
     * 根据ID查询翻译详情
     * GET /i18n/{id}
     */
    @Operation(summary = "查询翻译详情")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable String id) {
        return success(i18nService.selectSysI18nById(id));
    }

    /**
     * 新增翻译
     * POST /i18n
     */
    @Operation(summary = "新增翻译")
    @Log(title = "国际化翻译", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SysI18n sysI18n) {
        String username = SecurityUtils.getUsername();
        return toAjax(i18nService.insertSysI18n(sysI18n));
    }

    /**
     * 修改翻译
     * PUT /i18n
     */
    @Operation(summary = "修改翻译")
    @Log(title = "国际化翻译", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SysI18n sysI18n) {
        String username = SecurityUtils.getUsername();
        return toAjax(i18nService.updateSysI18n(sysI18n));
    }

    /**
     * 删除翻译（批量）
     * DELETE /i18n/{ids}
     */
    @Operation(summary = "删除翻译")
    @Log(title = "国际化翻译", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable String[] ids) {
        return toAjax(i18nService.deleteSysI18nByIds(ids));
    }

    /**
     * 刷新翻译缓存
     * POST /i18n/refresh
     */
    @Operation(summary = "刷新翻译缓存")
    @PostMapping("/refresh")
    public AjaxResult refresh() {
        i18nService.refreshCache();
        return success();
    }
}