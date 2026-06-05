package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.vehicle.domain.Material;
import com.ruoyi.vehicle.service.IMaterialService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

/**
 * 整车物料 Controller
 *
 * @author ruoyi
 */
@Slf4j
@RestController
@RequestMapping("/material")
public class MaterialController extends BaseController {

    @Autowired
    private IMaterialService materialService;

    @Autowired
    private RemoteDictService remoteDictService;

    /**
     * 查询整车物料列表
     */
    @Operation(summary = "整车物料号列表")
    @RequiresPermissions("vehicle:material:list")
    @GetMapping("/list")
    public TableDataInfo list(Material material) {
        startPage();
        List<Material> list = materialService.selectMaterialList(material);
        return getDataTable(list);
    }

    /**
     * 获取整车物料详细信息
     */
    @Operation(summary = "获取整车物料号信息")
    @RequiresPermissions("vehicle:material:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(materialService.selectMaterialById(id));
    }

    /**
     * 新增整车物料
     */
    @Operation(summary = "新增整车物料号信息")
    @RequiresPermissions("vehicle:material:add")
    @Log(title = "整车物料", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Material material) {
        return toAjax(materialService.insertMaterial(material));
    }

    /**
     * 修改整车物料
     */
    @Operation(summary = "编辑整车物料号信息")
    @RequiresPermissions("vehicle:material:edit")
    @Log(title = "整车物料", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Material material) {
        return toAjax(materialService.updateMaterial(material));
    }

    /**
     * 删除整车物料
     */
    @Operation(summary = "删除整车物料号信息")
    @RequiresPermissions("vehicle:material:remove")
    @Log(title = "整车物料", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(materialService.deleteMaterialByIds(ids));
    }

    /**
     * 提交物料 Excel 导入任务，立即返回 taskId。
     * 前端拿到 taskId 后，连接 /import/{taskId} 订阅进度。
     *
     * @param file          上传的 Excel 文件
     * @param updateSupport 是否允许覆盖已存在的物料号（true=覆盖，false=跳过）
     */
    @RequiresPermissions("vehicle:material:import")
    @PostMapping("/import")
    public AjaxResult importMaterial(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "updateSupport", defaultValue = "false") boolean updateSupport) {
        if (file.isEmpty()) {
            return AjaxResult.error("上传文件不能为空");
        }
        String taskId = materialService.submitImportTask(file, updateSupport);
        return AjaxResult.success(taskId);
    }

    /**
     * 通过 SSE 实时订阅物料导入进度。
     * 事件类型：
     *   progress → {"row":N,"total":T,"status":"success"/"update"/"skip"/"fail","reason":"..."}
     *   complete → {"successCount":N,"updateCount":U,"skipCount":S,"failCount":F,"errorDetails":["..."]}
     *   error    → {"message":"..."}
     */
    @GetMapping(value = "/import/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> importFlux(@PathVariable("taskId") String taskId) {
        return materialService.getImportFlux(taskId);
    }

    @GetMapping("/download/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        String fileName = "物料号导入模版.xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));

        ClassPathResource resource = new ClassPathResource("assets/" + fileName);
        IOUtils.copy(resource.getInputStream(), response.getOutputStream());
        response.flushBuffer();
    }

    @RequiresPermissions("vehicle:material:edit")
    @Log(title = "物料号管理状态切换", businessType = BusinessType.UPDATE)
    @PutMapping("/status/{id}")
    public AjaxResult updateStatus(@PathVariable Long id) {
        return success(materialService.updateMaterialStatus(id));
    }
}
