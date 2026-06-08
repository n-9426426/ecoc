package com.ruoyi.vehicle.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.util.DateUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteFileService;
import com.ruoyi.system.api.RemoteNoticeService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.system.api.domain.SysNotice;
import com.ruoyi.system.api.enums.SysNoticeModel;
import com.ruoyi.vehicle.domain.*;
import com.ruoyi.vehicle.domain.vo.DiffLineVO;
import com.ruoyi.vehicle.domain.vo.DiffResultVO;
import com.ruoyi.vehicle.enums.VehicleLifecycleOperation;
import com.ruoyi.vehicle.mapper.*;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.service.IVehicleValidationService;
import com.ruoyi.vehicle.service.IXmlFileService;
import com.ruoyi.vehicle.utils.FileUtils;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * XML文件Service业务层处理
 */
@Service
public class XmlFileServiceImpl implements IXmlFileService {

    private static final Logger log = LoggerFactory.getLogger(XmlFileServiceImpl.class);

    @Autowired
    private XmlFileMapper xmlFileMapper;

    @Autowired
    private XmlVersionMapper xmlVersionMapper;

    @Autowired
    private IVehicleInfoService vehicleInfoService;

    @Autowired
    private IVehicleValidationService vehicleValidationService;

    @Autowired
    private RemoteFileService remoteFileService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

    @Autowired
    private RemoteDictService remoteDictService;

    @Autowired
    private VehicleLifecycleMapper vehicleLifecycleMapper;

    @Autowired
    private XmlTemplateMapper xmlTemplateMapper;

    @Autowired
    private XmlTemplateAttributeMapper xmlTemplateAttributeMapper;

    @Autowired
    private AbnormalClassifyMapper abnormalClassifyMapper;

    @Autowired
    private RemoteNoticeService remoteNoticeService;

    @Autowired
    private VehicleInfoMapper vehicleInfoMapper;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询XML文件列表
     */
    @Override
    public List<XmlFile> selectXmlFileList(XmlFile xmlFile) {
        return xmlFileMapper.selectXmlFileList(xmlFile);
    }

    /**
     * 查询XML文件
     */
    @Override
    public XmlFile selectXmlFileById(Long id) {
        return xmlFileMapper.selectXmlFileById(id);
    }

    /**
     * 新增XML文件
     */
    @Override
    @Transactional
    public int insertXmlFile(XmlFile xmlFile) {
        xmlFile.setCreateBy(SecurityUtils.getUsername());
        return xmlFileMapper.insertXmlFile(xmlFile);
    }

