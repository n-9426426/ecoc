package com.ruoyi.vehicle.controller;

import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.XmlFile;
import com.ruoyi.vehicle.domain.vo.DiffResultVO;
import com.ruoyi.vehicle.service.IXmlFileService;
import com.ruoyi.vehicle.utils.ExcelUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * XML文件Controller
 */
@RestController
@RequestMapping("/xml")
public class XmlController extends BaseController {

    @Autowired
    private IXmlFileService xmlFileService;

    @Autowired
    private ExcelUtil excelUtil;

    @Autowired
    private RemoteDictService remoteDictService;

    @Value("${file.path:/profile}")
    private String uploadPath;

    /**
     * 查询XML文件列表
     */
    @GetMapping("/list")
    @RequiresPermissions("system:xml:query")
    public TableDataInfo list(XmlFile xmlFile) {
        // VIN：逗号/换行拆分成 vinList
        if (StringUtils.isNotBlank(xmlFile.getVin())) {
            List<String> vinList = Arrays.stream(xmlFile.getVin().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            xmlFile.setVinList(vinList);
            xmlFile.setVin(null);
        }
        // 车型代码：逗号拆分成 modelCodeList
        if (StringUtils.isNotBlank(xmlFile.getModelCode())) {
            List<String> modelCodeList = Arrays.stream(xmlFile.getModelCode().split("[,，\n]"))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
            xmlFile.setModelCodeList(modelCodeList);
            xmlFile.setModelCode(null);
        }
        startPage();
        List<XmlFile> list = xmlFileService.selectXmlFileList(xmlFile);
        return getDataTable(list);
    }

    /**
     * 导出XML文件列表
     */
    @Operation(summary = "导出XML文件列表")
    @RequiresPermissions("system:xml:export")
    @Log(title = "XML文件管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestBody XmlFile xmlFile) throws Exception {
        List<XmlFile> xmlFiles = xmlFileService.selectXmlFileList(xmlFile);

        // 查询字典
        List<SysDictData> vehicleModelList   = remoteDictService.getDictDataByType("vehicle_model").getData();
        List<SysDictData> countryList        = remoteDictService.getDictDataByType("country").getData();
        List<SysDictData> uploadResultList   = remoteDictService.getDictDataByType("upload_result").getData();
        List<SysDictData> validateResultList = remoteDictService.getDictDataByType("validate_result").getData();
        List<SysDictData> statusList         = remoteDictService.getDictDataByType("xml_status").getData();

        // 转 Map
        Map<String, String> modelMap          = toMap(vehicleModelList);
        Map<String, String> countryMap        = toMap(countryList);
        Map<String, String> uploadResultMap   = toMap(uploadResultList);
        Map<String, String> validateResultMap = toMap(validateResultList);
        Map<String, String> statusMap         = toMap(statusList);

        // 设置翻译字段
        for (XmlFile xml : xmlFiles) {
            xml.setModelName(modelMap.getOrDefault(
                    xml.getModelCode(), xml.getModelCode()));
            xml.setCountryLabel(countryMap.getOrDefault(
                    xml.getCountry(), xml.getCountry()));
            xml.setUploadResultLabel(uploadResultMap.getOrDefault(
                    xml.getUploadResult(), xml.getUploadResult()));
            xml.setValidateResultLabel(xml.getValidateResult() != null
                    ? validateResultMap.getOrDefault(
                    String.valueOf(xml.getValidateResult()),
                    String.valueOf(xml.getValidateResult()))
                    : "");
            xml.setStatusLabel(statusMap.getOrDefault(
                    xml.getStatus(), xml.getStatus()));
        }

        excelUtil.exportExcel(response, xmlFiles, "xml_file", "XML File");
    }



    // 工具方法：把字典列表转成 dictValue -> dictLabel 的 Map
    private Map<String, String> toMap(List<SysDictData> list) {
        if (list == null) return new HashMap<>();
        return list.stream().collect(Collectors.toMap(
                SysDictData::getDictValue,
                SysDictData::getDictLabel,
                (a, b) -> a
        ));
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
    @Log(title = "XML文件管理", businessType = BusinessType.IMPORT)
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file,
                             @RequestParam("xmlId") Long xmlId) {
        try {
            String filePath = xmlFileService.uploadXmlFile(file, xmlId);
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
    @Log(title = "XML文件管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody XmlFile xmlFile) {
        return toAjax(xmlFileService.updateXmlFile(xmlFile));
    }

    /**
     * 删除XML文件
     */
    @Operation(summary = "批量删除XML文件")
    @RequiresPermissions("system:xml:remove")
    @Log(title = "XML文件管理", businessType = BusinessType.DELETE)
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
    @Log(title = "XML文件管理", businessType = BusinessType.EXPORT)
    @GetMapping("/download/{id}")
    public void download(@PathVariable Long id, HttpServletResponse response) {
        try {
            XmlFile xmlFile = xmlFileService.selectXmlFileById(id);
            if (xmlFile == null) {
                logger.error("找不到XML文件记录，id={}", id);
                return;
            }

            String filePath = xmlFile.getFilePath();
            logger.info("文件路径: {}", filePath);

            response.setContentType("application/xml");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=" + new String(
                            xmlFile.getFileName().getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.ISO_8859_1
                    ));

            // 判断是 URL 还是本地路径
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                // 从远程 URL 读取
                URL url = new URL(filePath);
                try (InputStream is = url.openStream();
                     OutputStream os = response.getOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                    os.flush();
                }
            } else {
                // 本地文件路径
                File file = Paths.get(filePath).toFile();
                if (!file.exists()) {
                    logger.error("文件不存在: {}", file.getAbsolutePath());
                    return;
                }
                try (FileInputStream fis = new FileInputStream(file);
                     OutputStream os = response.getOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                    os.flush();
                }
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
    @Log(title = "XML文件管理", businessType = BusinessType.VALIDATION)
    @GetMapping("/validate/{id}")
    public AjaxResult validate(@PathVariable Long id) {
        return AjaxResult.success(xmlFileService.validateXml(id));
    }

    @Operation(summary = "批量校验XML文件")
    @RequiresPermissions("system:xml:validate")
    @Log(title = "XML文件管理", businessType = BusinessType.VALIDATION)
    @PostMapping("/validateBatch")
    public AjaxResult validateBatch(@RequestBody List<Long> ids) {
        List<AjaxResult> results = new ArrayList<>();
        for (Long id : ids) {
            results.add(AjaxResult.success(xmlFileService.validateXml(id)));
        }
        return AjaxResult.success(results);
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
    @Log(title = "XML文件管理", businessType = BusinessType.CREATE)
    @PostMapping("/generate/{vehicleId}")
    public AjaxResult generateXml(@PathVariable Long vehicleId) {
        try {
            String xmlContent = xmlFileService.generateXmlFromDatabase(vehicleId);
            return success(xmlContent);
        } catch (Exception e) {
            return error("生成失败: " + e.getMessage());
        }
    }

    @Operation(summary = "批量生成XML文件")
    @RequiresPermissions("system:xml:generate")
    @Log(title = "XML文件管理", businessType = BusinessType.CREATE)
    @PostMapping("/generate/batch")
    public AjaxResult batchGenerateXml(@RequestBody List<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return error("请选择需要生成的车辆");
        }
        List<String> successList = new ArrayList<>();
        List<String> failList = new ArrayList<>();

        for (Long vehicleId : vehicleIds) {
            try {
                xmlFileService.generateXmlFromDatabase(vehicleId);
                successList.add(String.valueOf(vehicleId));
            } catch (Exception e) {
                failList.add("vehicleId=" + vehicleId + "：" + e.getMessage());
            }
        }

        if (failList.isEmpty()) {
            return success("全部生成成功，共" + successList.size() + "条");
        } else if (successList.isEmpty()) {
            return error("全部生成失败：\n" + String.join("\n", failList));
        } else {
            return success("部分生成成功，成功" + successList.size() + "条，失败"
                    + failList.size() + "条：\n" + String.join("\n", failList));
        }
    }

    /**
     * 恢复xml文件
     */
    @Operation(summary = "恢复xml文件")
    @RequiresPermissions("system:xml:restore")
    @Log(title = "XML文件管理", businessType = BusinessType.RESTORE)
    @PutMapping("/restore")
    public AjaxResult recover(@RequestBody Long[] xmlIds) {
        return xmlFileService.restoreXmlByIds(xmlIds);
    }

    /**
     * 永久删除xml信息
     */
    @Operation(summary = "永久删除xml信息")
    @RequiresPermissions("system:xml:permanently")
    @Log(title = "XML文件管理", businessType = BusinessType.PERMANENTLY_DELETE)
    @PutMapping("/permanently")
    public AjaxResult permanently(@RequestBody Long[] xmlIds) {
        return AjaxResult.success(xmlFileService.permanentlyDeleteXmlByIds(xmlIds));
    }
}
