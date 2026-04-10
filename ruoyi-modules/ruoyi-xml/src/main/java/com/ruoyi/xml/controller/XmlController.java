package com.ruoyi.xml.controller;

import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.xml.domain.XmlFile;
import com.ruoyi.xml.domain.vo.DiffResultVO;
import com.ruoyi.xml.service.IXmlFileService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * XML文件Controller
 */
@RestController
@RequestMapping("/xml")
public class XmlController extends BaseController {

    @Autowired
    private IXmlFileService xmlFileService;

    @Value("${file.path:/profile}")
    private String uploadPath;

    /**
     * 查询XML文件列表
     */
    @Operation(summary = "查询XML文件列表")
    @RequiresPermissions("system:xml:query")
    @GetMapping("/list")
    public TableDataInfo list(XmlFile xmlFile) {
        startPage();
        List<XmlFile> list = xmlFileService.selectXmlFileList(xmlFile);
        return getDataTable(list);
    }

    /**
     * 导出XML文件列表
     */
    @Operation(summary = "导出XML文件列表")
    @RequiresPermissions("system:xml:export")
    @Log(title = "XML文件", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, XmlFile xmlFile) {
        List<XmlFile> list = xmlFileService.selectXmlFileList(xmlFile);
        ExcelUtil<XmlFile> util = new ExcelUtil<XmlFile>(XmlFile.class);
        util.exportExcel(response, list, "XML文件数据");
    }

    /**
     * 获取XML文件详细信息
     */
    @Operation(summary = "获取XML文件详细信息")
    @RequiresPermissions("system:xml:get")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(xmlFileService.selectXmlFileById(id));
    }

    /**
     * 上传XML文件
     */
    @Operation(summary = "上传XML文件")
    @RequiresPermissions("system:xml:add")
    @Log(title = "XML文件", businessType = BusinessType.INSERT)
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file,
                             @RequestParam("fileLevel") String fileLevel) {
        try {
            String filePath = xmlFileService.uploadXmlFile(file, fileLevel);
            return success(filePath);
        } catch (Exception e) {
            return error("上传失败: " + e.getMessage());
        }
    }

    /**
     * 修改XML文件
     */
    @Operation(summary = "修改XML文件")
    @RequiresPermissions("system:xml:edit")
    @Log(title = "XML文件", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody XmlFile xmlFile) {
        return toAjax(xmlFileService.updateXmlFile(xmlFile));
    }

    /**
     * 删除XML文件
     */
    @Operation(summary = "批量删除XML文件")
    @RequiresPermissions("system:xml:remove")
    @Log(title = "XML文件", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return xmlFileService.deleteXmlFileByIds(ids);
    }

    /**
     * 预览XML文件
     */
    @Operation(summary = "预览XML文件")
    @RequiresPermissions("system:xml:preview")
    @GetMapping("/preview/{id}")
    public AjaxResult preview(@PathVariable Long id) {
        try {
            String content = xmlFileService.previewXml(id);
            return success(content);
        } catch (Exception e) {
            return error("预览失败: " + e.getMessage());
        }
    }

    /**
     * 下载XML文件
     */
    @Operation(summary = "下载XML文件")
    @RequiresPermissions("system:xml:download")
    @GetMapping("/download/{id}")
    public void download(@PathVariable Long id, HttpServletResponse response) {
        try {
            XmlFile xmlFile = xmlFileService.selectXmlFileById(id);
            if (xmlFile == null) {
                return;
            }

            File file = new File(uploadPath + xmlFile.getFilePath());
            if (!file.exists()) {
                return;
            }

            response.setContentType("application/xml");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=" + new String(xmlFile.getFileName().getBytes("UTF-8"), "ISO-8859-1"));

            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
        } catch (Exception e) {
            logger.error("下载XML文件失败", e);
        }
    }

    /**
     * 校验XML文件
     */
    @Operation(summary = "校验XML文件")
    @RequiresPermissions("system:xml:validate")
    @GetMapping("/validate/{id}")
    public AjaxResult validate(@PathVariable Long id) {
        boolean isValid = xmlFileService.validateXml(id);
        return isValid ? success("校验通过") : error("校验失败，XML格式不正确");
    }

    /**
     * 查询文件版本列表
     */
    @Operation(summary = "查询文件版本列表")
    @RequiresPermissions("system:xml:version")
    @GetMapping("/versions/{fileId}")
    public AjaxResult versions(@PathVariable Long fileId) {
        try {
            List<XmlFile> versions = xmlFileService.selectXmlFileVersions(fileId);
            return success(versions);
        } catch (Exception e) {
            return error("查询版本失败: " + e.getMessage());
        }
    }

    /**
     * 版本对比
     */
    @Operation(summary = "版本对比")
    @RequiresPermissions("system:xml:compare")
    @GetMapping("/compare")
    public AjaxResult compare(@RequestParam String newVersion, @RequestParam String oldVersion) {
        try {
            DiffResultVO diff = xmlFileService.compareVersions(new BigDecimal(newVersion).longValue(), new BigDecimal(oldVersion).longValue());
            return success(diff);
        } catch (Exception e) {
            return error("版本对比失败: " + e.getMessage());
        }
    }

    /**
     * 从数据库生成XML文件
     */
    @Operation(summary = "从数据库生成车辆信息管理XML文件")
    @RequiresPermissions("system:xml:generate")
    @Log(title = "XML文件", businessType = BusinessType.CREATE)
    @PostMapping("/generate/{vehicleId}")
    public AjaxResult generateXml(@PathVariable Long vehicleId) {
        try {
            String xmlContent = xmlFileService.generateXmlFromDatabase(vehicleId);
            return success(xmlContent);
        } catch (Exception e) {
            return error("生成失败: " + e.getMessage());
        }
    }

    /**
     * 恢复xml文件
     */
    @Operation(summary = "恢复xml文件")
    @RequiresPermissions("system:xml:restore")
    @Log(title = "XML文件", businessType = BusinessType.RESTORE)
    @PutMapping("/restore")
    public AjaxResult recover(@RequestBody Long[] xmlIds) {
        return xmlFileService.restoreXmlByIds(xmlIds);
    }

    /**
     * 永久删除xml信息
     */
    @Operation(summary = "永久删除xml信息")
    @RequiresPermissions("system:xml:permanently")
    @Log(title = "XML文件", businessType = BusinessType.PERMANENTLY_DELETE)
    @PutMapping("/permanently")
    public AjaxResult permanently(@RequestBody Long[] xmlIds) {
        return AjaxResult.success(xmlFileService.permanentlyDeleteXmlByIds(xmlIds));
    }
}