    /**
     * 修改XML文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateXmlFile(XmlFile xmlFile) {
        // 1. 保存前端传入的 content
        String base64Content = xmlFile.getContent();
        // 还原 URL-safe Base64 为标准 Base64
        String standardBase64 = base64Content
                .replace('-', '+')
                .replace('_', '/');
        // 补齐填充符
        int padding = standardBase64.length() % 4;
        if (padding == 2) {
            standardBase64 += "==";
        } else if (padding == 3) {
            standardBase64 += "=";
        }
        String content;
        try {
            byte[] decodedBytes;
            // 判断是否包含 URL Safe Base64 特征字符
            if (base64Content.contains("-") || base64Content.contains("_")) {
                decodedBytes = Base64.getUrlDecoder().decode(base64Content);
            } else {
                decodedBytes = Base64.getDecoder().decode(base64Content);
            }
            content = new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("XML内容解码失败，请确认传入Base64格式");
        }

        // 2. 查询数据库中的原始记录
        XmlFile dbXmlFile = xmlFileMapper.selectXmlFileById(xmlFile.getId());
        if (dbXmlFile == null) {
            throw new ServiceException("xml文件不存在");
        }

        // 3. 从旧文件名中提取 VIN
        String oldFileName = dbXmlFile.getFileName();
        String vin = oldFileName.split("_")[1];
        vin = vin.split("\\.")[0];

        // 4. 计算新版本号（当前版本 +1）
        String oldVersion = xmlFileMapper.selectVersionByFileName("vehicle_" + vin);
        String newVersion = String.valueOf(new BigDecimal(oldVersion).add(new BigDecimal(1)));

        // 5. 生成新文件名和路径
        String newFileName = "vehicle_" + vin + ".xml";
        // 获取文件路径
        MultipartFile multipartFile = FileUtils.createMultipartFile(
                content, vin + ".xml", "application/xml");
        String newFilePath = remoteFileService.upload(multipartFile).getData().getUrl();

        // 7. 计算文件大小
        long fileSize = multipartFile.getSize();

        // 8. 构造 remark
        String remark = "由" + oldFileName + "更新，版本：" + newVersion;

        // 9. 将旧记录 is_latest 设为 0
        xmlFileMapper.updateIsLatestToFalse("vehicle_" + vin);

        // 10. 更新 xml_file 表
        dbXmlFile.setFileName(newFileName);
        dbXmlFile.setFilePath(newFilePath);
        dbXmlFile.setFileSize(fileSize);
        dbXmlFile.setVersion(newVersion);
        dbXmlFile.setIsLatest(true);
        dbXmlFile.setRemark(remark);
        dbXmlFile.setCreateBy(SecurityUtils.getUsername());
        dbXmlFile.setCreateTime(new Date());
        int rows = xmlFileMapper.insertXmlFile(dbXmlFile);

        // 11. 插入 xml_version 历史记录
        XmlVersion xmlVersion = new XmlVersion();
        xmlVersion.setFileId(dbXmlFile.getId());
        xmlVersion.setVersion(newVersion);
        xmlVersion.setFilePath(newFilePath);
        xmlVersion.setChangeType("更新");
        xmlVersion.setChangeDesc(remark);
        xmlVersion.setCreateBy(SecurityUtils.getUsername());
        xmlVersion.setCreateTime(new Date());
        xmlVersionMapper.insertXmlVersion(xmlVersion);

        return rows;
    }

    /**
     * 批量删除XML文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult deleteXmlFileByIds(Long[] xmlIds) {
        try {
            int deleteRows = xmlFileMapper.deleteXmlFileByIds(xmlIds);
            Map<String, Integer> result = new HashMap<>();
            result.put("deleteRows", deleteRows);
            return AjaxResult.success(result);
        } catch (Exception e){
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 上传XML文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String uploadXmlFile(MultipartFile file, Long xmlId) {
        try {
            String filePath = remoteFileService.upload(file).getData().getUrl();
            XmlFile xmlFile = xmlFileMapper.selectXmlFileById(xmlId);
            xmlFile.setFilePath(filePath);
            xmlFile.setFileSize(file.getSize());

            String xmlVersion = xmlFileMapper.selectVersionByFileName("vehicle_" + xmlFile.getVin());
            if (xmlVersion == null) {
                xmlVersion = "1.0";
            } else {
                xmlVersion = String.valueOf(new BigDecimal(xmlVersion).add(new BigDecimal(1)));
            }

            xmlFile.setVersion(xmlVersion);
            xmlFile.setFileName("vehicle_" + xmlFile.getVin() + ".xml");
            xmlFile.setFileSize(file.getSize());
            xmlFile.setIsLatest(true);
            xmlFile.setStatus("0");
            xmlFile.setDeleted(0);
            xmlFile.setCreateBy(SecurityUtils.getUsername());
            xmlFile.setCreateTime(new Date());
            xmlFileMapper.insertXmlFile(xmlFile);
            xmlFileMapper.updateIsLatestToFalse("vehicle_" + xmlFile.getVin());

            // 保存版本记录
            XmlVersion version = new XmlVersion();
            version.setFileId(xmlFile.getId());
            version.setVersion(xmlVersion);
            version.setFilePath(filePath);
            version.setChangeType("上传");
            version.setChangeDesc("上传新版本");
            version.setCreateBy(SecurityUtils.getUsername());
            version.setCreateTime(new Date());
            xmlVersionMapper.insertXmlVersion(version);

            return filePath;
        } catch (Exception e) {
            log.error("上传XML文件失败", e);
            throw new RuntimeException("上传失败: " + e.getMessage());
        }
    }

    @Override
    public int uploadXmlFilesToApprove(List<Long> xmlIds) {
        for (Long xmlId : xmlIds) {
            checkUploadPermission(new VehicleInfo());
        }
        return -1;
    }

    /**
     * 预览XML文件
     */
    @Override
    public String previewXml(Long id) {
        try {
            XmlFile xmlFile = xmlFileMapper.selectXmlFileById(id);
            if (xmlFile == null) {
                throw new RuntimeException(remoteTranslateService.translate("xml.not.found", null));
            }

            String fullPath = xmlFile.getFilePath();
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(fullPath)
                    .header("User-Agent", "YourApp/1.0")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException(StringUtils.format(
                            remoteTranslateService.translate("common.file.not.found", null), fullPath));
                }
                return response.body().string(); // 自动按 UTF-8 解码
            }
        } catch (Exception e) {
            log.error("预览XML文件失败", e);
            throw new RuntimeException(StringUtils.format(
                    remoteTranslateService.translate("common.file.preview.failed", null), e.getMessage()));
        }
    }

    /**
     * 查询文件版本列表
     */
    @Override
    public List<XmlFile> selectXmlFileVersions(Long fileId) {
        XmlFile xmlFile = xmlFileMapper.selectXmlFileById(fileId);
        if (xmlFile == null) {
            throw new RuntimeException(remoteTranslateService.translate("common.file.not.exist", null));
        }
        String vin = xmlFile.getVin();
        if (StringUtils.isBlank(vin)) {
            throw new RuntimeException("VIN为空，无法查询版本列表");
        }
        List<XmlFile> versions = xmlFileMapper.selectXmlFileVersions(vin);
        return versions;
    }

    /**
     * 版本对比
     */
    @Override
    public DiffResultVO compareVersions(Long newVersionId, Long oldVersionId) {
        try {
            XmlFile newFile = xmlFileMapper.selectXmlFileById(newVersionId);
            XmlFile oldFile = xmlFileMapper.selectXmlFileById(oldVersionId);

            if (oldFile == null || newFile == null) {
                throw new RuntimeException(remoteTranslateService.translate("common.file.not.exist", null));
            }

            String projectPath = System.getProperty("user.dir");
            String oldContent = readFileContent(oldFile.getFilePath());
            String newContent = readFileContent(newFile.getFilePath());

            String[] oldLines = oldContent.replace("\r\n", "\n").split("\n", -1);
            String[] newLines = newContent.replace("\r\n", "\n").split("\n", -1);

            List<DiffLineVO> oldResult = new ArrayList<>();
            List<DiffLineVO> newResult = new ArrayList<>();

            // 使用 LCS diff 对齐
            List<int[]> diffPairs = diffLines(oldLines, newLines);

            boolean isSame = true;
            int oldLineNum = 1;
            int newLineNum = 1;

            for (int[] pair : diffPairs) {
                int oldIdx = pair[0]; // -1 表示新增行（旧版本无对应）
                int newIdx = pair[1]; // -1 表示删除行（新版本无对应）

                DiffLineVO oldVO = new DiffLineVO();
                DiffLineVO newVO = new DiffLineVO();

                if (oldIdx >= 0 && newIdx >= 0) {
                    // 两侧都有，判断内容是否相同
                    boolean changed = !oldLines[oldIdx].equals(newLines[newIdx]);
                    if (changed) isSame = false;

                    oldVO.setLineNumber(oldLineNum++);
                    oldVO.setContent(oldLines[oldIdx]);
                    oldVO.setType(changed ? "removed" : "normal");

                    newVO.setLineNumber(newLineNum++);
                    newVO.setContent(newLines[newIdx]);
                    newVO.setType(changed ? "added" : "normal");

                } else if (oldIdx >= 0) {
                    // 旧版本有，新版本没有 —— 删除行
                    isSame = false;
                    oldVO.setLineNumber(oldLineNum++);
                    oldVO.setContent(oldLines[oldIdx]);
                    oldVO.setType("removed");
                } else {
                    // 新版本有，旧版本没有 —— 新增行
                    isSame = false;
                    newVO.setLineNumber(newLineNum++);
                    newVO.setContent(newLines[newIdx]);
                    newVO.setType("added");
                }

                if (!StringUtils.isBlank(oldVO.getType())) {
                    oldResult.add(oldVO);
                }
                if (!StringUtils.isBlank(newVO.getType())) {
                    newResult.add(newVO);
                }
            }

            DiffResultVO result = new DiffResultVO();
            result.setOldLines(oldResult);
            result.setNewLines(newResult);
            result.setSame(isSame);
            return result;

        } catch (Exception e) {
            log.error("版本对比失败", e);
            throw new RuntimeException(StringUtils.format(remoteTranslateService.translate("common.diff.compare.failed", null), e.getMessage()));
        }
    }

    private String readFileContent(String filePath) throws IOException {
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            URL url = new URL(filePath);
            try (InputStream is = url.openStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
                return baos.toString(StandardCharsets.UTF_8.name());
            }
        } else {
            String projectPath = System.getProperty("user.dir");
            return new String(Files.readAllBytes(
                    new File(projectPath + filePath).toPath()), StandardCharsets.UTF_8);
        }
    }
    /**
     * LCS diff：返回对齐后的行索引对
     * pair[0] = oldIndex（-1表示该行为新增）
     * pair[1] = newIndex（-1表示该行为删除）
     */
    private List<int[]> diffLines(String[] oldLines, String[] newLines) {
        int m = oldLines.length, n = newLines.length;

        // 构建 LCS 表
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // 回溯生成 diff 结果
        List<int[]> result = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                result.add(new int[]{i - 1, j - 1});
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result.add(new int[]{-1, j - 1}); // 新增行
                j--;
            } else {
                result.add(new int[]{i - 1, -1}); // 删除行
                i--;
            }
        }

        Collections.reverse(result);
        return result;
    }

    @Override
    public AjaxResult restoreXmlByIds(Long[] xmlIds) {
        int restoreRows = xmlFileMapper.restoreXmlByIds(xmlIds);
        Map<String, Object> result = new HashMap<>();
        result.put("restoreRows", restoreRows);
        return AjaxResult.success(result);
    }

    /**
     * 永久删除xml信息
     *
     * @param xmlIds 需要永久删除的xml主键集合
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int permanentlyDeleteXmlByIds(Long[] xmlIds) {
        // 1. 根据 xmlIds 查询对应的 file_path
        List<String> filePaths = xmlFileMapper.selectFilePathsByIds(xmlIds);

        // 2. 遍历 filePaths 删除文件
        String projectPath = System.getProperty("user.dir");
        for (String filePath : filePaths) {
            try {
                // HTTP URL 由文件服务器管理，跳过本地删除
                if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                    log.info("文件为远程URL，跳过本地删除: {}", filePath);
                    continue;
                }
                Path absolutePath = Paths.get(projectPath, filePath);
                if (Files.exists(absolutePath)) {
                    Files.delete(absolutePath);
                    log.info("成功删除文件: {}", absolutePath);
                } else {
                    log.warn("文件不存在，无法删除: {}", absolutePath);
                }
            } catch (Exception e) {
                log.error("删除文件失败: " + filePath, e);
                throw new RuntimeException("删除文件失败：" + filePath, e);
            }
        }
        xmlVersionMapper.deleteXmlVersionByFileId(xmlIds);
        return xmlFileMapper.permanentlyDeleteXmlByIds(xmlIds);
    }

    private void deleteFile(String filePath) {
        if (filePath == null) return;
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            // HTTP URL 文件由文件服务器管理，跳过本地删除
            log.info("文件为远程URL，跳过本地删除: {}", filePath);
            return;
        }
        // 本地文件才删除
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }
    }

    // =====================================================
    // 校验结果辅助方法
    // =====================================================

    /**
     * 将结构/格式校验错误包装为 FieldValidationResult（valid=false）。
     * fieldName 使用校验类别（FORMAT / STRUCTURE），value 留空，
     * violations 中存放一条同时包含英文和中文描述的 RuleViolation。
     *
     * @param category  校验类别，如 "FORMAT"、"STRUCTURE"
     * @param messageEn 英文错误描述
     * @param messageZh 中文错误描述
     */
    private FieldValidationResult buildStructureFieldResult(String category,
                                                            String messageEn,
                                                            String messageZh) {
        RuleViolation violation = RuleViolation.builder()
                .ruleId(category)
                .fieldName(category)
                .messageEn(messageEn)
                .messageZh(messageZh)
                .build();
        return FieldValidationResult.builder()
                .fieldName(category)
                .value(null)
                .valid(false)
                .violations(Collections.singletonList(violation))
                .build();
    }

    // =====================================================
    // 校验入口
    // =====================================================

    /**
     * 校验XML文件（三项校验），结果以 ValidationReport 返回：
     *  1. XML格式规范校验
     *  2. XML结构与模板层级一致性校验（含循环节点）
     *  3. 字段值规则校验（rule / range_rule）—— 委托 VehicleValidationService
     *
     * 结果组装规则：
     *  - 校验1、2 的每条错误包装为 FieldValidationResult，按顺序置于 fieldResults 最前。
     *  - 校验3 的 FieldValidationResult 追加在后。
     *  - allValid = 所有 fieldResults 均通过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ValidationReport validateXml(Long id) {
        // 前置：结构校验错误列表（校验1、2）
        List<FieldValidationResult> structureResults = new ArrayList<>();
        List<AbnormalClassify> abnormalClassifies = new ArrayList<>();
        AbnormalClassify abnormalClassify;
        try {
            // 1. 查询文件记录
            XmlFile xmlFile = xmlFileMapper.selectXmlFileById(id);
            if (xmlFile == null) {
                log.warn("校验失败：XML文件记录不存在，id={}", id);
                return ValidationReport.fail("XML文件记录不存在，id=" + id);
            }

            // 2. 获取XML内容（远程URL读取，与previewXml保持一致）
            String xmlContent;
            try {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(xmlFile.getFilePath())
                        .header("User-Agent", "Validator/1.0")
                        .build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("校验失败：无法获取XML文件内容，path={}", xmlFile.getFilePath());
                        return ValidationReport.fail("无法获取XML文件内容，path=" + xmlFile.getFilePath());
                    }
                    xmlContent = response.body().string();
                }
            } catch (Exception e) {
                log.error("获取XML文件内容失败", e);
                return ValidationReport.fail("获取XML文件内容异常：" + e.getMessage());
            }

            // ─────────────────────────────────────────────
            // 校验一：XML格式规范
            // ─────────────────────────────────────────────
            Document doc = validateXmlFormat(xmlContent, structureResults);

            ValidationReport dataReport = null;
            if (doc != null) {
                // ─────────────────────────────────────────────
                // 校验二：结构与模板层级一致性（含循环）
                // ─────────────────────────────────────────────
                validateXmlStructure(doc, xmlFile, structureResults);

                // ─────────────────────────────────────────────
                // 校验三：字段值规则（rule / range_rule）
                // ─────────────────────────────────────────────
                dataReport = validateXmlData(doc, xmlFile);
            }

            // ─────────────────────────────────────────────
            // 组装最终 ValidationReport
            // 校验1+2 结果在前，校验3 结果追加在后
            // ─────────────────────────────────────────────
            ValidationReport finalReport = ValidationReport.builder()
                    .allValid(true)
                    .fieldResults(new ArrayList<>())
                    .build();

            // 先放校验1+2的结果
            for (FieldValidationResult r : structureResults) {
                for (RuleViolation ruleViolation: r.getViolations()) {
                    ruleViolation.setRuleType(RuleItemType.STRUCTURE);
                }
                finalReport.addFieldResult(r);
            }

            // 再放校验3的结果
            if (dataReport != null && dataReport.getFieldResults() != null) {
                for (FieldValidationResult r : dataReport.getFieldResults()) {
                    finalReport.addFieldResult(r);
                }
                // 传递 vehicleCategory / stageOfCompletion（来自校验3）
                if (finalReport.getVehicleCategory() == null) {
                    finalReport.setVehicleCategory(dataReport.getVehicleCategory());
                }
                if (finalReport.getStageOfCompletion() == null) {
                    finalReport.setStageOfCompletion(dataReport.getStageOfCompletion());
                }
            }

            boolean validateResult = finalReport.isAllValid();

            // 汇总日志
            if (!validateResult) {
                long failCount = finalReport.getFieldResults().stream().filter(r -> !r.isValid()).count();
                log.warn("XML校验失败，共 {} 个字段不通过，id={}", failCount, id);
            } else {
                log.info("XML校验通过，id={}", id);
            }

            // 回写校验结果
            xmlFile.setValidateResult(validateResult ? 1 : 2);
            xmlFile.setUploadResult(validateResult ? "1" : "2");
            xmlFile.setValidationReportJson(objectMapper.writeValueAsString(finalReport));
            xmlFileMapper.updateXmlFile(xmlFile);

            // 记录生命周期
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setEntryId(xmlFile.getId());
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(xmlFile.getVin());
            vehicleLifecycle.setOperate(VehicleLifecycleOperation.XML_VALIDATE.getOperation());
            vehicleLifecycle.setResult(validateResult ? 0 : 1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            for (FieldValidationResult fieldValidationResult: finalReport.getFieldResults()) {
                for (RuleViolation ruleViolation: fieldValidationResult.getViolations()) {
                    abnormalClassify = new AbnormalClassify();
                    abnormalClassify.setEntryId(String.valueOf(id));
                    abnormalClassify.setEntryType("XML File");
                    abnormalClassify.setRuleType(RuleItemType.getRuleType(ruleViolation.getRuleType()));
                    abnormalClassifies.add(abnormalClassify);
                }
            }

            if (!abnormalClassifies.isEmpty()) {
                abnormalClassifyMapper.batchInsert(abnormalClassifies);
            }

            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(xmlFile.getId()));
            params.put("vin", xmlFile.getVin());
            params.put("modelCode", xmlFile.getModelCode());
            params.put("factoryCode", xmlFile.getFactoryCode());
            params.put("country", xmlFile.getCountry());
            params.put("validationResult", validateResult ? "1" : "2");
            params.put("issueDate", com.ruoyi.common.core.utils.DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", xmlFile.getIssueDate()));
            SysNotice sysNotice = new SysNotice();
            sysNotice.setModel(SysNoticeModel.XML_FILE.getModel());
            sysNotice.setQueryParams(JSON.toJSONString(params));
            sysNotice.setIsRead(false);
            sysNotice.setNoticeType("1");
            sysNotice.setNoticeTitle("XML文件校验完成通知");
            String msg =
                    "由车辆VIN " +
                            xmlFile.getVin() +
                            "生成的XML文件的校验结果为: " +
                            (finalReport.isAllValid() ? "通过" : "失败");
            sysNotice.setNoticeContent(msg);
            sysNotice.setCreateBy("自动提醒");
            sysNotice.setCreateTime(new Date());
            sysNotice.setSorts(Arrays.asList(12, 13));
            remoteNoticeService.innerAdd(sysNotice);

            return finalReport;
        } catch (Exception e) {
            log.error("校验XML文件失败", e);
            return ValidationReport.fail("校验XML文件异常：" + e.getMessage());
        }
    }

    // =====================================================
    // 校验一：XML格式规范
    // =====================================================

    /**
     * 校验XML是否符合格式规范（能否被解析为合法DOM）。
     * 成功返回解析后的Document，失败返回null并向results中追加FieldValidationResult。
     * ★ 改造：使用自定义 ErrorHandler 收集全部 SAX 错误（不在首个错误处停止），
     *         每条错误均明确行号、列号及原因，messageEn / messageZh 双语输出。
     */
    private Document validateXmlFormat(String xmlContent, List<FieldValidationResult> results) {
        // 用于收集解析过程中的全部错误
        List<org.xml.sax.SAXParseException> parseErrors = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();

            // ★ 改造：不抛出异常，而是将每条错误/致命错误都收集到 parseErrors
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                    // warning 不计入格式错误，忽略
                }
                @Override
                public void error(org.xml.sax.SAXParseException e) {
                    parseErrors.add(e);
                }
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) {
                    parseErrors.add(e);
                }
            });

            Document doc = null;
            try {
                doc = builder.parse(new InputSource(new StringReader(xmlContent)));
            } catch (org.xml.sax.SAXParseException e) {
                // 部分解析器在 fatalError 后仍会抛出，确保也被收集
                if (parseErrors.stream().noneMatch(ex ->
                        ex.getLineNumber() == e.getLineNumber()
                                && ex.getColumnNumber() == e.getColumnNumber())) {
                    parseErrors.add(e);
                }
            }

            // 将所有收集到的错误写入 results
            for (org.xml.sax.SAXParseException e : parseErrors) {
                String msgEn = String.format(
                        "Invalid XML format at line %d, column %d: %s",
                        e.getLineNumber(), e.getColumnNumber(), e.getMessage());
                String msgZh = String.format(
                        "XML格式不合法，第 %d 行第 %d 列：%s",
                        e.getLineNumber(), e.getColumnNumber(), e.getMessage());
                log.warn("XML格式错误：{}", msgZh);
                results.add(buildStructureFieldResult("FORMAT", msgEn, msgZh));
            }

            // 有任何格式错误则返回 null，阻止后续校验
            if (!parseErrors.isEmpty()) {
                return null;
            }
            return doc;

        } catch (SAXException e) {
            String msgEn = "Invalid XML format (parse error): " + e.getMessage();
            String msgZh = "XML格式不合法（解析错误）：" + e.getMessage();
            log.warn("XML格式校验失败：{}", msgZh);
            results.add(buildStructureFieldResult("FORMAT", msgEn, msgZh));
            return null;

        } catch (Exception e) {
            String msgEn = "XML parsing exception: " + e.getMessage();
            String msgZh = "XML解析异常：" + e.getMessage();
            results.add(buildStructureFieldResult("FORMAT", msgEn, msgZh));
            return null;
        }
    }

    // =====================================================
    // 校验二：结构与模板层级一致性
    // =====================================================

    /**
     * 校验XML结构是否与模板定义一致。
     * 算法：将模板属性路径构成的"标签路径树"与XML DOM路径逐一比对。
     * 对循环节点：允许同一个标签在同一父节点下出现多次（≥1次即合法）。
     */
    private void validateXmlStructure(Document doc, XmlFile xmlFile,
                                      List<FieldValidationResult> results) {
        try {
            // 1. 匹配模板
            xmlFile = xmlFileMapper.selectXmlFileById(xmlFile.getId());
            if (xmlFile == null) {
                throw new ServiceException("该文件不存在");
            }
            String vin = xmlFile.getFileName().split("_")[1];
            vin = vin.split("\\.")[0];
            VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoByVin(vin);
            if (vehicle == null) {
                results.add(buildStructureFieldResult("STRUCTURE",
                        "No associated vehicle information found; structure validation skipped",
                        "未找到关联车辆信息，无法进行结构校验"));
                return;
            }

            XmlTemplate template = matchTemplate(vehicle);
            if (template == null) {
                results.add(buildStructureFieldResult("STRUCTURE",
                        "No matching XML template found; structure validation skipped",
                        "未找到匹配的XML模板，无法进行结构校验"));
                return;
            }

            // 2. 查询模板属性及字典
            List<XmlTemplateAttribute> attrList =
                    xmlTemplateAttributeMapper.selectByTemplateId(template.getTemplateId());
            if (attrList == null || attrList.isEmpty()) return;

            List<SysDictData> dictDataList = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            Map<String, SysDictData> dictCodeMap = new HashMap<>();
            for (SysDictData d : dictDataList) {
                if (d.getDictCode() != null) {
                    dictCodeMap.put(String.valueOf(d.getUuid()), d);
                }
            }

            // 3. 构建"模板标签路径 → 是否循环节点"映射
            //    循环节点定义：该路径对应dict_value='NULL'且其子孙叶子节点中至少一个值含分号
            Map<String, String> attrPathToTagName = new LinkedHashMap<>(); // attrPath → tagName
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                if (d != null && StringUtils.isNotBlank(d.getDictLabel())) {
                    attrPathToTagName.put(attr.getAttrPath(), sanitizeXmlTagName(d.getDictLabel()));
                }
            }

            // 4. 识别循环容器路径集合（与生成逻辑保持一致）
            Set<String> loopContainerPaths = resolveLoopContainerPaths(
                    attrList, dictCodeMap, vehicle.getJsonMap() != null ? vehicle.getJsonMap() : new HashMap<>());

            // 5. 构建"标签层级路径（tagPath）→ 是否循环"查找表
            //    tagPath = 从根标签到当前标签的层级，如 "Root/ManufacturerTable/ManufacturerGroup"
            //    ★修复：先按路径深度升序排序，保证父节点一定先于子节点处理，
            //           否则子节点处理时 attrPathToTagPath 中还没有父节点记录，导致 tagPath 断链
            //           （如 1058.37.39.40 先于 1058.37.39 处理时 ManufacturerGroup 变成孤立短路径）
            final String INVALID = "__INVALID__";
            List<XmlTemplateAttribute> sortedAttrList = attrList.stream()
                    .sorted(Comparator.comparingInt(a -> a.getAttrPath().split("\\.").length))
                    .collect(Collectors.toList());
            Map<String, Boolean> tagPathIsLoop = new LinkedHashMap<>();
            // tagPath → is_required（true=必须，false=非必须）
            Map<String, Boolean> tagPathIsRequired = new HashMap<>();
            Map<String, String> attrPathToTagPath = new HashMap<>();
            for (XmlTemplateAttribute attr : sortedAttrList) {
                String attrPath = attr.getAttrPath();
                String tagName = attrPathToTagName.get(attrPath);
                String parentAttrPath = getParentPath(attrPath);
                String parentTagPath = attrPathToTagPath.getOrDefault(parentAttrPath, "");
                // ★ 父节点无效，子树全部跳过
                if (INVALID.equals(parentTagPath)) {
                    attrPathToTagPath.put(attrPath, INVALID);
                    continue;
                }
                // ★ 当前节点字典缺失，标记无效并跳过
                if (tagName == null) {
                    attrPathToTagPath.put(attrPath, INVALID);
                    continue;
                }
                String tagPath = parentTagPath.isEmpty() ? tagName : parentTagPath + "/" + tagName;
                attrPathToTagPath.put(attrPath, tagPath);
                boolean isLoop = loopContainerPaths.contains(attrPath);
                tagPathIsLoop.put(tagPath, isLoop);
                // 记录 is_required：同一 tagPath 只要有一个 required=1 则为必须
                boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
                tagPathIsRequired.merge(tagPath, required, (a, b) -> a || b);
            }

            // 6. 对XML DOM做DFS遍历，按层级路径逐节点与模板比对
            Element root = doc.getDocumentElement();
            checkElementStructure(root, "", tagPathIsLoop, tagPathIsRequired, results);

        } catch (Exception e) {
            results.add(buildStructureFieldResult("STRUCTURE",
                    "Structure validation exception: " + e.getMessage(),
                    "结构校验异常：" + e.getMessage()));
            log.error("XML结构校验失败", e);
        }
    }

    /**
     * 递归校验Element是否在模板定义的tagPath集合中。
     * @param element          当前DOM节点
     * @param parentPath       当前节点的父级tagPath（空字符串表示在根之上）
     * @param tagPathIsLoop    模板tagPath → 是否循环容器
     * @param tagPathIsRequired 模板tagPath → is_required（true=必须存在，false=非必须）
     * @param results          校验结果收集列表
     * ★ 改造：遇到未定义标签不再提前 return，继续处理其余兄弟节点，
     *         确保同级所有问题全部写入报告；三类错误均包含完整路径信息，双语输出。
     * ★ is_required=0 的模板节点在XML中缺失时视为通过（不报错）。
     */
    private void checkElementStructure(Element element, String parentPath,
                                       Map<String, Boolean> tagPathIsLoop,
                                       Map<String, Boolean> tagPathIsRequired,
                                       List<FieldValidationResult> results) {
        String tagName = element.getTagName();
        String currentPath = parentPath.isEmpty() ? tagName : parentPath + "/" + tagName;

        if (!tagPathIsLoop.containsKey(currentPath)) {
            results.add(buildStructureFieldResult("STRUCTURE",
                    String.format("Unexpected tag <%s> at path \"%s\": not defined in template (redundant or wrong hierarchy level)",
                            tagName, currentPath),
                    String.format("标签 <%s> 不在模板定义中（路径：%s），属于多余节点或层级位置错误",
                            tagName, currentPath)));
            return;
        }

        // 判断当前节点自身是否为循环节点（在父节点下可重复出现）
        boolean currentNodeIsLoop = Boolean.TRUE.equals(tagPathIsLoop.get(currentPath));

        // 对子节点按标签名分组，循环节点允许重复出现
        NodeList children = element.getChildNodes();
        Map<String, Integer> childTagCount = new LinkedHashMap<>();
        List<Element> childElements = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                Element childEl = (Element) n;
                childElements.add(childEl);
                childTagCount.merge(childEl.getTagName(), 1, Integer::sum);
            }
        }

        // ★ 检查同一父节点下的同名子标签：只有模板中为循环节点时才允许重复；
        //    循环节点出现多次时生成 valid=true 的报告（通过但有记录）
        for (Map.Entry<String, Integer> entry : childTagCount.entrySet()) {
            if (entry.getValue() > 1) {
                String childPath = currentPath + "/" + entry.getKey();
                Boolean isLoop = tagPathIsLoop.get(childPath);
                if (!Boolean.TRUE.equals(isLoop)) {
                    // 非循环节点重复 → 校验失败
                    results.add(buildStructureFieldResult("STRUCTURE",
                            String.format("Tag <%s> appears %d times under <%s> (path: \"%s\"), but template defines it as non-repeatable (expected exactly 1)",
                                    entry.getKey(), entry.getValue(), tagName, childPath),
                            String.format("标签 <%s> 在父节点 <%s> 下出现 %d 次（路径：%s），但模板定义为非循环节点（期望唯一）",
                                    entry.getKey(), tagName, entry.getValue(), childPath)));
                } else {
                    // ★ 循环节点重复出现 → valid=true，仅作信息记录
                    RuleViolation infoViolation = RuleViolation.builder()
                            .ruleId("STRUCTURE_LOOP_INFO")
                            .fieldName("STRUCTURE_LOOP_INFO")
                            .messageEn(String.format(
                                    "Loop tag <%s> appears %s times under <%s> (path: \"%s\") — validation passed",
                                    entry.getKey(), entry.getValue(), tagName, childPath))
                            .messageZh(String.format(
                                    "循环标签 <%s> 在父节点 <%s> 下出现 %s 次（路径：%s）——校验通过",
                                    entry.getKey(), entry.getValue(), tagName, childPath))
                            .build();
                    results.add(FieldValidationResult.builder()
                            .fieldName("STRUCTURE_LOOP_INFO")
                            .value(null)
                            .valid(true)
                            .violations(Collections.singletonList(infoViolation))
                            .build());
                }
            }
        }

        // ★ 检查模板要求的子节点是否全部存在
        //    ★修复问题1：若当前节点自身是循环节点且为空实例（无子元素），跳过"缺少子节点"检查——
        //               空的循环实例（如多余的 <ManufacturerGroup/>）是合法的，不应报缺少子节点
        if (!(currentNodeIsLoop && childElements.isEmpty())) {
            for (Map.Entry<String, Boolean> entry : tagPathIsLoop.entrySet()) {
                String templateChildPath = entry.getKey();
                if (!templateChildPath.startsWith(currentPath + "/")) continue;
                String remainder = templateChildPath.substring(currentPath.length() + 1);
                if (remainder.contains("/")) continue;
                String expectedChildTag = remainder;
                if (!childTagCount.containsKey(expectedChildTag)) {
                    boolean childIsLoop = Boolean.TRUE.equals(entry.getValue());
                    // ★ 判断该子节点是否为 is_required=1
                    boolean childIsRequired = Boolean.TRUE.equals(tagPathIsRequired.get(templateChildPath));
                    if (childIsLoop) {
                        // 循环节点缺失：valid=true，仅作警告记录
                        RuleViolation warnViolation = RuleViolation.builder()
                                .ruleId("STRUCTURE_LOOP_WARN")
                                .fieldName("STRUCTURE_LOOP_WARN")
                                .messageEn(String.format(
                                        "Loop tag <%s> has no instances under \"%s\" (path: \"%s\") — validation passed, loop data may be absent",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag))
                                .messageZh(String.format(
                                        "循环标签 <%s> 在父节点 \"%s\" 下无实例（路径：%s）——校验通过，循环数据可能为空",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag))
                                .build();
                        results.add(FieldValidationResult.builder()
                                .fieldName("STRUCTURE_LOOP_WARN")
                                .value(null)
                                .valid(true)
                                .violations(Collections.singletonList(warnViolation))
                                .build());
                    } else if (!childIsRequired) {
                        // ★ is_required=0 的非循环节点缺失 → 视为通过，仅作可选节点信息记录
                        RuleViolation optionalViolation = RuleViolation.builder()
                                .ruleId("STRUCTURE_OPTIONAL_ABSENT")
                                .fieldName("STRUCTURE_OPTIONAL_ABSENT")
                                .messageEn(String.format(
                                        "Optional tag <%s> is absent under \"%s\" (path: \"%s\") — validation passed (is_required=0)",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag))
                                .messageZh(String.format(
                                        "可选标签 <%s> 在父节点 \"%s\" 下不存在（路径：%s）——校验通过（is_required=0）",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag))
                                .build();
                        results.add(FieldValidationResult.builder()
                                .fieldName("STRUCTURE_OPTIONAL_ABSENT")
                                .value(null)
                                .valid(true)
                                .violations(Collections.singletonList(optionalViolation))
                                .build());
                    } else {
                        // is_required=1 的非循环节点缺失 → 校验失败
                        results.add(buildStructureFieldResult("STRUCTURE",
                                String.format("Missing required tag <%s> under \"%s\" (expected path: \"%s\")",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag),
                                String.format("缺少必须的标签 <%s>，父节点路径：%s（期望完整路径：%s，is_required=1）",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag)));
                    }
                }
            }
        }

        // ★ 递归处理全部子节点
        for (Element childEl : childElements) {
            checkElementStructure(childEl, currentPath, tagPathIsLoop, tagPathIsRequired, results);
        }
    }

    /**
     * 解析出所有循环节点的 attrPath 集合，供结构校验使用（与生成逻辑 detectLoopPattern 保持一致）。
     *
     * 循环模式与循环节点的对应关系：
     *  - PARENT_LEVEL（上级循环，如 HEV1:xxx;HEV2:xxx 前缀格式）：
     *      loopContainerPath（如 ManufacturerTable）本身就是循环节点——每个前缀生成一个实例。
     *  - SIBLING_LEVEL（同级循环，如 北京;柏林 无前缀分号格式）：
     *      loopContainerPath 下包含触发字段的直接子结构（如 ManufacturerGroup）是循环节点。
     */
    private Set<String> resolveLoopContainerPaths(List<XmlTemplateAttribute> attrList,
                                                  Map<String, SysDictData> dictCodeMap,
                                                  Map<String, Object> jsonMap) {
        Set<String> result = new HashSet<>();

        // ★ 修复：遍历【所有】含 ; 或 | 的叶子字段，逐一推断其所属 Group 循环节点，
        //   而不只依赖 detectLoopPattern 返回的单一触发字段。
        //   原因：detectLoopPattern 只选“最浅”的触发字段，导致深层的嵌套循环
        //   （如 GearRatioGroup）在校验时被误判为“非循环节点”而报错。
        for (XmlTemplateAttribute leaf : attrList) {
            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            if (d == null || isStructNode(d)) continue;
            if (StringUtils.isBlank(d.getDictLabel())) continue;

            Object raw = jsonMap.get(d.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString().trim();

            boolean hasPipe = val.contains("|");
            boolean hasSemi = !hasPipe && val.contains(";");
            if (!hasPipe && !hasSemi) continue;

            // 判断是否前缀格式（PARENT_LEVEL：每段均含 :)
            String separator = hasPipe ? "\\|" : ";";
            String[] items = val.split(separator, -1);
            boolean allHavePrefix = items.length > 0 && Arrays.stream(items)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .allMatch(s -> s.contains(":"));

            if (allHavePrefix) {
                // PARENT_LEVEL：loopContainerPath（Table 层）本身是循环节点
                String containerPath = getParentPath(getParentPath(leaf.getAttrPath()));
                if (StringUtils.isNotBlank(containerPath)) {
                    result.add(containerPath);
                }
            } else {
                // SIBLING_LEVEL：leaf 直接父节点（Group 层）是循环节点
                String groupPath = getParentPath(leaf.getAttrPath());
                if (StringUtils.isBlank(groupPath)) continue;
                String[] groupParts = groupPath.split("\\.");
                SysDictData groupDict = dictCodeMap.get(groupParts[groupParts.length - 1]);
                if (groupDict != null && isStructNode(groupDict)) {
                    result.add(groupPath);
                }
            }
        }

        log.info("=== resolveLoopContainerPaths result: {}", result);
        return result;
    }

    // =====================================================
    // 校验三：字段值规则（委托 VehicleValidationServiceImpl）
    // =====================================================

    /**
     * 校验XML中各叶子节点的值是否符合 sys_dict_data 中 rule/range_rule 规则。
     *
     * 策略：
     * 1. 遍历模板叶子节点，从 XML DOM 中读取每个标签的当前值。
     * 2. 以 dictData.keyMap 为键、XML 中读取到的值为 value，重建一个 jsonMap。
     *    ★ 改造：将所有有 keyMap 的字段（含无 rule/rangeRule 字段）全部放入 jsonMap，
     *           确保条件规则（MANDATORY_IF / FORBIDDEN_IF 等）的上下文字段不缺失，
     *           规则引擎可对全量字段执行校验，报告中包含所有不通过项。
     * 3. 将 jsonMap 序列化为 JSON 字符串，连同 vehicleCategory/stageOfCompletion
     *    一起调用 vehicleValidationService.validate()，复用已有的规则引擎完成校验。
     * 4. 直接返回 ValidationReport，由调用方追加到最终报告中。
     */
    private ValidationReport validateXmlData(Document doc, XmlFile xmlFile) {
        try {
            xmlFile = xmlFileMapper.selectXmlFileById(xmlFile.getId());
            if (xmlFile == null) throw new ServiceException("该文件不存在");

            String vin = xmlFile.getFileName().split("_")[1].split("\\.")[0];
            VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoByVin(vin);
            if (vehicle == null) return null;

            XmlTemplate template = matchTemplate(vehicle);
            if (template == null) return null;

            List<XmlTemplateAttribute> attrList =
                    xmlTemplateAttributeMapper.selectByTemplateId(template.getTemplateId());
            if (attrList == null || attrList.isEmpty()) return null;

            List<SysDictData> dictDataList =
                    remoteDictService.getDictDataByType("vehicle_attribute").getData();

            Map<String, SysDictData> labelToDictMap = new HashMap<>();
            Map<String, SysDictData> dictCodeMap    = new HashMap<>();
            for (SysDictData d : dictDataList) {
                if (d.getDictCode() != null) {
                    dictCodeMap.put(String.valueOf(d.getUuid()), d);
                }
                if (d.getDictLabel() != null) {
                    labelToDictMap.put(sanitizeXmlTagName(d.getDictLabel()), d);
                }
            }

            Map<String, List<String[]>> keyMapMeta = buildKeyMapMeta(dictCodeMap);

            // 提取 vehicleCategory / stageOfCompletion
            String vehicleCategory   = extractTextByKeyMap(doc, dictCodeMap, attrList, "VehicleCategory");
            String stageOfCompletion = extractTextByKeyMap(doc, dictCodeMap, attrList, "StageOfCompletion");
            if (vehicleCategory == null && vehicle.getJsonMap() != null) {
                Object v = vehicle.getJsonMap().get("VehicleCategory");
                vehicleCategory = v != null ? v.toString() : null;
            }
            if (stageOfCompletion == null && vehicle.getJsonMap() != null) {
                Object v = vehicle.getJsonMap().get("StageOfCompletion");
                stageOfCompletion = v != null ? v.toString() : null;
            }

            // ── 第一步：识别循环节点（与原逻辑相同）──────────────────────────
            NodeList allNodes = doc.getElementsByTagName("*");
            Map<String, Integer> parentTagCount = new LinkedHashMap<>();
            Set<String> loopTagNames = new HashSet<>();
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element element = (Element) allNodes.item(i);
                String tagName = element.getTagName();
                Node parent = element.getParentNode();
                String key = System.identityHashCode(parent) + "#" + tagName;
                int count = parentTagCount.merge(key, 1, Integer::sum);
                if (count > 1) {
                    loopTagNames.add(tagName);
                }
            }

            // ── 第二步：构建非循环节点的 baseJsonMap（与原逻辑相同）───────────
            Map<String, Object> baseJsonMap = new LinkedHashMap<>();
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element element = (Element) allNodes.item(i);
                String tagName  = element.getTagName();
                SysDictData dict = labelToDictMap.get(tagName);
                if (dict == null || isStructNode(dict) || StringUtils.isBlank(dict.getKeyMap())) continue;

                if (!loopTagNames.contains(tagName)) {
                    baseJsonMap.put(dict.getKeyMap(),
                            StringUtils.defaultString(element.getTextContent()));
                }
            }

            // ── ★ 第三步（新增）：将循环节点收集为列表，注入 baseJsonMap ──────
            //
            // 目标结构（以 AxleGroup 为例）：
            //   baseJsonMap.put("AxleGroup", [
            //       {"AxleOfNumber": "2"},
            //       {"AxleOfNumber": "2"},
            //       {"AxleOfNumber": "2"}
            //   ])
            //
            // key 规则：
            //   - 循环节点本身是结构节点（isStructNode），以其 dictLabel（即 tagName）为 key
            //   - 这与 VehicleFieldParser.parseListFieldsFromMap 期望的 key 一致
            //
            // 遍历思路：
            //   找出所有「结构型循环节点」（即 isStructNode 且在 loopTagNames 中），
            //   对每个实例，将其直接子叶子节点的 keyMap→textContent 收集为一个 Map，
            //   所有实例聚合为 List<Map<String,Object>>。

            // 收集结构型循环节点的 tagName 集合（AxleGroup、TyreAxleGroup 等）
            Set<String> processedStructLoopTags = new HashSet<>();
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element element = (Element) allNodes.item(i);
                String tagName = element.getTagName();

                // 只处理：① 在 loopTagNames 中 ② 是结构节点 ③ 字典有记录
                if (!loopTagNames.contains(tagName)) continue;
                SysDictData dict = labelToDictMap.get(tagName);
                if (dict == null || !isStructNode(dict)) continue;
                if (processedStructLoopTags.contains(tagName)) continue;
                processedStructLoopTags.add(tagName);

                // 收集该 tagName 的所有实例
                NodeList instances = doc.getElementsByTagName(tagName);
                List<Map<String, Object>> rowList = new ArrayList<>();

                for (int j = 0; j < instances.getLength(); j++) {
                    Element instance = (Element) instances.item(j);
                    Map<String, Object> rowMap = new LinkedHashMap<>();

                    // 遍历该实例的直接子节点，取叶子字段
                    NodeList children = instance.getChildNodes();
                    for (int k = 0; k < children.getLength(); k++) {
                        Node child = children.item(k);
                        if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element childEl = (Element) child;
                        String childTagName = childEl.getTagName();
                        SysDictData childDict = labelToDictMap.get(childTagName);
                        if (childDict == null || isStructNode(childDict)
                                || StringUtils.isBlank(childDict.getKeyMap())) continue;

                        rowMap.put(childDict.getKeyMap(),
                                StringUtils.defaultString(childEl.getTextContent()));
                    }

                    if (!rowMap.isEmpty()) {
                        rowList.add(rowMap);
                    }
                }

                if (!rowList.isEmpty()) {
                    // key 使用 tagName（即 dictLabel），与 VehicleFieldParser 期望一致
                    baseJsonMap.put(tagName, rowList);
                    log.debug("★ 注入循环列表到 baseJsonMap: key={}, size={}", tagName, rowList.size());
                }
            }

            // ── 第四步：非循环节点整体校验（带上循环列表上下文）────────────────
            ValidationReport mergedReport = null;
            if (!baseJsonMap.isEmpty()) {
                String jsonStr = new ObjectMapper().writeValueAsString(baseJsonMap);
                ValidationReport report = vehicleValidationService.validate(
                        jsonStr, vehicleCategory, stageOfCompletion);
                mergedReport = enrichAndMerge(mergedReport, report, keyMapMeta);
            }

            // ── 第五步：循环节点逐个校验（与原逻辑相同）────────────────────────
            Set<String> processedLoopTags = new HashSet<>();
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element element = (Element) allNodes.item(i);
                String tagName  = element.getTagName();
                if (!loopTagNames.contains(tagName)) continue;
                if (processedLoopTags.contains(tagName)) continue;
                processedLoopTags.add(tagName);

                SysDictData dict = labelToDictMap.get(tagName);
                if (dict == null || isStructNode(dict) || StringUtils.isBlank(dict.getKeyMap())) continue;

                NodeList loopNodes = doc.getElementsByTagName(tagName);
                for (int j = 0; j < loopNodes.getLength(); j++) {
                    String value = StringUtils.defaultString(
                            loopNodes.item(j).getTextContent());

                    Map<String, Object> singleJsonMap = new LinkedHashMap<>(baseJsonMap);
                    singleJsonMap.put(dict.getKeyMap(), value);

                    String jsonStr = new ObjectMapper().writeValueAsString(singleJsonMap);
                    ValidationReport report = vehicleValidationService.validate(
                            jsonStr, vehicleCategory, stageOfCompletion);

                    if (report != null && report.getFieldResults() != null) {
                        final int index = j + 1;
                        for (FieldValidationResult fr : report.getFieldResults()) {
                            if (!fr.isValid() && fr.getViolations() != null) {
                                for (RuleViolation v : fr.getViolations()) {
                                    appendLoopIndex(v, tagName, index);
                                }
                            }
                        }
                    }
                    mergedReport = enrichAndMerge(mergedReport, report, keyMapMeta);
                }
            }

            return mergedReport;

        } catch (Exception e) {
            log.error("XML数据校验失败", e);
            return ValidationReport.fail("数据规则校验异常：" + e.getMessage());
        }
    }

    /**
     * 从 XML DOM 中按 keyMap 对应的 dictLabel 标签名读取文本值。
     * 用于提取 vehicleCategory / stageOfCompletion 等上下文字段。
     *
     * @param keyMapValue 字典中 keyMap 列的值（如 "vehicleCategory"）
     * @return 标签的文本内容，找不到时返回 null
     */
    private String extractTextByKeyMap(Document doc,
                                       Map<String, SysDictData> dictCodeMap,
                                       List<XmlTemplateAttribute> attrList,
                                       String keyMapValue) {
        for (XmlTemplateAttribute attr : attrList) {
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || !keyMapValue.equals(dict.getKeyMap())) continue;
            String tagName = sanitizeXmlTagName(dict.getDictLabel());
            NodeList nodeList = doc.getElementsByTagName(tagName);
            if (nodeList.getLength() > 0) {
                String v = nodeList.item(0).getTextContent();
                if (StringUtils.isNotBlank(v)) return v;
            }
        }
        return null;
    }

    /**
     * 循环模式枚举
     * NONE- 无循环，普通树结构
     * PARENT_LEVEL - 上级循环：所有含分号的字段都是"前缀:值"格式，在父容器级别循环
     * SIBLING_LEVEL- 同级循环：至少一个字段是"值;值"无前缀格式，在子结构级别循环
     */
    private enum LoopMode {
        NONE, PARENT_LEVEL, SIBLING_LEVEL
    }

    /**
     * 循环检测结果
     */
    @Data
    private static class LoopDetectionResult {
        private LoopMode loopMode = LoopMode.NONE;
        private List<String> groupKeys = new ArrayList<>();
        private XmlTemplateAttribute triggerAttr;
        private String loopContainerPath;
        private int maxRows = 1;
    }

    /**
     * 从数据库生成XML文件
     * 规则：
     * 1. json值中含分号 → 触发循环
     * 2. 所有含分号字段均为"前缀:值"格式 → 上级循环（父容器级别）
     * 3. 至少一个含分号字段为"值;值"无前缀格式 → 同级循环（子结构级别）
     *
     * ★ 改动：生成逻辑直接使用 dictLabel 匹配 jsonMap，不再通过 keyMap 取值
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateXmlFromDatabase(Long vehicleId) {
        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoById(vehicleId);
        if (vehicle == null) {
            throw new RuntimeException("车辆信息不存在");
        }
        if (vehicle.getStatus().equals(1)) {
            throw new RuntimeException("车辆信息已停用");
        }
        checkGeneratePermission(vehicle);

        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("XML文件生成通知");
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());
        StringBuilder msg = new StringBuilder();
        msg.append(System.lineSeparator());
        msg.append("车辆vin ");
        msg.append(vehicle.getVin());
        msg.append("生成XML文件的结果为: ");
        Map<String, String> vehicleParams = getVehicleParams(vehicle);

        try {
            Map<String, Object> jsonMap = vehicle.getJsonMap();
            jsonMap.put("IntendedCountryRegistration", vehicle.getCountry());
            jsonMap.put("IviReferenceId", UUID.randomUUID().toString());
            // IviVersionDateTime 是 DateTime 类型，需要带时区的完整格式
            jsonMap.put("IviVersionDateTime", DateUtils.format(new Date(), "yyyy-MM-dd'T'HH:mm:ss'Z'"));

            // DateManufactureVehicle 和 SignatureDate 是 Date 类型，只需年月日
            if (vehicle.getManufactureDate() != null) {
                jsonMap.put("DateManufactureVehicle", DateUtils.format(vehicle.getManufactureDate(), "yyyy-MM-dd"));
            }
            if (vehicle.getIssueDate() != null) {
                jsonMap.put("SignatureDate", DateUtils.format(vehicle.getIssueDate(), "yyyy-MM-dd"));
                // 同时写入 TypeApprovalIssueDate
                jsonMap.put("TypeApprovalIssueDate", DateUtils.format(vehicle.getIssueDate(), "yyyy-MM-dd"));
            }
            if (StringUtils.isBlank((String) (jsonMap.get("SignatureDate")))) {
                jsonMap.put("SignatureDate", DateUtils.format(new Date(), "yyyy-MM-dd"));
            }
            // 2.匹配模板
            XmlTemplate xmlTemplate = matchTemplate(vehicle);
            if (xmlTemplate == null) {
                msg.append("失败");
                sysNotice.setQueryParams(JSON.toJSONString(vehicleParams));
                sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
                sysNotice.setNoticeContent(msg.toString());
                sysNotice.setSorts(Arrays.asList(14, 15));
                remoteNoticeService.innerAdd(sysNotice);
                throw new RuntimeException("未找到匹配的XML模板，VIN=" + vehicle.getVin());
            }

            // 3. 查询字典数据，构建 uuid -> SysDictData 映射
            List<SysDictData> dictDataList = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            Map<String, SysDictData> dictCodeMap = new HashMap<>(); // key 为 uuid
            for (SysDictData d : dictDataList) {
                if (d.getUuid() != null) {
                    dictCodeMap.putIfAbsent(d.getUuid(), d);   // ★ uuid 为 key，一个uuid多行取第一个
                }
            }

            // 4. 查询模板属性列表
            List<XmlTemplateAttribute> attrList = xmlTemplateAttributeMapper.selectByTemplateId(xmlTemplate.getTemplateId());
            if (attrList == null || attrList.isEmpty()) {
                msg.append("失败");
                sysNotice.setQueryParams(JSON.toJSONString(vehicleParams));
                sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
                sysNotice.setNoticeContent(msg.toString());
                sysNotice.setSorts(Arrays.asList(14, 15));
                remoteNoticeService.innerAdd(sysNotice);
                throw new ServiceException("模板无属性定义，无法生成XML");
            }

            // 5. 单根节点校验
            List<XmlTemplateAttribute> topLevelAttrs = attrList.stream()
                    .filter(a -> a.getAttrPath() != null && a.getAttrPath().split("\\.").length == 1)
                    .collect(Collectors.toList());
            if (topLevelAttrs.isEmpty()) {
                msg.append("失败");
                sysNotice.setQueryParams(JSON.toJSONString(vehicleParams));
                sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
                sysNotice.setNoticeContent(msg.toString());
                sysNotice.setSorts(Arrays.asList(14, 15));
                remoteNoticeService.innerAdd(sysNotice);
                throw new ServiceException("模板无顶层节点，XML必须有唯一根节点");
            }
            if (topLevelAttrs.size() > 1) {
                msg.append("失败");
                sysNotice.setQueryParams(JSON.toJSONString(vehicleParams));
                sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
                sysNotice.setNoticeContent(msg.toString());
                sysNotice.setSorts(Arrays.asList(14, 15));
                remoteNoticeService.innerAdd(sysNotice);
                throw new ServiceException("模板存在多个顶层节点，XML 不允许多根节点");
            }

            // 6. 按模板定义顺序排序
            Map<String, Integer> pathSortOrderMap = new HashMap<>();
            for (XmlTemplateAttribute a : attrList) {
                if (a.getAttrPath() != null) {
                    pathSortOrderMap.put(a.getAttrPath(), a.getSortOrder() != null ? a.getSortOrder() : 0);
                }
            }
            Comparator<XmlTemplateAttribute> templateOrderComparator = (a, b) -> {
                String[] partsA = a.getAttrPath().split("\\.");
                String[] partsB = b.getAttrPath().split("\\.");
                int minLen = Math.min(partsA.length, partsB.length);
                StringBuilder prefixA = new StringBuilder();
                StringBuilder prefixB = new StringBuilder();
                for (int idx = 0; idx < minLen; idx++) {
                    if (idx > 0) { prefixA.append("."); prefixB.append("."); }
                    prefixA.append(partsA[idx]);
                    prefixB.append(partsB[idx]);
                    int soA = pathSortOrderMap.getOrDefault(prefixA.toString(), 0);
                    int soB = pathSortOrderMap.getOrDefault(prefixB.toString(), 0);
                    if (soA != soB) return Integer.compare(soA, soB);
                }
                return Integer.compare(partsA.length, partsB.length);
            };
            attrList.sort(templateOrderComparator);

            // 7. 创建XML文档
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            // 8. 创建根节点，将 defaultValue 作为属性写入标签（如 <Root xmlns="123">）
            XmlTemplateAttribute rootAttr = topLevelAttrs.get(0);
            String rootAttrPath = rootAttr.getAttrPath();
            SysDictData rootDict = dictCodeMap.get(rootAttrPath);
            String rootTagName = (rootDict != null && StringUtils.isNotBlank(rootDict.getDictLabel()))
                    ? sanitizeXmlTagName(rootDict.getDictLabel()) : "Root";
            // 创建根节点：若 defaultValue 含 xmlns 则用 createElementNS，保证命名空间正确
            String rootNsUri = extractNamespaceUri(rootAttr.getDefaultValue());
            Element root = createElementWithDefault(doc, rootTagName, rootAttr.getDefaultValue());
            doc.appendChild(root);

            // 9. 路径 -> Element 映射（记录已创建的节点）
            Map<String, Element> pathNodeMap = new LinkedHashMap<>();
            pathNodeMap.put(rootAttrPath, root);

            // 10. 识别所有结构节点（dict_value = "NULL"，表示容器节点，不含实际值）
            Set<String> structNodePaths = attrList.stream()
                    .filter(a -> {
                        String[] parts = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                        return d != null && isStructNode(d);
                    })
                    .map(XmlTemplateAttribute::getAttrPath)
                    .collect(Collectors.toSet());

            // 11. 识别所有叶子节点（dict_value ！= "NULL"，对应 json 中的实际值）
            List<XmlTemplateAttribute> leafNodes = attrList.stream()
                    .filter(a -> {
                        String[] parts = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                        return d != null && !isStructNode(d);
                    })
                    .collect(Collectors.toList());

            // 12. 检测循环模式
            LoopDetectionResult loopResult = detectLoopPattern(leafNodes, dictCodeMap, jsonMap);

            if (loopResult.getLoopMode() == LoopMode.NONE) {
                buildNormalTree(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, rootAttrPath);
            } else if (loopResult.getLoopMode() == LoopMode.PARENT_LEVEL) {
                buildParentLevelLoop(doc, root, attrList, dictCodeMap, jsonMap,
                        pathNodeMap, structNodePaths, loopResult, rootAttrPath);
            } else {
                buildSiblingLevelLoop(doc, root, attrList, dictCodeMap, jsonMap,
                        pathNodeMap, structNodePaths, loopResult, rootAttrPath);
                buildUnprocessedNodes(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, rootAttrPath);
            }

            // 13. 移除空结构节点
            removeEmptyStructNodes(root, attrList, dictCodeMap);

            // 14. 生成XML字符串（不输出 <?xml ...?> 声明头）
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            String xmlContent = writer.toString();

            // 15. 版本管理
            String xmlVersion = xmlFileMapper.selectVersionByFileName("vehicle_" + vehicle.getVin());
            xmlVersion = StringUtils.isBlank(xmlVersion) ? "1.0" : String.valueOf(new BigDecimal(xmlVersion).add(new BigDecimal(1)));
            xmlFileMapper.updateIsLatestToFalse("vehicle_" + vehicle.getVin());

            // 16. 上传文件
            MultipartFile multipartFile = FileUtils.createMultipartFile(
                    xmlContent, vehicle.getVin() + ".xml", "application/xml");
            String filePath = remoteFileService.upload(multipartFile).getData().getUrl();

            // 17. 保存 XmlFile记录
            XmlFile xmlFile = new XmlFile();
            xmlFile.setFileName("vehicle_" + vehicle.getVin() + ".xml");
            xmlFile.setFilePath(filePath);
            xmlFile.setFileSize((long) xmlContent.getBytes(StandardCharsets.UTF_8).length);
            xmlFile.setFileLevel("1");
            xmlFile.setVersion(xmlVersion);
            xmlFile.setVin(vehicle.getVin());
            xmlFile.setIsLatest(true);
            xmlFile.setStatus("0");
            xmlFile.setDeleted(0);
            xmlFile.setUploadResult("1");
            xmlFile.setCreateBy(SecurityUtils.getUsername());
            xmlFile.setCreateTime(new Date());
            xmlFile.setRemark("由车辆VIN: " + vehicle.getVin() + " 生成XML,版本: " + xmlVersion);
            xmlFile.setVin(vehicle.getVin());
            xmlFile.setXmlTemplateId(xmlTemplate.getTemplateId());
            xmlFile.setModelCode(String.valueOf(vehicle.getVehicleModel()));
            xmlFile.setFactoryCode(vehicle.getFactoryCode());
            xmlFile.setVehicleMaterialNo(vehicle.getMaterialNo());
            xmlFile.setCountry(vehicle.getCountry());
            xmlFile.setIssueDate(vehicle.getIssueDate());
            xmlFile.setValidateResult(0);
            xmlFileMapper.insertXmlFile(xmlFile);

            try {
                validateXml(xmlFile.getId());
            } catch (Exception e) {
                log.warn("自动校验失败，xmlFileId={}，原因={}", xmlFile.getId(), e.getMessage());
                // 校验失败不影响生成结果，只记录日志
            }

            // 18. 保存版本记录
            XmlVersion version = new XmlVersion();
            version.setFileId(xmlFile.getId());
            version.setVersion(xmlVersion);
            version.setFilePath(filePath);
            version.setChangeType("生成");
            version.setChangeDesc("由车辆VIN: " + vehicle.getVin() + " 生成XML, 版本: " + xmlVersion);
            version.setCreateBy(SecurityUtils.getUsername());
            version.setCreateTime(new Date());
            xmlVersionMapper.insertXmlVersion(version);

            // 19. 更新状态
            VehicleInfo updateObj = new VehicleInfo();
            updateObj.setVehicleId(vehicle.getVehicleId());
            updateObj.setUploadStatus(1);

            // 20. 记录生命周期
            vehicleLifecycle.setEntryId(vehicle.getVehicleId());
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(vehicle.getVin());
            vehicleLifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_BUILD_XML.getOperation());
            vehicleLifecycle.setResult(0);
            vehicleInfoService.updateVehicleInfo(updateObj, false);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            msg.append("成功");
            Map<String, String> params = new HashMap<>();
            params.put("id", String.valueOf(xmlFile.getId()));
            params.put("vin", xmlFile.getVin());
            params.put("modelCode", xmlFile.getModelCode());
            params.put("factoryCode", xmlFile.getFactoryCode());
            params.put("country", xmlFile.getCountry());
            params.put("issueDate", com.ruoyi.common.core.utils.DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", xmlFile.getIssueDate()));
            sysNotice.setModel(SysNoticeModel.XML_FILE.getModel());
            sysNotice.setQueryParams(JSON.toJSONString(params));
            sysNotice.setNoticeContent(msg.toString());
            sysNotice.setSorts(Arrays.asList(14, 15));
            remoteNoticeService.innerAdd(sysNotice);

            return xmlContent;
        } catch (Exception e) {
            log.error("生成XML文件失败", e);
            vehicleLifecycle.setEntryId(vehicleId);
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(vehicle.getVin() == null ? "" : vehicle.getVin());
            vehicleLifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_BUILD_XML.getOperation());
            vehicleLifecycle.setResult(1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            msg.append("失败");
            sysNotice.setQueryParams(JSON.toJSONString(vehicleParams));
            sysNotice.setModel(SysNoticeModel.VEHICLE_INFO.getModel());
            sysNotice.setNoticeContent(msg.toString());
            sysNotice.setSorts(Arrays.asList(14, 15));
            remoteNoticeService.innerAdd(sysNotice);
            throw new RuntimeException("生成XML失败: " + e.getMessage());
        }
    }

    /**
     * 补充构建所有未被 buildTreeUpToPath/buildSiblingLevelLoop 处理的节点。
     * 从 rootAttrPath 开始，逐层遍历，对 pathNodeMap 中存在但子节点未完整构建的结构节点，
     * 以及完全未处理的结构节点，按需构建。
     */
    private void buildUnprocessedNodes(Document doc, Element root,
                                       List<XmlTemplateAttribute> attrList,
                                       Map<String, SysDictData> dictCodeMap,
                                       Map<String, Object> jsonMap,
                                       Map<String, Element> pathNodeMap,
                                       String rootAttrPath) {
        Queue<String> queue = new LinkedList<>();
        queue.add(rootAttrPath);

        while (!queue.isEmpty()) {
            String currentPath = queue.poll();
            int currentDepth = currentPath.split("\\.").length;
            Element currentElement = pathNodeMap.get(currentPath);
            if (currentElement == null) continue;

            // 找当前节点的直接子节点，按 sort_order 排序
            List<XmlTemplateAttribute> directChildren = attrList.stream()
                    .filter(a -> a.getAttrPath().startsWith(currentPath + ".")
                            && a.getAttrPath().split("\\.").length == currentDepth + 1)
                    .sorted(Comparator.comparingInt(a -> a.getSortOrder() != null ? a.getSortOrder() : 0))
                    .collect(Collectors.toList());

            for (XmlTemplateAttribute child : directChildren) {
                String childPath = child.getAttrPath();
                String[] parts = childPath.split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null || !isStructNode(dict)) continue;

                if (!pathNodeMap.containsKey(childPath)) {
                    // 未处理的结构节点 → 构建
                    List<XmlTemplateAttribute> subAttrs = attrList.stream()
                            .filter(a -> a.getAttrPath().startsWith(childPath + "."))
                            .collect(Collectors.toList());

                    boolean hasPipe = subAttrs.stream().anyMatch(a -> {
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        Object raw = jsonMap.get(d.getDictLabel());
                        return raw != null && raw.toString().contains("|");
                    });


                    int childDepth = childPath.split("\\.").length;
                    boolean hasSemi = !hasPipe && subAttrs.stream().anyMatch(a -> {
                        if (a.getAttrPath().split("\\.").length != childDepth + 2) return false; // ← 加这行
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        Object raw = jsonMap.get(d.getDictLabel());
                        return raw != null && raw.toString().contains(";") && !raw.toString().contains("|");
                    });

                    Element structEl = createElementWithDefault(doc,
                            sanitizeXmlTagName(dict.getDictLabel()), child.getDefaultValue());

                    // ★ 按 sort_order 找正确插入位置
                    int childSortOrder = child.getSortOrder() != null ? child.getSortOrder() : 0;
                    Node insertBeforeNode = null;
                    NodeList siblings = currentElement.getChildNodes();
                    outer:
                    for (int i = 0; i < siblings.getLength(); i++) {
                        Node sib = siblings.item(i);
                        if (!(sib instanceof Element)) continue;
                        String sibTag = ((Element) sib).getLocalName() != null
                                ? ((Element) sib).getLocalName()
                                : ((Element) sib).getTagName();
                        for (XmlTemplateAttribute sibAttr : directChildren) {
                            String[] sp = sibAttr.getAttrPath().split("\\.");
                            SysDictData sd = dictCodeMap.get(sp[sp.length - 1]);
                            if (sd == null) continue;
                            if (!sanitizeXmlTagName(sd.getDictLabel()).equals(sibTag)) continue;
                            int sibSort = sibAttr.getSortOrder() != null ? sibAttr.getSortOrder() : 0;
                            if (sibSort > childSortOrder) {
                                insertBeforeNode = sib;
                                break outer;
                            }
                        }
                    }

                    if (insertBeforeNode != null) {
                        currentElement.insertBefore(structEl, insertBeforeNode);
                    } else {
                        currentElement.appendChild(structEl);
                    }
                    pathNodeMap.put(childPath, structEl);

                    if (hasPipe) {
                        int pipeRows = detectPipeRows(subAttrs, dictCodeMap, jsonMap, childPath);
                        expandPipeLoop(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                                buildSubPathNodeMap(pathNodeMap, childPath, structEl),
                                childPath, pipeRows);
                    } else if (hasSemi) {
                        // ★ 含 ; 的子树（如 TestFamilyIdentifiersTable）也用 expandPipeLoop
                        log.info("=== buildUnprocessedNodes childPath末段={} hasPipe={} hasSemi={}",
                                childPath.substring(childPath.lastIndexOf('.')+1,
                                        Math.min(childPath.lastIndexOf('.')+9, childPath.length())),
                                hasPipe, hasSemi);
                        int semiRows = detectSemicolonRows(subAttrs, dictCodeMap, jsonMap, childPath);
                        expandPipeLoop(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                                buildSubPathNodeMap(pathNodeMap, childPath, structEl),
                                childPath, semiRows);
                    } else {
                        buildSubTree(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                                buildSubPathNodeMap(pathNodeMap, childPath, structEl),
                                childPath, -1);
                    }

                } else {
                    // 已在 pathNodeMap，继续往下 BFS，让深层未处理节点被发现
                    queue.add(childPath);
                }
            }
        }
    }

    /**
     * 按模板 sort_order 将新节点插入到父节点的正确位置
     */
    private void insertElementInOrder(Element parentElement, Element newElement,
                                      XmlTemplateAttribute newAttr,
                                      List<XmlTemplateAttribute> attrList,
                                      Map<String, SysDictData> dictCodeMap) {
        String parentPath = getParentPath(newAttr.getAttrPath());
        int newSortOrder = newAttr.getSortOrder() != null ? newAttr.getSortOrder() : 0;
        int newDepth = newAttr.getAttrPath().split("\\.").length;

        // 找到同级中 sort_order 比 newSortOrder 大的第一个已存在子节点
        NodeList children = parentElement.getChildNodes();
        Node insertBefore = null;

        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element childEl = (Element) n;

            // 找这个已存在子节点对应的 attrList 条目
            String childTag = childEl.getLocalName() != null ? childEl.getLocalName() : childEl.getTagName();
            for (XmlTemplateAttribute a : attrList) {
                if (!a.getAttrPath().startsWith(parentPath.isEmpty() ? "" : parentPath + ".")) continue;
                if (a.getAttrPath().split("\\.").length != newDepth) continue;
                String[] p = a.getAttrPath().split("\\.");
                SysDictData d = dictCodeMap.get(p[p.length - 1]);
                if (d == null) continue;
                if (!sanitizeXmlTagName(d.getDictLabel()).equals(childTag)) continue;
                int existingSortOrder = a.getSortOrder() != null ? a.getSortOrder() : 0;
                if (existingSortOrder > newSortOrder) {
                    insertBefore = n;
                    break;
                }
            }
            if (insertBefore != null) break;
        }

        if (insertBefore != null) {
            parentElement.insertBefore(newElement, insertBefore);
        } else {
            parentElement.appendChild(newElement);
        }
    }

    /**
     * 补充构建在 buildSiblingLevelLoop/buildParentLevelLoop 处理后
     * 仍未被构建的顶层节点（pathNodeMap 里没有的）
     */
    private void buildRemainingNodes(Document doc, Element root,
                                     List<XmlTemplateAttribute> attrList,
                                     Map<String, SysDictData> dictCodeMap,
                                     Map<String, Object> jsonMap,
                                     Map<String, Element> pathNodeMap,
                                     String rootAttrPath) {
        int rootDepth = rootAttrPath.split("\\.").length;

        List<XmlTemplateAttribute> topChildren = attrList.stream()
                .filter(a -> a.getAttrPath().startsWith(rootAttrPath + ".")
                        && a.getAttrPath().split("\\.").length == rootDepth + 1)
                .collect(Collectors.toList());

        for (XmlTemplateAttribute child : topChildren) {
            String[] parts = child.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || !isStructNode(dict)) continue;

            // 检查该子树下是否有 | 字段
            List<XmlTemplateAttribute> subAttrs = attrList.stream()
                    .filter(a -> a.getAttrPath().startsWith(child.getAttrPath() + "."))
                    .collect(Collectors.toList());

            boolean hasPipeField = subAttrs.stream().anyMatch(a -> {
                String[] p = a.getAttrPath().split("\\.");
                SysDictData d = dictCodeMap.get(p[p.length - 1]);
                if (d == null || isStructNode(d)) return false;
                Object raw = jsonMap.get(d.getDictLabel());
                return raw != null && raw.toString().contains("|");
            });

            // ★ 只处理含 | 字段的节点，其他全跳过
            if (!hasPipeField) continue;

            // 从 DOM 和 pathNodeMap 里移除旧节点及其子树
            if (pathNodeMap.containsKey(child.getAttrPath())) {
                Element old = pathNodeMap.get(child.getAttrPath());
                if (old != null && old.getParentNode() != null) {
                    old.getParentNode().removeChild(old);
                }
                String prefix = child.getAttrPath() + ".";
                pathNodeMap.entrySet().removeIf(e ->
                        e.getKey().equals(child.getAttrPath()) || e.getKey().startsWith(prefix));
            }

            // 重新构建
            Element structEl = createElementWithDefault(doc,
                    sanitizeXmlTagName(dict.getDictLabel()), child.getDefaultValue());
            root.appendChild(structEl);
            pathNodeMap.put(child.getAttrPath(), structEl);

            Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap,
                    child.getAttrPath(), structEl);

            buildSubTree(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                    subMap, child.getAttrPath(), -1);
        }
    }

    // =====================================================
    // 循环检测
    // =====================================================

    /**
     * 检测循环模式：
     * - 遍历所有叶子节点，找出值中含分号的字段
     * - 若所有含分号字段都是"前缀:值"格式 → PARENT_LEVEL（上级循环）
     * - 若至少一个含分号字段是"值;值"无前缀格式 → SIBLING_LEVEL（同级循环）
     * - 无含分号字段 → NONE
     *
     * ★ 改动：直接使用 dictLabel 匹配 jsonMap，不再通过 keyMap 取值
     */
    private LoopDetectionResult detectLoopPattern(List<XmlTemplateAttribute> leafNodes,
                                                  Map<String, SysDictData> dictCodeMap,
                                                  Map<String, Object> jsonMap) {
        LoopDetectionResult result = new LoopDetectionResult();

        boolean hasPrefix = false;
        boolean hasNonPrefix = false;
        int maxRows = 1;
        XmlTemplateAttribute deepestTriggerAttr = null;
        Set<String> allPrefixes = new LinkedHashSet<>();

        for (XmlTemplateAttribute leaf : leafNodes) {
            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            if (d == null || StringUtils.isBlank(d.getDictLabel())) continue;

            Object raw = jsonMap.get(d.getDictLabel());
            if (raw == null) continue;

            String val = raw.toString().trim();
            if (!val.contains(";")) continue;

            // ★ 改为选路径最浅的触发字段，避免深层字段（如 TestFamilyIdentifier）
            //    抢占 loopContainerPath，破坏整体结构
            if (deepestTriggerAttr == null ||
                    leaf.getAttrPath().split("\\.").length <
                            deepestTriggerAttr.getAttrPath().split("\\.").length) {
                deepestTriggerAttr = leaf;
            }

            String[] items = val.split(";", -1);
            maxRows = Math.max(maxRows, items.length);

            boolean allItemsHavePrefix = true;
            for (String item : items) {
                item = item.trim();
                if (item.isEmpty()) continue;
                if (!item.contains(":")) {
                    allItemsHavePrefix = false;
                    break;
                }
            }

            if (allItemsHavePrefix) {
                hasPrefix = true;
                for (String item : items) {
                    item = item.trim();
                    if (item.isEmpty()) continue;
                    int colon = item.indexOf(':');
                    if (colon > 0) {
                        allPrefixes.add(item.substring(0, colon).trim());
                    }
                }
            } else {
                hasNonPrefix = true;
            }
        }

        if (deepestTriggerAttr == null) {
            result.setLoopMode(LoopMode.NONE);
            return result;
        }

        result.setTriggerAttr(deepestTriggerAttr);

        if (hasPrefix && !hasNonPrefix) {
            result.setLoopMode(LoopMode.PARENT_LEVEL);
            result.setGroupKeys(new ArrayList<>(allPrefixes));
            result.setLoopContainerPath(getParentPath(getParentPath(deepestTriggerAttr.getAttrPath())));
        } else {
            result.setLoopMode(LoopMode.SIBLING_LEVEL);
            result.setMaxRows(maxRows);
            result.setGroupKeys(IntStream.range(0, maxRows)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.toList()));
            result.setLoopContainerPath(getParentPath(getParentPath(deepestTriggerAttr.getAttrPath())));
        }

        return result;
    }

    private int detectPipeRows(List<XmlTemplateAttribute> attrList,
                               Map<String, SysDictData> dictCodeMap,
                               Map<String, Object> jsonMap,
                               String rootAttrPath) {
        int maxRows = 1;
        for (XmlTemplateAttribute attr : attrList) {
            if (attr.getAttrPath().equals(rootAttrPath)) continue;
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString();
            if (val.contains("|")) {
                int rows = val.split("\\|", -1).length;
                maxRows = Math.max(maxRows, rows);
            }
        }
        return maxRows;
    }

    // =====================================================
    // 构建普通树（无循环）
    // =====================================================

    /**
     * 构建无循环的普通XML树
     * ★ 改动：直接使用 dictLabel 匹配 jsonMap
     */
    private void buildNormalTree(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                 Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                 Map<String, Element> pathNodeMap, String rootAttrPath) {
        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            String[] parts = attrPath.split("\\.");
            String lastPart = parts[parts.length - 1];
            SysDictData dict = dictCodeMap.get(lastPart);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            if (isStructNode(dict)) {
                Element structElement = createElementWithDefault(doc,
                        sanitizeXmlTagName(dict.getDictLabel()), attr.getDefaultValue());
                parentElement.appendChild(structElement);
                pathNodeMap.put(attrPath, structElement);
                // ★ 检查该结构节点子树是否含 ; 字段需要展开
                // ★ 修复：仅在 buildNormalTree（无循环场景）下触发，含 ; 不含 | 的孙级叶子才展开
                int nodeDepth = attrPath.split("\\.").length;
                boolean subHasSemi = attrList.stream().anyMatch(a -> {
                    if (!a.getAttrPath().startsWith(attrPath + ".")) return false;
                    if (a.getAttrPath().split("\\.").length != nodeDepth + 2) return false;
                    String[] p = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                    if (d == null || isStructNode(d)) return false;
                    Object raw = jsonMap.get(d.getDictLabel());
                    return raw != null && raw.toString().contains(";") && !raw.toString().contains("|");
                });

                log.info("=== buildNormalTree struct subHasSemi={} label={}", subHasSemi, dict.getDictLabel());
                if (subHasSemi) {
                    List<XmlTemplateAttribute> subAttrs = attrList.stream()
                            .filter(a -> a.getAttrPath().startsWith(attrPath + "."))
                            .collect(Collectors.toList());
                    int semiRows = detectSemicolonRows(subAttrs, dictCodeMap, jsonMap, attrPath);
                    Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap, attrPath, structElement);
                    expandPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                            subMap, attrPath, semiRows);
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel()) && !isStructNode(dict)) {
                // ★ 改动：直接使用 dictLabel 匹配 jsonMap
                Object raw = jsonMap.get(dict.getDictLabel());
                String value = getValueOrDefault(raw, attr.getDefaultValue());
                boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
                addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value, required);
            }
        }
    }

    // =====================================================
    // 上级循环（PARENT_LEVEL）
    // =====================================================

    /**
     * 上级循环：每个前缀生成一套完整的 loopContainer 结构。
     * ★ 改动：叶子节点分支使用 dictLabel 匹配 jsonMap
     */
    private void buildParentLevelLoop(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                      Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                      Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                      LoopDetectionResult loopResult, String rootAttrPath) {

        log.info("=== loopContainerPath: {}, groupKeys: {}",
                loopResult.getLoopContainerPath(), loopResult.getGroupKeys());

        String loopContainerPath = loopResult.getLoopContainerPath();

        // 1. 构建到循环容器父节点为止
        buildTreeUpToPath(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, structNodePaths, loopContainerPath, rootAttrPath);

        // 2. 获取循环容器的父元素
        String parentPath = getParentPath(loopContainerPath);
        Element parentElement = pathNodeMap.getOrDefault(parentPath, root);

        // 3. 按 sort_order 顺序遍历父节点的所有直接子节点
        int loopDepth = loopContainerPath.split("\\.").length;
        List<XmlTemplateAttribute> directSiblings = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    if (parentPath.isEmpty()) return p.split("\\.").length == loopDepth;
                    return p.startsWith(parentPath + ".") && p.split("\\.").length == loopDepth;
                })
                .collect(Collectors.toList());

        List<String> groupKeys = loopResult.getGroupKeys();
        for (XmlTemplateAttribute sibling : directSiblings) {
            String[] parts = sibling.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if (sibling.getAttrPath().equals(loopContainerPath)) {
                // 当前节点是循环容器 → 展开为 N 个循环容器
                for (int i = 0; i < groupKeys.size(); i++) {
                    generateParentLoopContainer(doc, parentElement, loopContainerPath,
                            attrList, dictCodeMap, jsonMap, structNodePaths, groupKeys.get(i), i, pathNodeMap);
                }
            } else if (isStructNode(dict)) {
                // 结构节点
                if (!pathNodeMap.containsKey(sibling.getAttrPath())) {
                    Element structElement = createElementWithDefault(doc, sanitizeXmlTagName(dict.getDictLabel()), sibling.getDefaultValue());
                    parentElement.appendChild(structElement);
                    pathNodeMap.put(sibling.getAttrPath(), structElement);
                    // ★ 修复：直接传主 pathNodeMap，子树内节点回写到主 map，防止 buildUnprocessedNodes 重复创建
                    buildSubTree(doc, structElement,
                            attrList.stream()
                                    .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                                    .collect(Collectors.toList()),
                            dictCodeMap, jsonMap,
                            pathNodeMap,
                            sibling.getAttrPath());
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel()) && !isStructNode(dict)) {
                // ★ 改动：使用 dictLabel 匹配 jsonMap；含分号 → 循环字段跳过；无分号 → 正常生成
                Object raw = jsonMap.get(dict.getDictLabel());
                String value = getValueOrDefault(raw, sibling.getDefaultValue());
                if (!value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    /**
     * 生成单个父级循环容器
     */
    private void generateParentLoopContainer(Document doc, Element parentElement, String loopContainerPath,
                                             List<XmlTemplateAttribute> attrList,
                                             Map<String, SysDictData> dictCodeMap,
                                             Map<String, Object> jsonMap,
                                             Set<String> structNodePaths,
                                             String prefix,
                                             int prefixIndex,
                                             Map<String, Element> pathNodeMap) {

        String[] containerParts = loopContainerPath.split("\\.");
        SysDictData containerDict = dictCodeMap.get(containerParts[containerParts.length - 1]);
        if (containerDict == null) return;

        Element container = doc.createElement(sanitizeXmlTagName(containerDict.getDictLabel()));
        parentElement.appendChild(container);

        buildContainerByPrefix(doc, container, loopContainerPath, attrList, dictCodeMap, jsonMap, prefix, prefixIndex);
    }

    /**
     * 按前缀递归填充容器内容（上级循环专用）
     * ★ 改动：使用 dictLabel 匹配 jsonMap
     */
    private void buildContainerByPrefix(Document doc, Element container, String containerPath,
                                        List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap,
                                        Map<String, Object> jsonMap,
                                        String prefix,
                                        int prefixIndex) {
        List<XmlTemplateAttribute> directChildren = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    if (!p.startsWith(containerPath + ".")) return false;
                    return p.split("\\.").length == containerPath.split("\\.").length + 1;
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute child : directChildren) {
            String[] parts = child.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if (isStructNode(dict)) {
                Element structElement = createElementWithDefault(doc, sanitizeXmlTagName(dict.getDictLabel()), child.getDefaultValue());
                structElement.setUserData("loopGenerated", Boolean.TRUE, null);
                container.appendChild(structElement);
                buildContainerByPrefix(doc, structElement, child.getAttrPath(), attrList, dictCodeMap, jsonMap, prefix, prefixIndex);
            } else if (StringUtils.isNotBlank(dict.getDictLabel()) && !isStructNode(dict)) {
                // ★ 改动：使用 dictLabel 匹配 jsonMap
                String value = extractValueByPrefix(jsonMap, dict.getDictLabel(), prefix, prefixIndex);
                if (StringUtils.isBlank(value)) {
                    value = StringUtils.isNotBlank(child.getDefaultValue()) ? child.getDefaultValue() : "";
                }
                addElement(doc, container, sanitizeXmlTagName(dict.getDictLabel()), value);
            }
        }
    }

    // =====================================================
    // 同级循环（SIBLING_LEVEL）
    // =====================================================

    /**
     * 同级循环：在同一个容器内，子结构循环多次。
     * ★ 改动：叶子节点分支使用 dictLabel 匹配 jsonMap
     */
    private void buildSiblingLevelLoop(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                       Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                       Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                       LoopDetectionResult loopResult, String rootAttrPath) {
        String loopContainerPath = loopResult.getLoopContainerPath();
        // 1. 构建到循环容器父节点为止
        buildTreeUpToPath(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, structNodePaths, loopContainerPath, rootAttrPath);

        // 2. 获取循环容器的父元素
        String parentOfContainerPath = getParentPath(loopContainerPath);
        Element parentElement = pathNodeMap.getOrDefault(parentOfContainerPath, root);

        // 3. 按 sort_order 顺序遍历父节点的所有直接子节点
        int loopDepth = loopContainerPath.split("\\.").length;
        List<XmlTemplateAttribute> directSiblings = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    if (parentOfContainerPath.isEmpty()) return p.split("\\.").length == loopDepth;
                    return p.startsWith(parentOfContainerPath + ".") && p.split("\\.").length == loopDepth;
                })
                .collect(Collectors.toList());

        // 预先找好循环子结构路径（ManufacturerGroup）
        String triggerPath = loopResult.getTriggerAttr().getAttrPath();
        String loopStructPath = findLoopStructPath(loopContainerPath, triggerPath, structNodePaths);
        for (XmlTemplateAttribute sibling : directSiblings) {
            String[] parts = sibling.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if (sibling.getAttrPath().equals(loopContainerPath)) {
                // 当前节点是循环容器 → 创建容器并在内部展开子结构循环
                Element container = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                parentElement.appendChild(container);
                pathNodeMap.put(loopContainerPath, container);

                // 展开循环子结构（ManufacturerGroup × N）
                if (loopStructPath != null) {
                    String[] structParts = loopStructPath.split("\\.");
                    SysDictData structDict = dictCodeMap.get(structParts[structParts.length - 1]);
                    if (structDict != null) {
                        for (int i = 0; i < loopResult.getMaxRows(); i++) {
                            Element structElement = doc.createElement(sanitizeXmlTagName(structDict.getDictLabel()));
                            structElement.setUserData("loopGenerated", Boolean.TRUE, null);
                            container.appendChild(structElement);
                            fillStructByIndex(doc, structElement, loopStructPath, attrList, dictCodeMap, jsonMap, i);
                        }
                    }
                }
                // 容器内不参与循环的直接子叶子节点
                addNonLoopSiblingNodes(doc, container, loopContainerPath, loopStructPath, attrList, dictCodeMap, jsonMap);

            } else if (isStructNode(dict)) {
                // 结构节点
                // ★ 修复：同时检查 pathNodeMap（逻辑层）和 DOM（物理层），防止 buildTreeUpToPath 已写入后再重复创建
                if (!pathNodeMap.containsKey(sibling.getAttrPath())
                        && !hasChildElement(parentElement, sanitizeXmlTagName(dict.getDictLabel()))) {
                    List<XmlTemplateAttribute> subAttrs = attrList.stream()
                            .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                            .collect(Collectors.toList());

                    // ★ 检查该子树下是否有 | 字段（仅检查直接子叶子，不跨子树污染）
                    boolean hasPipe = subAttrs.stream().anyMatch(a -> {
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        // ★ 只检查该子树内的直接叶子，排除其他子树的 | 字段
                        // 判断：该字段的路径必须以 sibling.getAttrPath() 开头（已由 filter 保证）
                        Object raw = jsonMap.get(d.getDictLabel());
                        return raw != null && raw.toString().contains("|");
                    });

                    Element structElement = createElementWithDefault(doc,
                            sanitizeXmlTagName(dict.getDictLabel()), sibling.getDefaultValue());
                    parentElement.appendChild(structElement);
                    pathNodeMap.put(sibling.getAttrPath(), structElement);

                    if (hasPipe) {
                        int pipeRows = detectPipeRows(subAttrs, dictCodeMap, jsonMap, sibling.getAttrPath());
                        Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap, sibling.getAttrPath(), structElement);
                        expandPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                                subMap, sibling.getAttrPath(), pipeRows);
                        // 将子树节点回写到主 pathNodeMap，防止 buildUnprocessedNodes 重复构建
                        pathNodeMap.putAll(subMap);
                    } else {
                        // ★ 修复：直接传主 pathNodeMap（而非副本），使子树内所有结构节点路径回写到主 map，
                        //   防止 buildUnprocessedNodes BFS 时因找不到 MakeGroup 等路径而重复创建
                        pathNodeMap.put(sibling.getAttrPath(), structElement);
                        buildSubTree(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                                pathNodeMap, sibling.getAttrPath(), -1);
                    }
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel()) && !isStructNode(dict)) {
                Object raw = jsonMap.get(dict.getDictLabel());
                String value = getValueOrDefault(raw, sibling.getDefaultValue());
                // ★ 已有同名子节点则跳过，防止 buildTreeUpToPath 已写过的字段重复输出
                if (!value.contains(";") && !hasChildElement(parentElement, sanitizeXmlTagName(dict.getDictLabel()))) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    private boolean hasChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && tagName.equals(((Element) n).getTagName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找到循环容器下、包含循环触发字段的直接子结构节点路径
     */
    private String findLoopStructPath(String containerPath, String triggerPath,
                                      Set<String> structNodePaths) {
        for (String structPath : structNodePaths) {
            if (!structPath.startsWith(containerPath + ".")) continue;
            if (structPath.split("\\.").length != containerPath.split("\\.").length + 1) continue;
            if (triggerPath.startsWith(structPath + ".")) {
                return structPath;
            }
        }
        return null;
    }

    /**
     * 添加循环容器下不参与循环的同级节点（叶子节点，不在 loopStructPath 下）
     * ★ 改动：使用 dictLabel 匹配 jsonMap
     */
    private void addNonLoopSiblingNodes(Document doc, Element container, String containerPath,
                                        String loopStructPath, List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap,
                                        Map<String, Object> jsonMap) {
        List<XmlTemplateAttribute> directLeafs = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    if (!p.startsWith(containerPath + ".")) return false;
                    if (p.split("\\.").length != containerPath.split("\\.").length + 1) return false;
                    if (loopStructPath != null && p.startsWith(loopStructPath + ".")) return false;
                    String[] parts = p.split("\\.");
                    SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                    // ★ 改动：判断 dictLabel 非空
                    return d != null && StringUtils.isNotBlank(d.getDictLabel())
                            && !isStructNode(d);
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute leaf : directLeafs) {
            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || StringUtils.isBlank(dict.getDictLabel())) continue;

            // ★ 改动：使用 dictLabel 匹配 jsonMap
            Object raw = jsonMap.get(dict.getDictLabel());
            String value = raw != null ? raw.toString() : "";
            // 若值含分号，说明该字段本身不是循环触发，取第一个值
            if (value.contains(";")) {
                value = value.split(";")[0].trim();
            }
            if (StringUtils.isBlank(value)) {
                value = StringUtils.isNotBlank(leaf.getDefaultValue()) ? leaf.getDefaultValue() : "";
            }
            addElement(doc, container, sanitizeXmlTagName(dict.getDictLabel()), value);
        }
    }

    /**
     * 按索引填充子结构下的所有叶子字段（同级循环专用）
     * ★ 改动：使用 dictLabel 匹配 jsonMap
     */
    private void fillStructByIndex(Document doc, Element structElement, String loopStructPath,
                                   List<XmlTemplateAttribute> attrList,
                                   Map<String, SysDictData> dictCodeMap,
                                   Map<String, Object> jsonMap, int rowIndex) {
        // 取该 Group 下的所有子节点
        List<XmlTemplateAttribute> children = attrList.stream()
                .filter(a -> a.getAttrPath().startsWith(loopStructPath + "."))
                .collect(Collectors.toList());

        Map<String, Element> subPathNodeMap = new LinkedHashMap<>();
        subPathNodeMap.put(loopStructPath, structElement);

        // ★ 传入 rowIndex，让取值按行分割
        buildSubTree(doc, structElement, children, dictCodeMap, jsonMap,
                subPathNodeMap, loopStructPath, rowIndex);
    }

    // =====================================================
    // 公共辅助：构建循环前的树
    // =====================================================

    /**
     * 构建循环容器祖先链上的节点（不含 targetPath 本身、其子孙节点、及其同级节点）。
     * ★ 改动：叶子节点分支使用 dictLabel 匹配 jsonMap
     */
    private void buildTreeUpToPath(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                   Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                   Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                   String targetPath, String rootAttrPath) {

        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;
            if (attrPath.equals(targetPath) || attrPath.startsWith(targetPath + ".")) continue;

            String[] parts = attrPath.split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            // ★ 结构节点：targetPath 必须经过它才建，否则跳过（不建旁支子树）
            // ★ 叶子节点：正常输出（允许 GearboxType 这类同级叶子）
            if (isStructNode(dict) && !targetPath.startsWith(attrPath + ".")) continue;

            if (isStructNode(dict)) {
                Element structElement = createElementWithDefault(doc,
                        sanitizeXmlTagName(dict.getDictLabel()), attr.getDefaultValue());
                parentElement.appendChild(structElement);
                pathNodeMap.put(attrPath, structElement);
            } else if (StringUtils.isNotBlank(dict.getDictLabel())) {
                Object raw = jsonMap.get(dict.getDictLabel());
                String value = getValueOrDefault(raw, attr.getDefaultValue());
                // ★ 修复：加重复检查，防止后续 buildSiblingLevelLoop 遍历 directSiblings 时再次写入同名节点
                if (!value.contains(";") && !hasChildElement(parentElement, sanitizeXmlTagName(dict.getDictLabel()))) {
                    addElement(doc, parentElement,
                            sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    /**
     * 补充循环容器的同级节点。
     * @deprecated 已由 buildParentLevelLoop / buildSiblingLevelLoop 内的统一顺序遍历替代，不再调用。
     */
    @Deprecated
    private void addSiblingNodesAfterLoop(Document doc, Element parentElement, String loopContainerPath,
                                          List<XmlTemplateAttribute> attrList,
                                          Map<String, SysDictData> dictCodeMap,
                                          Map<String, Object> jsonMap,
                                          Map<String, Element> pathNodeMap) {

        String parentPath = getParentPath(loopContainerPath);
        int loopDepth = loopContainerPath.split("\\.").length;

        List<XmlTemplateAttribute> siblings = attrList.stream()
                .filter(a -> {
                    String path = a.getAttrPath();
                    if (path.equals(loopContainerPath)) return false;
                    if (!path.startsWith(parentPath + ".")) return false;
                    return path.split("\\.").length == loopDepth;
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute sibling : siblings) {
            String[] parts = sibling.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if (isStructNode(dict)) {
                if (pathNodeMap.containsKey(sibling.getAttrPath())) continue;
                Element structElement = createElementWithDefault(doc, sanitizeXmlTagName(dict.getDictLabel()), sibling.getDefaultValue());
                parentElement.appendChild(structElement);
                pathNodeMap.put(sibling.getAttrPath(), structElement);
                buildSubTree(doc, structElement,
                        attrList.stream()
                                .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                                .collect(Collectors.toList()),
                        dictCodeMap, jsonMap,
                        buildSubPathNodeMap(pathNodeMap, sibling.getAttrPath(), structElement),
                        sibling.getAttrPath());
            } else if (StringUtils.isNotBlank(dict.getDictLabel()) && !isStructNode(dict)) {
                // ★ 改动：使用 dictLabel 匹配 jsonMap
                Object raw = jsonMap.get(dict.getDictLabel());
                String value = raw != null ? raw.toString() : "";
                if (!value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    /**
     * 为子树构建一个局部 pathNodeMap，根节点已预先注册
     */
    private Map<String, Element> buildSubPathNodeMap(Map<String, Element> existingMap,
                                                     String rootPath, Element rootElement) {
        Map<String, Element> subMap = new HashMap<>(existingMap);
        subMap.put(rootPath, rootElement);
        return subMap;
    }

    /**
     * 在指定父元素下构建子树
     * ★ 改动：使用 dictLabel 匹配 jsonMap
     */
    private void buildSubTree(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                              Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                              Map<String, Element> pathNodeMap, String rootAttrPath,
                              int rowIndex) {

        // ★ 只有非循环场景（rowIndex<0）才检测分隔符并触发展开
        if (rowIndex < 0) {
            // 先检测 | 展开（AxleGroup / FinalDriveVehicleGroup 等）
            int pipeRows = detectPipeRows(attrList, dictCodeMap, jsonMap, rootAttrPath);
            if (pipeRows > 1) {
                expandPipeLoop(doc, root, attrList, dictCodeMap, jsonMap,
                        pathNodeMap, rootAttrPath, pipeRows);
                return;
            }
        }

        // 正常逐节点处理
        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            String[] parts = attrPath.split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            if (isStructNode(dict)) {
                Element structElement = createElementWithDefault(doc,
                        sanitizeXmlTagName(dict.getDictLabel()), attr.getDefaultValue());
                parentElement.appendChild(structElement);
                pathNodeMap.put(attrPath, structElement);
                // ★ 检查该结构节点的直接子 Group 层叶子是否含 ; 字段需要展开
                // ★ 修复：depth 限定改为 nodeDepth+2（Table→Group→Field），且仅在 rowIndex<0 场景触发，
                //         避免已在循环上下文中再次展开（例如 MakeGroup.Make 含";"被误触发二次循环）
                int nodeDepth = attrPath.split("\\.").length;
                boolean subHasSemi = (rowIndex < 0) && attrList.stream().anyMatch(a -> {
                    if (!a.getAttrPath().startsWith(attrPath + ".")) return false;
                    if (a.getAttrPath().split("\\.").length != nodeDepth + 2) return false;
                    String[] p = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                    if (d == null || isStructNode(d)) return false;
                    Object raw = jsonMap.get(d.getDictLabel());
                    return raw != null && raw.toString().contains(";") && !raw.toString().contains("|");
                });
                if (subHasSemi) {
                    List<XmlTemplateAttribute> subAttrs = attrList.stream()
                            .filter(a -> a.getAttrPath().startsWith(attrPath + "."))
                            .collect(Collectors.toList());
                    int semiRows = detectSemicolonRows(subAttrs, dictCodeMap, jsonMap, attrPath);
                    Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap, attrPath, structElement);
                    expandPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                            subMap, attrPath, semiRows);
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel())) {
                String value = getValueByRow(jsonMap, dict.getDictLabel(),
                        attr.getDefaultValue(), rowIndex);
                boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
                if (StringUtils.isNotBlank(value) || required) {
                    addElement(doc, parentElement,
                            sanitizeXmlTagName(dict.getDictLabel()), value, required);
                }
            }
        }
    }

    // 兼容无 rowIndex 的旧调用




    /**
     * 检测子树中是否有 ; 分隔的字段（不含 | 的），返回最大行数。
     * 用于处理嵌套循环（如 TestFamilyIdentifiersGroup），
     * 与 detectPipeLoop（处理 |）职责分离。
     */
    private int detectSemicolonRows(List<XmlTemplateAttribute> attrList,
                                    Map<String, SysDictData> dictCodeMap,
                                    Map<String, Object> jsonMap,
                                    String rootAttrPath) {
        int maxRows = 1;
        int rootDepth = rootAttrPath.split("\\.").length;
        for (XmlTemplateAttribute attr : attrList) {
            if (attr.getAttrPath().equals(rootAttrPath)) continue;
            if (attr.getAttrPath().split("\\.").length != rootDepth + 2) continue;
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString();
            // ★ 加这行
            log.info("=== detectSemicolonRows check label={} val={} rootDepth={} attrDepth={}",
                    dict.getDictLabel(), val, rootDepth, attr.getAttrPath().split("\\.").length);
            if (val.contains(";") && !val.contains("|")) {
                int rows = val.split(";", -1).length;
                maxRows = Math.max(maxRows, rows);
            }
        }
        log.info("=== detectSemicolonRows result rootAttrPath末段={} maxRows={}",
                rootAttrPath.substring(rootAttrPath.lastIndexOf('.')+1), maxRows);
        return maxRows;
    }

    /**
     * 扫描 attrList 里的叶子节点，看是否有 | 分隔的字段
     * 返回最大行数（没有 | 则返回 1）
     */
    /**
     * 对含 | 分隔字段的子树，找到最外层的 Group 节点，循环展开 N 次
     * 例如：AxleTable → AxleGroup × 2，每个 AxleGroup 下再展开 TyreAxleGroup
     */
    private void expandPipeLoop(Document doc, Element parentElement,
                                List<XmlTemplateAttribute> attrList,
                                Map<String, SysDictData> dictCodeMap,
                                Map<String, Object> jsonMap,
                                Map<String, Element> pathNodeMap,
                                String rootAttrPath, int rows) {

        // 找 rootAttrPath 下的第一层直接子节点
        int rootDepth = rootAttrPath.split("\\.").length;

        List<XmlTemplateAttribute> directChildren = attrList.stream()
                .filter(a -> a.getAttrPath().startsWith(rootAttrPath + ".")
                        && a.getAttrPath().split("\\.").length == rootDepth + 1)
                .collect(Collectors.toList());

        for (XmlTemplateAttribute child : directChildren) {
            String[] parts = child.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if (isStructNode(dict)) {
                // 这是 Group 节点（如 AxleGroup）→ 循环展开 rows 次
                List<XmlTemplateAttribute> groupChildren = attrList.stream()
                        .filter(a -> a.getAttrPath().startsWith(child.getAttrPath() + "."))
                        .collect(Collectors.toList());

                // ★ 修复重复生成：按该 Group 自身子字段的实际最大行数决定展开次数，
                //   防止外层 rows（由其他 Table 的字段计算得出）污染本 Group 的循环次数。
                //   例如 MakeGroup.Make="OMODA"（单值）应只展开 1 次，而非 AxleTable 的 2 次。
                int actualRows = calcGroupActualRows(groupChildren, dictCodeMap, jsonMap, rows);

                for (int i = 0; i < actualRows; i++) {
                    Element groupEl = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                    parentElement.appendChild(groupEl);

                    Map<String, Element> subMap = new LinkedHashMap<>();
                    subMap.put(child.getAttrPath(), groupEl);

                    // 以 rowIndex=i 递归构建该 Group 的子树
                    buildSubTree(doc, groupEl, groupChildren, dictCodeMap, jsonMap,
                            subMap, child.getAttrPath(), i);
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel())) {
                // 直接叶子节点（不常见，但容错处理）
                String value = getValueByRow(jsonMap, dict.getDictLabel(),
                        child.getDefaultValue(), -1);
                if (StringUtils.isNotBlank(value)) {
                    addElement(doc, parentElement,
                            sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }


    private void buildSubTree(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                              Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                              Map<String, Element> pathNodeMap, String rootAttrPath) {
        buildSubTree(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, rootAttrPath, -1);
    }

    /**
     * 计算某个 Group 节点自身子字段的实际最大行数。
     * <p>
     * 规则：
     * <ul>
     *   <li>遍历该 Group 下所有叶子字段，在 jsonMap 中查找对应值</li>
     *   <li>若某个字段的值含 | 或 ;，则按分隔符拆分后取条目数</li>
     *   <li>所有字段条目数的最大值即为该 Group 的实际行数</li>
     *   <li>若所有字段均为单值（无分隔符），返回 1</li>
     *   <li>若计算结果超过外层传入的 maxRows，以 maxRows 为上限（防止数据异常撑爆）</li>
     * </ul>
     *
     * @param groupChildren Group 下的所有子节点模板属性
     * @param dictCodeMap   字段字典映射
     * @param jsonMap       数据 Map
     * @param maxRows       外层 Table 计算出的最大行数（上限）
     * @return 该 Group 实际应展开的次数
     */
    private int calcGroupActualRows(List<XmlTemplateAttribute> groupChildren,
                                    Map<String, SysDictData> dictCodeMap,
                                    Map<String, Object> jsonMap,
                                    int maxRows) {
        int actual = 1;
        for (XmlTemplateAttribute attr : groupChildren) {
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString().trim();
            if (val.contains("|")) {
                actual = Math.max(actual, val.split("\\|", -1).length);
            } else if (val.contains(";")) {
                actual = Math.max(actual, val.split(";", -1).length);
            }
        }
        // 以外层 maxRows 为上限
        return Math.min(actual, maxRows);
    }

    /**
     * 按行取值：
     *  - rowIndex == -1：取完整值（非循环场景）
     *  - rowIndex >= 0：值若含分号则按行分割取第 rowIndex 个，否则所有行共用该值
     */
    private String getValueByRow(Map<String, Object> jsonMap, String dictLabel,
                                 String defaultValue, int rowIndex) {
        Object raw = jsonMap.get(dictLabel);
        if (raw == null) {
            return StringUtils.isNotBlank(defaultValue) ? defaultValue : "";
        }
        String val = raw.toString().trim();

        if (rowIndex < 0) {
            // 非循环场景：含分隔符的字段返回空，由上层展开逻辑处理
            return (val.contains("|") || val.contains(";")) ? "" : val;
        }

        // 循环场景：优先按 | 分割，其次按 ; 分割
        String separator = val.contains("|") ? "\\|" : (val.contains(";") ? ";" : null);
        if (separator == null) {
            return val;  // 单值，所有行共用
        }
        String[] items = val.split(separator, -1);
        if (rowIndex < items.length) {
            String item = items[rowIndex].trim();
            return item.isEmpty()
                    ? (StringUtils.isNotBlank(defaultValue) ? defaultValue : "")
                    : item;
        }
        return StringUtils.isNotBlank(defaultValue) ? defaultValue : "";
    }
    // =====================================================
    // 值提取工具方法
    // =====================================================

    /**
     * 按前缀提取值（上级循环）
     * 例：jsonMap中 key="ManufacturerPlaceOfResidence"，value="HEV1:北京;HEV2:柏林"，prefix="HEV1" → 返回"北京"
     * 若值不含分号，直接返回原值
     */
    private String extractValueByPrefix(Map<String, Object> jsonMap, String dictLabel,
                                        String prefix, int fallbackIndex) {
        Object raw = jsonMap.get(dictLabel);
        if (raw == null) return "";

        String val = raw.toString().trim();
        if (!val.contains(";")) {
            if (val.contains(":")) {
                int colon = val.indexOf(':');
                String itemPrefix = val.substring(0, colon).trim();
                if (prefix.equals(itemPrefix)) {
                    return val.substring(colon + 1).trim();
                }
                return fallbackIndex == 0 ? val.substring(colon + 1).trim() : "";
            }
            return val;
        }

        String[] items = val.split(";", -1);

        // 第一步：优先按前缀精确匹配
        for (String item : items) {
            item = item.trim();
            if (item.isEmpty()) continue;
            int colon = item.indexOf(':');
            if (colon > 0) {
                String itemPrefix = item.substring(0, colon).trim();
                if (prefix.equals(itemPrefix)) {
                    return item.substring(colon + 1).trim();
                }
            }
        }

        // 第二步：前缀匹配失败，按 fallbackIndex 位置取值
        if (fallbackIndex >= 0 && fallbackIndex < items.length) {
            String item = items[fallbackIndex].trim();
            if (!item.isEmpty()) {
                int colon = item.indexOf(':');
                if (colon > 0 && colon < item.length() - 1) {
                    return item.substring(colon + 1).trim();
                }
                return item;
            }
        }
        return "";
    }

    /**
     * 按索引提取值（同级循环）
     * 例：jsonMap中 key="ManufacturerPlaceOfResidence"，value="北京;柏林"，index=0 → 返回"北京"
     * 若值不含分号，直接返回原值（所有行共享同一值）
     */
    private String extractValueByIndex(Map<String, Object> jsonMap, String dictLabel, int index) {
        Object raw = jsonMap.get(dictLabel);
        if (raw == null) return "";

        String val = raw.toString().trim();
        if (!val.contains(";")) {
            if (val.contains(":")) {
                int colon = val.indexOf(':');
                return val.substring(colon + 1).trim();
            }
            return val;
        }

        String[] items = val.split(";", -1);
        if (index < 0 || index >= items.length) return "";

        String item = items[index].trim();
        if (item.isEmpty()) return "";

        int colon = item.indexOf(':');
        if (colon > 0 && colon < item.length() - 1) {
            return item.substring(colon + 1).trim();
        }
        return item;
    }

    // =====================================================
    // 模板匹配
    // =====================================================

    /**
     * 根据车辆信息匹配 XmlTemplate
     */
    private XmlTemplate matchTemplate(VehicleInfo vehicle) {
        List<XmlTemplate> templates = xmlTemplateMapper.selectTemplateAll();
        if (templates.isEmpty()) return null;

        for (XmlTemplate template : templates) {
            if (!Objects.equals(template.getModelDictCode(), vehicle.getVehicleModel())) {
                continue;
            }
            if (template.getIsLast().equals(0)) {
                continue;
            }
            return template;
        }
        return null;
    }

    // =====================================================
    // XML工具方法
    // =====================================================

    /**
     * 添加XML子元素
     * ★修改：若 textContent 为空且非必须（required=false）则不创建该元素（移除无值标签）；
     *        required=true 时即使无值也生成空标签，满足 is_required=1 语义。
     */
    private void addElement(Document doc, Element parent, String tagName, String textContent) {
        addElement(doc, parent, tagName, textContent, false);
    }

    private void addElement(Document doc, Element parent, String tagName, String textContent, boolean required) {
        if (StringUtils.isBlank(textContent) && !required) {
            // 无值且非必须则不添加该标签
            return;
        }
        // 与根节点保持命名空间一致，避免混用 createElement/createElementNS 导致序列化异常
        String nsUri = (doc.getDocumentElement() != null) ? doc.getDocumentElement().getNamespaceURI() : null;
        Element element = (nsUri != null) ? doc.createElementNS(nsUri, tagName) : doc.createElement(tagName);
        if (StringUtils.isNotBlank(textContent)) {
            element.setTextContent(textContent);
        }
        parent.appendChild(element);
    }

    /**
     * 获取路径的父路径
     * 例："1058.37.39.42" → "1058.37.39"
     * 若无父路径，返回 ""
     */
    private String getParentPath(String path) {
        if (StringUtils.isBlank(path)) return "";
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(0, lastDot) : "";
    }

    /**
     * 递归移除空节点（深度优先），感知 is_required 标志：
     * - is_required=1 的节点：无论有无值、有无子标签，均保留（强制生成）。
     * - is_required=0（默认）的节点：无值且无有效子标签时删除。
     *
     * 实现思路：
     *   1. 先按 tagName 反向查找该节点对应的模板属性，取得 is_required。
     *   2. 深度优先递归，先清理子孙，再决定当前节点去留。
     */
    private void removeEmptyStructNodes(Element element, List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap) {
        // 构建 tagName → isRequired 快查表（同一 tagName 只要有一个 required=1 即视为必须保留）
        Map<String, Boolean> tagRequiredMap = buildTagRequiredMap(attrList, dictCodeMap);

        removeEmptyStructNodesInternal(element, attrList, dictCodeMap, tagRequiredMap);
    }

    /**
     * 构建 XML 标签名（sanitized dictLabel）→ is_required 的映射。
     * 同一标签名若在模板中有多条记录，只要任意一条 is_required=1 则整体视为必须。
     */
    private Map<String, Boolean> buildTagRequiredMap(List<XmlTemplateAttribute> attrList,
                                                     Map<String, SysDictData> dictCodeMap) {
        Map<String, Boolean> map = new HashMap<>();
        for (XmlTemplateAttribute attr : attrList) {
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || StringUtils.isBlank(dict.getDictLabel())) continue;
            String tagName = sanitizeXmlTagName(dict.getDictLabel());
            boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
            // 只要有一条 required=1 就标记为必须
            map.merge(tagName, required, (existing, newVal) -> existing || newVal);
        }
        return map;
    }

    /**
     * 内部递归实现，接收预构建的 tagRequiredMap 避免重复计算。
     */
    private void removeEmptyStructNodesInternal(Element element, List<XmlTemplateAttribute> attrList,
                                                Map<String, SysDictData> dictCodeMap,
                                                Map<String, Boolean> tagRequiredMap) {
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element childElement = (Element) child;
                // 先递归处理子节点（深度优先）
                removeEmptyStructNodesInternal(childElement, attrList, dictCodeMap, tagRequiredMap);

                // 判断该节点是否标记为 is_required=1
                String childTag = childElement.getLocalName() != null
                        ? childElement.getLocalName() : childElement.getTagName();
                boolean isRequired = Boolean.TRUE.equals(tagRequiredMap.get(childTag));

                if (isRequired) {
                    // is_required=1：无论有无内容，强制保留，不做任何删除
                    continue;
                }

                // is_required=0（或未配置）：无值 / 无有效子孙时删除
                int childCount = childElement.getChildNodes().getLength();
                if (childCount == 0 || !hasNonEmptyDescendantText(childElement)) {
                    element.removeChild(childElement);
                }
            }
        }
    }

    /**
     * 递归判断元素是否包含任何非空文本内容的后代节点。
     * 返回 true 表示存在至少一个有实质内容的叶子文本；false 表示全空。
     */
    private boolean hasNonEmptyDescendantText(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                if (child.getTextContent() != null && !child.getTextContent().trim().isEmpty()) {
                    return true;
                }
            } else if (child instanceof Element) {
                if (hasNonEmptyDescendantText((Element) child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 清洗字符串为合法 XML tag 名
     */
    /**
     * 判断字典项是否为容器节点（结构节点）。
     * dictValue 为 "NULL"、null 或空字符串时均视为容器节点，不含实际值。
     */
    private boolean isStructNode(SysDictData dict) {
        return StringUtils.isBlank(dict.getDictValue())
                || "NULL".equalsIgnoreCase(dict.getDictValue());
    }

    /**
     * 解析 defaultValue 中的所有属性，按类型分类处理后写入元素。
     * <p>
     * defaultValue 格式示例（空格分隔多属性）：
     *   xmlns="http://example.com" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     *   xsi:noNamespaceSchemaLocation="schema.xsd" version="1.0"
     * <p>
     * 属性分三类：
     *   1. xmlns="..."               → 默认命名空间，由 createElementNS 在创建时处理，此处跳过
     *   2. xmlns:prefix="..."        → 前缀命名空间声明，用 setAttributeNS 写入
     *   3. prefix:localName="..."    → 带命名空间前缀的普通属性，用 setAttributeNS 写入
     *   4. 其余普通属性               → 用 setAttribute 写入
     */
    private void applyDefaultValueAsAttributes(Element element, String defaultValue) {
        if (StringUtils.isBlank(defaultValue)) return;
        // 解析所有已声明的命名空间前缀（xmlns:prefix="uri"），供后续属性使用
        Map<String, String> nsPrefixMap = new LinkedHashMap<>();
        java.util.regex.Pattern nsDecl = java.util.regex.Pattern.compile(
                "xmlns:([\\w.-]+)\\s*=\\s*[\"']([^\"']*)[\"']");
        java.util.regex.Matcher nsm = nsDecl.matcher(defaultValue.trim());
        while (nsm.find()) {
            nsPrefixMap.put(nsm.group(1), nsm.group(2));
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "([\\w:.-]+)\\s*=\\s*[\"']([^\"']*)[\"']");
        java.util.regex.Matcher matcher = pattern.matcher(defaultValue.trim());
        while (matcher.find()) {
            String attrName  = matcher.group(1);
            String attrValue = matcher.group(2);

            if ("xmlns".equals(attrName)) {
                // 默认命名空间由 createElementNS 处理，跳过
                continue;
            } else if (attrName.startsWith("xmlns:")) {
                // 前缀命名空间声明：xmlns:xsi="http://..."
                element.setAttributeNS(
                        "http://www.w3.org/2000/xmlns/",
                        attrName,
                        attrValue);
            } else if (attrName.contains(":")) {
                // 带前缀的属性：xsi:noNamespaceSchemaLocation="..."
                String prefix = attrName.substring(0, attrName.indexOf(':'));
                String nsUri  = nsPrefixMap.get(prefix);
                if (nsUri != null) {
                    element.setAttributeNS(nsUri, attrName, attrValue);
                } else {
                    // 前缀未在同一 defaultValue 中声明，降级为普通属性
                    element.setAttribute(attrName, attrValue);
                }
            } else {
                // 普通属性
                element.setAttribute(attrName, attrValue);
            }
        }
    }

    /**
     * 从 defaultValue 中提取默认命名空间 URI（xmlns="..."）。
     * 返回 null 表示没有默认命名空间声明。
     */
    private String extractNamespaceUri(String defaultValue) {
        if (StringUtils.isBlank(defaultValue)) return null;
        // 匹配 xmlns="..." 但排除 xmlns:prefix="..."
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "xmlns(?!:)\\s*=\\s*[\"']([^\"']*)[\"']");
        java.util.regex.Matcher matcher = pattern.matcher(defaultValue.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 创建 XML 元素并应用 defaultValue 中的所有属性声明。
     * <ul>
     *   <li>若含 xmlns="..."，用 createElementNS 创建命名空间感知元素（避免 setAttribute("xmlns",...) 的 DOM 异常）</li>
     *   <li>xmlns:prefix、prefix:attr 等通过 setAttributeNS 正确写入</li>
     *   <li>普通属性通过 setAttribute 写入</li>
     * </ul>
     */
    private Element createElementWithDefault(Document doc, String tagName, String defaultValue) {
        // 自动继承根节点命名空间：根节点用 createElementNS 创建后，Document 进入命名空间感知模式，
        // 子节点若用 createElement 创建会引发序列化异常，需统一用 createElementNS 并继承根节点 ns。
        String inheritedNsUri = null;
        if (doc.getDocumentElement() != null) {
            inheritedNsUri = doc.getDocumentElement().getNamespaceURI();
        }
        return createElementWithDefault(doc, tagName, defaultValue, inheritedNsUri);
    }

    /**
     * 创建元素，支持命名空间继承。
     * @param inheritedNsUri 父节点的命名空间 URI（当 defaultValue 中无 xmlns 时继承使用）
     */
    private Element createElementWithDefault(Document doc, String tagName, String defaultValue, String inheritedNsUri) {
        String nsUri = extractNamespaceUri(defaultValue);
        Element element;
        if (nsUri != null) {
            element = doc.createElementNS(nsUri, tagName);
        } else if (StringUtils.isNotBlank(inheritedNsUri)) {
            // 无独立命名空间声明，继承上级命名空间，避免非命名空间感知节点挂在命名空间感知根节点下引发序列化异常
            element = doc.createElementNS(inheritedNsUri, tagName);
        } else {
            element = doc.createElement(tagName);
        }
        applyDefaultValueAsAttributes(element, defaultValue);
        return element;
    }


    private String sanitizeXmlTagName(String raw) {
        if (StringUtils.isBlank(raw)) return "field";
        String cleaned = raw.trim().replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        if (cleaned.matches("^[0-9\\-].*")) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    /**
     * 取 jsonMap 中对应 key 的值；若值为空（null 或空白字符串），回退到模板属性的 defaultValue。
     *
     * @param raw          已从 jsonMap 中取出的原始值（可为 null）
     * @param defaultValue XmlTemplateAttribute.defaultValue（可为 null）
     * @return 最终使用的字符串值，永不为 null
     */
    private String getValueOrDefault(Object raw, String defaultValue) {
        if (raw != null) {
            String val = raw.toString();
            if (StringUtils.isNotBlank(val)) {
                return val;
            }
        }
        return StringUtils.isNotBlank(defaultValue) ? defaultValue : "";
    }

    /**
     * 构建 keyMap → List<[tagLabel, rule, rangeRule]> 映射。
     * ★ 改为从全量字典（dictCodeMap）构建，不再依赖模板 attrList，
     *   避免模板未配置的字段（如 18.4.）在校验结果中无法映射为 tagLabel。
     * ★ 同一 keyMap 可能对应多个字段（如 BodyworkTypeTrailer / BrakedTypeTrail 共享 18.4.），
     *   改用 List 存储，防止 HashMap 覆盖导致其中一个字段丢失。
     */
    private Map<String, List<String[]>> buildKeyMapMeta(Map<String, SysDictData> dictCodeMap) {
        Map<String, List<String[]>> keyMapMeta = new HashMap<>();
        for (SysDictData dict : dictCodeMap.values()) {
            if (StringUtils.isBlank(dict.getKeyMap())) continue;
            if (isStructNode(dict)) continue;
            keyMapMeta
                    .computeIfAbsent(dict.getKeyMap(), k -> new ArrayList<>())
                    .add(new String[]{
                            sanitizeXmlTagName(dict.getDictLabel()),
                            StringUtils.defaultString(dict.getRule()),
                            StringUtils.defaultString(dict.getRangeRule())
                    });
        }
        return keyMapMeta;
    }

    /**
     * 补充 violation 消息，并将 report 合并到 merged 中。
     * keyMapMeta 的 value 改为 List，支持同一 keyMap 对应多个字段（如 18.4. 对应
     * BodyworkTypeTrailer 和 BrakedTypeTrail），显示时拼接为 "A / B" 形式。
     */
    private ValidationReport enrichAndMerge(ValidationReport merged,
                                            ValidationReport report,
                                            Map<String, List<String[]>> keyMapMeta) {
        if (report == null) return merged;

        if (report.getFieldResults() != null) {
            for (FieldValidationResult fr : report.getFieldResults()) {
                List<String[]> metaList = keyMapMeta.get(fr.getFieldName());
                // 全量字典里也找不到（如 STRUCTURE 等虚拟字段），保持原 fieldName 不变
                if (metaList == null || metaList.isEmpty()) continue;

                // 同一 keyMap 对应多个字段时，tagLabel 拼接展示；rule/rangeRule 取唯一值，多值时留空
                String tagLabel;
                String rule;
                String rangeRule;
                if (metaList.size() == 1) {
                    tagLabel  = metaList.get(0)[0];
                    rule      = metaList.get(0)[1];
                    rangeRule = metaList.get(0)[2];
                } else {
                    tagLabel  = metaList.stream()
                            .map(m -> m[0])
                            .distinct()
                            .collect(Collectors.joining(" / "));
                    // 多字段共享 keyMap 时，rule 描述从 violation 自身的 rawRule 取，此处留空
                    rule      = "";
                    rangeRule = "";
                }

                fr.setFieldName(tagLabel);
                if (fr.isValid() || fr.getViolations() == null) continue;

                String ruleDescEn = buildRuleDesc(rule, rangeRule, false);
                String ruleDescZh = buildRuleDesc(rule, rangeRule, true);
                for (RuleViolation v : fr.getViolations()) {
                    // 多字段共享 keyMap 时，用 violation 自身的 rawRule 补充描述
                    String effectiveRuleDescEn = StringUtils.isNotBlank(ruleDescEn) ? ruleDescEn
                            : (StringUtils.isNotBlank(v.getRawRule()) ? " (rule: " + v.getRawRule() + ")" : "");
                    String effectiveRuleDescZh = StringUtils.isNotBlank(ruleDescZh) ? ruleDescZh
                            : (StringUtils.isNotBlank(v.getRawRule()) ? "（rule：" + v.getRawRule() + "）" : "");
                    if (StringUtils.isBlank(v.getMessageEn())) {
                        v.setMessageEn(String.format(
                                "Tag <%s> value \"%s\" failed validation%s",
                                tagLabel, fr.getValue(), effectiveRuleDescEn));
                    }
                    if (StringUtils.isBlank(v.getMessageZh())) {
                        v.setMessageZh(String.format(
                                "标签 <%s> 的值 \"%s\" 不满足校验规则%s",
                                tagLabel, fr.getValue(), effectiveRuleDescZh));
                    }
                }
            }
        }

        // 合并：首个直接返回，后续追加 fieldResults
        if (merged == null) return report;
        if (report.getFieldResults() != null) {
            merged.getFieldResults().addAll(report.getFieldResults());
        }
        if (!report.isAllValid()) {
            merged.setAllValid(false);
        }
        return merged;
    }

    /**
     * 在循环节点的 violation 消息里追加序号，便于定位是第几个
     */
    private void appendLoopIndex(RuleViolation v, String tagName, int index) {
        String suffix   = String.format(" [%s #%d]", tagName, index);
        String suffixZh = String.format("【%s 第%d项】", tagName, index);
        if (StringUtils.isNotBlank(v.getMessageEn())) {
            v.setMessageEn(v.getMessageEn() + suffix);
        }
        if (StringUtils.isNotBlank(v.getMessageZh())) {
            v.setMessageZh(v.getMessageZh() + suffixZh);
        }
    }

    private String buildRuleDesc(String rule, String range, boolean zh) {
        boolean hasRule  = StringUtils.isNotBlank(rule);
        boolean hasRange = StringUtils.isNotBlank(range);
        if (!hasRule && !hasRange) return "";
        if (zh) {
            if (hasRule && hasRange) return "（rule：" + rule + "，rangeRule：" + range + "）";
            if (hasRule)             return "（rule：" + rule + "）";
            return                          "（rangeRule：" + range + "）";
        } else {
            if (hasRule && hasRange) return " (rule: " + rule + ", rangeRule: " + range + ")";
            if (hasRule)             return " (rule: " + rule + ")";
            return                          " (rangeRule: " + range + ")";
        }
    }

    private Map<String, String> getVehicleParams(VehicleInfo vehicle) {
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(vehicle.getVehicleId()));
        params.put("vin", vehicle.getVin());
        params.put("vehicleModel", vehicle.getVehicleModel());
        params.put("factoryCode", vehicle.getFactoryCode());
        params.put("country", vehicle.getCountry());
        params.put("issueDate", com.ruoyi.common.core.utils.DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicle.getIssueDate()));
        params.put("materialNo", vehicle.getMaterialNo());
        return params;
    }

    /**
     * 校验车辆是否允许生成 XML
     * 规则：该车辆关联物料号的首台车必须已确认（generate_affirm=1），
     *       或者该车辆本身就是首台车（first_material_flag=1）
     */
    private void checkGeneratePermission(VehicleInfo vehicleInfo) {
        // 首台车本身始终允许操作
        if (Integer.valueOf(1).equals(vehicleInfo.getFirstMaterialFlag())) {
            return;
        }
        // 其他车辆：检查该物料号下是否已有确认记录
        boolean confirmed = vehicleInfoMapper.existsConfirmedMaterial(vehicleInfo.getMaterialNo());
        if (!confirmed) {
            throw new ServiceException("该物料号首台车尚未确认生成，当前车辆暂不可生成");
        }
    }

    /**
     * 校验车辆是否允许上传 XML
     * 规则：该车辆关联模版的首台车必须已确认（upload_affirm=1），
     *       或者该车辆本身就是首台车（first_template_flag=1）
     */
    private void checkUploadPermission(VehicleInfo vehicleInfo) {
        // 首台车本身始终允许操作
        if (Integer.valueOf(1).equals(vehicleInfo.getFirstTemplateFlag())) {
            return;
        }
        // 其他车辆：检查该模版下是否已有确认记录
        boolean confirmed = vehicleInfoMapper.existsConfirmedTemplate(vehicleInfo.getVehicleTemplateId());
        if (!confirmed) {
            throw new ServiceException("该模版首台车尚未确认上传，当前车辆暂不可上传");
        }
    }
}