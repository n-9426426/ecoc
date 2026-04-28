package com.ruoyi.vehicle.service.impl;

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
import com.ruoyi.vehicle.domain.*;
import com.ruoyi.vehicle.domain.vo.DiffLineVO;
import com.ruoyi.vehicle.domain.vo.DiffResultVO;
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询XML文件列表
     */
    @Override
    public List<XmlFile> selectXmlFileList(XmlFile xmlFile) {
        return xmlFileMapper.selectXmlFileList(xmlFile);}

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
        String vin = oldFileName.split("_")[0];
        vin = oldFileName.split("\\.")[0];

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

        xmlFileMapper.updateIsLatestToFalse(newFileName);

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
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("XML文件校验完成通知");
        StringBuilder msg = new StringBuilder("XML文件");
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
            xmlFile.setUploadResult(validateResult ? "2" : "1");
            xmlFile.setValidationReportJson(objectMapper.writeValueAsString(finalReport));
            xmlFileMapper.updateXmlFile(xmlFile);

            // 记录生命周期
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setEntryId(xmlFile.getId());
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(xmlFile.getVin());
            vehicleLifecycle.setOperate("3");
            vehicleLifecycle.setResult(validateResult ? 0 : 1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            msg.append(System.lineSeparator());
            msg.append("Vin");
            msg.append(xmlFile.getVin());
            msg.append("的校验结果为");
            msg.append(finalReport.isAllValid() ? "通过" : "失败");

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
            sysNotice.setNoticeContent(msg.toString());
            remoteNoticeService.add(sysNotice);
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
                    dictCodeMap.put(String.valueOf(d.getDictCode()), d);
                }
            }

            // 3. 构建"模板标签路径 → 是否循环节点"映射
            //    循环节点定义：该路径对应dict_value='NULL'且其子孙叶子节点中至少一个值含分号
            Map<String, String> attrPathToTagName = new LinkedHashMap<>(); // attrPath → tagName
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                if (d != null && StringUtils.isNotBlank(d.getDictLabel())) {
                    attrPathToTagName.put(attr.getAttrPath(),
                            sanitizeXmlTagName(d.getDictLabel()));
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
            List<XmlTemplateAttribute> sortedAttrList = attrList.stream()
                    .sorted(Comparator.comparingInt(a -> a.getAttrPath().split("\\.").length))
                    .collect(Collectors.toList());
            Map<String, Boolean> tagPathIsLoop = new LinkedHashMap<>();
            Map<String, String> attrPathToTagPath = new HashMap<>();
            for (XmlTemplateAttribute attr : sortedAttrList) {
                String attrPath = attr.getAttrPath();
                String tagName = attrPathToTagName.get(attrPath);
                String parentAttrPath = getParentPath(attrPath);
                String parentTagPath = attrPathToTagPath.getOrDefault(parentAttrPath, "");
                if (tagName == null) {
                    // ★ 字典缺失时：用父 tagPath 占位，保证子节点的 parentTagPath 不丢失
                    attrPathToTagPath.put(attrPath, parentTagPath);
                    continue;
                }
                String tagPath = parentTagPath.isEmpty() ? tagName : parentTagPath + "/" + tagName;
                attrPathToTagPath.put(attrPath, tagPath);
                boolean isLoop = loopContainerPaths.contains(attrPath);
                tagPathIsLoop.put(tagPath, isLoop);
            }

            // 6. 对XML DOM做DFS遍历，按层级路径逐节点与模板比对
            Element root = doc.getDocumentElement();
            checkElementStructure(root, "", tagPathIsLoop, results);

        } catch (Exception e) {
            results.add(buildStructureFieldResult("STRUCTURE",
                    "Structure validation exception: " + e.getMessage(),
                    "结构校验异常：" + e.getMessage()));
            log.error("XML结构校验失败", e);
        }
    }

    /**
     * 递归校验Element是否在模板定义的tagPath集合中。
     * @param element       当前DOM节点
     * @param parentPath    当前节点的父级tagPath（空字符串表示在根之上）
     * @param tagPathIsLoop 模板tagPath → 是否循环容器
     * @param results       校验结果收集列表
     * ★ 改造：遇到未定义标签不再提前 return，继续处理其余兄弟节点，
     *         确保同级所有问题全部写入报告；三类错误均包含完整路径信息，双语输出。
     */
    private void checkElementStructure(Element element, String parentPath,
                                       Map<String, Boolean> tagPathIsLoop,
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
                    } else {
                        // 非循环节点缺失 → 校验失败
                        results.add(buildStructureFieldResult("STRUCTURE",
                                String.format("Missing required tag <%s> under \"%s\" (expected path: \"%s\")",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag),
                                String.format("缺少模板要求的标签 <%s>，父节点路径：%s（期望完整路径：%s）",
                                        expectedChildTag, currentPath, currentPath + "/" + expectedChildTag)));
                    }
                }
            }
        }

        // ★ 递归处理全部子节点
        for (Element childEl : childElements) {
            checkElementStructure(childEl, currentPath, tagPathIsLoop, results);
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
        List<XmlTemplateAttribute> leafNodes = attrList.stream()
                .filter(a -> {
                    String[] parts = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                    return d != null && !"NULL".equalsIgnoreCase(d.getDictValue());
                }).collect(Collectors.toList());

        LoopDetectionResult loopResult = detectLoopPattern(leafNodes, dictCodeMap, jsonMap);
        if (loopResult.getLoopMode() == LoopMode.NONE
                || StringUtils.isBlank(loopResult.getLoopContainerPath())) {
            return result;
        }

        String loopContainerPath = loopResult.getLoopContainerPath();
        String triggerPath = loopResult.getTriggerAttr().getAttrPath();

        if (loopResult.getLoopMode() == LoopMode.PARENT_LEVEL) {
            // ★修复：上级循环时，loopContainerPath（ManufacturerTable）本身是重复出现的节点
            result.add(loopContainerPath);
        } else {
            // 同级循环：loopContainerPath 下包含触发字段的直接子结构（ManufacturerGroup）是循环节点
            for (XmlTemplateAttribute attr : attrList) {
                String p = attr.getAttrPath();
                if (!p.startsWith(loopContainerPath + ".")) continue;
                if (p.split("\\.").length != loopContainerPath.split("\\.").length + 1) continue;
                String[] parts = p.split("\\.");
                SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                if (d == null || !"NULL".equalsIgnoreCase(d.getDictValue())) continue;
                if (triggerPath.startsWith(p + ".")) {
                    result.add(p);
                }
            }
        }
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
            // 1. 获取车辆信息（用于匹配模板和提取 vehicleCategory/stageOfCompletion）
            xmlFile = xmlFileMapper.selectXmlFileById(xmlFile.getId());
            if (xmlFile == null) {
                throw new ServiceException("该文件不存在");
            }
            String vin = xmlFile.getFileName().split("_")[1];
            vin = vin.split("\\.")[0];
            VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoByVin(vin);
            if (vehicle == null) return null;

            XmlTemplate template = matchTemplate(vehicle);
            if (template == null) return null;

            // 2. 查询模板属性列表和字典映射
            List<XmlTemplateAttribute> attrList = xmlTemplateAttributeMapper.selectByTemplateId(template.getTemplateId());
            if (attrList == null || attrList.isEmpty()) return null;

            List<SysDictData> dictDataList = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            Map<String, SysDictData> dictCodeMap = new HashMap<>();
            for (SysDictData d : dictDataList) {
                if (d.getDictCode() != null) {
                    dictCodeMap.put(String.valueOf(d.getDictCode()), d);
                }
            }

            // 3. 遍历叶子节点，从 XML DOM 中读取值，以 keyMap 为键重建 jsonMap
            //    ★ 改造：不再跳过无 rule/rangeRule 字段——所有有 keyMap 的字段都放入 jsonMap，
            //           保证条件规则的上下文完整，规则引擎对全量字段执行校验
            Map<String, Object> reconstructedJsonMap = new LinkedHashMap<>();

            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null) continue;
                // 只处理叶子节点（dict_value != NULL）且有 keyMap 的字段
                if ("NULL".equalsIgnoreCase(dict.getDictValue())) continue;
                if (StringUtils.isBlank(dict.getKeyMap())) continue;
                // ★ 不再跳过无规则字段，全部纳入 jsonMap 以支持条件规则上下文

                String tagName = sanitizeXmlTagName(dict.getDictLabel());
                NodeList nodeList = doc.getElementsByTagName(tagName);
                if (nodeList.getLength() == 0) continue;

                if (nodeList.getLength() == 1) {
                    // 非循环节点：直接取值
                    String value = nodeList.item(0).getTextContent();
                    reconstructedJsonMap.put(dict.getKeyMap(), value);
                } else {
                    // 循环节点：拼接为分号分隔字符串，与原始 json 格式对齐
                    StringJoiner joiner = new StringJoiner(";");
                    for (int i = 0; i < nodeList.getLength(); i++) {
                        String v = nodeList.item(i).getTextContent();
                        joiner.add(v != null ? v : "");
                    }
                    reconstructedJsonMap.put(dict.getKeyMap(), joiner.toString());
                }
            }

            if (reconstructedJsonMap.isEmpty()) return null;

            // 4. 提取 vehicleCategory / stageOfCompletion（优先取 XML，回退到车辆信息）
            String vehicleCategory  = extractTextByKeyMap(doc, dictCodeMap, attrList, "vehicleCategory");
            String stageOfCompletion = extractTextByKeyMap(doc, dictCodeMap, attrList, "stageOfCompletion");
            if (vehicleCategory == null && vehicle.getJsonMap() != null) {
                Object v = vehicle.getJsonMap().get("vehicleCategory");
                vehicleCategory = v != null ? v.toString() : null;
            }
            if (stageOfCompletion == null && vehicle.getJsonMap() != null) {
                Object v = vehicle.getJsonMap().get("stageOfCompletion");
                stageOfCompletion = v != null ? v.toString() : null;
            }

            // 5. 序列化 jsonMap → JSON 字符串，调用规则引擎（全量字段均参与，报告包含所有不通过项）
            String jsonStr = new ObjectMapper().writeValueAsString(reconstructedJsonMap);
            ValidationReport report = vehicleValidationService.validate(jsonStr, vehicleCategory, stageOfCompletion);

            // ★ 对规则校验不通过的字段，将标签名和规则内容补充到 violation 消息中，
            //    风格与 FinalRuleExecutor.buildViolation 保持一致（英文在前、中文在后）
            if (report != null && report.getFieldResults() != null) {
                // 构建 keyMap → (dictLabel标签名, rule, rangeRule) 辅助映射
                Map<String, String[]> keyMapMeta = new HashMap<>();
                for (XmlTemplateAttribute attr : attrList) {
                    String[] parts = attr.getAttrPath().split("\\.");
                    SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                    if (dict == null || StringUtils.isBlank(dict.getKeyMap())) continue;
                    keyMapMeta.put(dict.getKeyMap(), new String[]{
                            sanitizeXmlTagName(dict.getDictLabel()),
                            StringUtils.defaultString(dict.getRule()),
                            StringUtils.defaultString(dict.getRangeRule())
                    });
                }
                for (FieldValidationResult fr : report.getFieldResults()) {
                    String[] meta = keyMapMeta.get(fr.getFieldName());
                    if (meta == null) continue;
                    String tagLabel = meta[0];
                    // ★ 将 fieldName 由 keyMap（如 "11.1"）映射为 dictLabel（如 "ManufacturerPlaceOfResidence"）
                    fr.setFieldName(tagLabel);
                    if (fr.isValid() || fr.getViolations() == null) continue;
                    String rule     = meta[1];
                    String range    = meta[2];
                    String ruleDesc = "";
                    if (!rule.isEmpty() && !range.isEmpty()) {
                        ruleDesc = " (rule: " + rule + ", rangeRule: " + range + ")";
                    } else if (!rule.isEmpty()) {
                        ruleDesc = " (rule: " + rule + ")";
                    } else if (!range.isEmpty()) {
                        ruleDesc = " (rangeRule: " + range + ")";
                    }
                    String ruleDescZh = "";
                    if (!rule.isEmpty() && !range.isEmpty()) {
                        ruleDescZh = "（rule：" + rule + "，rangeRule：" + range + "）";
                    } else if (!rule.isEmpty()) {
                        ruleDescZh = "（rule：" + rule + "）";
                    } else if (!range.isEmpty()) {
                        ruleDescZh = "（rangeRule：" + range + "）";
                    }
                    for (RuleViolation v : fr.getViolations()) {
                        if (StringUtils.isBlank(v.getMessageEn())) {
                            v.setMessageEn(String.format(
                                    "Tag <%s> value \"%s\" failed validation%s",
                                    tagLabel, fr.getValue(), ruleDesc));
                        }
                        if (StringUtils.isBlank(v.getMessageZh())) {
                            v.setMessageZh(String.format(
                                    "标签 <%s> 的值 \"%s\" 不满足校验规则%s",
                                    tagLabel, fr.getValue(), ruleDescZh));
                        }
                    }
                }
            }
            return report;

        } catch (Exception e) {
            log.error("XML数据校验失败", e);
            ValidationReport errReport = ValidationReport.fail("数据规则校验异常：" + e.getMessage());
            return errReport;
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
     *规则：
     * 1. json值中含分号 → 触发循环
     * 2. 所有含分号字段均为"前缀:值"格式 → 上级循环（父容器级别）
     * 3. 至少一个含分号字段为"值;值"无前缀格式 → 同级循环（子结构级别）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateXmlFromDatabase(Long vehicleId) {
        try {
            SysNotice sysNotice = new SysNotice();
            sysNotice.setIsRead(false);
            sysNotice.setNoticeType("1");
            sysNotice.setNoticeTitle("XML文件生成通知");
            StringBuilder msg = new StringBuilder("XML文件");
            msg.append(System.lineSeparator());
            msg.append("Vin");

            // 1. 查询车辆信息
            VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoById(vehicleId);
            if (vehicle == null) {
                throw new RuntimeException("车辆信息不存在");
            }
            if (vehicle.getStatus().equals(1)) {
                throw new RuntimeException("车辆信息已停用");
            }
            msg.append(vehicle.getVin());
            msg.append("的生成结果为");
            Map<String, Object> jsonMap = vehicle.getJsonMap();
            if (jsonMap == null) {
                jsonMap = new HashMap<>();
            }

            // 2.匹配模板
            XmlTemplate xmlTemplate = matchTemplate(vehicle);
            if (xmlTemplate == null) {
                msg.append("失败");
                sysNotice.setNoticeContent(msg.toString());
                remoteNoticeService.add(sysNotice);
                throw new RuntimeException("未找到匹配的XML模板，VIN=" + vehicle.getVin());
            }

            // 3. 查询字典数据，构建 dictCode -> SysDictData 映射
            List<SysDictData> dictDataList = remoteDictService.getDictDataByType("vehicle_attribute").getData();
            Map<String, SysDictData> dictCodeMap = new HashMap<>();
            for (SysDictData d : dictDataList) {
                if (d.getDictCode() != null) {
                    dictCodeMap.put(String.valueOf(d.getDictCode()), d);
                }
            }

            // 4. 查询模板属性列表
            List<XmlTemplateAttribute> attrList = xmlTemplateAttributeMapper.selectByTemplateId(xmlTemplate.getTemplateId());
            if (attrList == null || attrList.isEmpty()) {
                msg.append("失败");
                sysNotice.setNoticeContent(msg.toString());
                remoteNoticeService.add(sysNotice);
                throw new ServiceException("模板无属性定义，无法生成XML");
            }

            // 5. 单根节点校验
            List<XmlTemplateAttribute> topLevelAttrs = attrList.stream()
                    .filter(a -> a.getAttrPath() != null && a.getAttrPath().split("\\.").length == 1)
                    .collect(Collectors.toList());
            if (topLevelAttrs.isEmpty()) {
                msg.append("失败");
                sysNotice.setNoticeContent(msg.toString());
                remoteNoticeService.add(sysNotice);
                throw new ServiceException("模板无顶层节点，XML必须有唯一根节点");
            }
            if (topLevelAttrs.size() > 1) {
                msg.append("失败");
                sysNotice.setNoticeContent(msg.toString());
                remoteNoticeService.add(sysNotice);
                throw new ServiceException("模板存在多个顶层节点，XML 不允许多根节点");
            }

            // 6. 按模板定义顺序排序：先按路径深度（父先于子），同深度下按各层级 sort_order 组合键排序
            //    构建 attrPath → sortOrder 查找表，用于 comparator
            Map<String, Integer> pathSortOrderMap = new HashMap<>();
            for (XmlTemplateAttribute a : attrList) {
                if (a.getAttrPath() != null) {
                    pathSortOrderMap.put(a.getAttrPath(), a.getSortOrder() != null ? a.getSortOrder() : 0);
                }
            }
            // comparator：将每个节点的 attrPath 按"."拆分，逐段累积路径查 sortOrder，
            // 形成祖先链 sort_order 序列（如 [1,2,5,1]），按列表字典序比较，
            // 保证父节点先于子节点、同级节点按 sort_order 正确排列。
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
                // 前缀完全相同时，较短路径（祖先）排前面
                return Integer.compare(partsA.length, partsB.length);
            };
            attrList.sort(templateOrderComparator);

            // 7. 创建XML文档
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            // 8. 创建根节点
            String rootAttrPath = topLevelAttrs.get(0).getAttrPath();
            SysDictData rootDict = dictCodeMap.get(rootAttrPath);
            String rootTagName = (rootDict != null && StringUtils.isNotBlank(rootDict.getDictLabel()))
                    ? sanitizeXmlTagName(rootDict.getDictLabel()) : "Root";
            Element root = doc.createElement(rootTagName);
            doc.appendChild(root);

            // 9. 路径 -> Element 映射（记录已创建的节点）
            Map<String, Element> pathNodeMap = new LinkedHashMap<>();
            pathNodeMap.put(rootAttrPath, root);

            // 10. 识别所有结构节点（dict_value = "NULL"，表示容器节点，不含实际值）
            Set<String> structNodePaths = attrList.stream()
                    .filter(a -> {
                        String[] parts = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                        return d != null && "NULL".equalsIgnoreCase(d.getDictValue());
                    })
                    .map(XmlTemplateAttribute::getAttrPath)
                    .collect(Collectors.toSet());

            // 11. 识别所有叶子节点（dict_value ！= "NULL"，对应 json 中的实际值）
            List<XmlTemplateAttribute> leafNodes = attrList.stream()
                    .filter(a -> {
                        String[] parts = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                        return d != null && !"NULL".equalsIgnoreCase(d.getDictValue());
                    })
                    .collect(Collectors.toList());

            // 12. 检测循环模式
            LoopDetectionResult loopResult = detectLoopPattern(leafNodes, dictCodeMap, jsonMap);

            if (loopResult.getLoopMode() == LoopMode.NONE) {
                // 无循环 → 普通树结构
                buildNormalTree(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, rootAttrPath);
            } else if (loopResult.getLoopMode() == LoopMode.PARENT_LEVEL) {
                // 上级循环 → 在父容器级别循环
                buildParentLevelLoop(doc, root, attrList, dictCodeMap, jsonMap,
                        pathNodeMap, structNodePaths, loopResult, rootAttrPath);
            } else {
                // 同级循环 → 在子结构级别循环
                buildSiblingLevelLoop(doc, root, attrList, dictCodeMap, jsonMap,
                        pathNodeMap, structNodePaths, loopResult, rootAttrPath);
            }

            // 13. 移除空结构节点
            removeEmptyStructNodes(root, attrList, dictCodeMap);

            // 14. 生成XML字符串
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
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
            vehicle.setUploadStatus(1);
            vehicleInfoService.updateVehicleInfo(vehicle);

            // 20. 记录生命周期
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setEntryId(vehicle.getVehicleId());
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(vehicle.getVin());
            vehicleLifecycle.setOperate("2");
            vehicleLifecycle.setResult(1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            msg.append("成功");
            sysNotice.setNoticeContent(msg.toString());
            remoteNoticeService.add(sysNotice);

            return xmlContent;

        } catch (Exception e) {
            log.error("生成XML文件失败", e);
            throw new RuntimeException("生成XML失败: " + e.getMessage());
        }
    }

    // =====================================================
    // 循环检测
    // =====================================================

    /**
     * 检测循环模式：
     * -遍历所有叶子节点，找出值中含分号的字段
     * - 若所有含分号字段都是"前缀:值"格式→ PARENT_LEVEL（上级循环）
     * - 若至少一个含分号字段是"值;值"无前缀格式 → SIBLING_LEVEL（同级循环）
     * - 无含分号字段 → NONE
     */
    private LoopDetectionResult detectLoopPattern(List<XmlTemplateAttribute> leafNodes,Map<String, SysDictData> dictCodeMap,
                                                  Map<String, Object> jsonMap) {
        LoopDetectionResult result = new LoopDetectionResult();

        boolean hasPrefix = false;      // 存在"前缀:值;前缀:值"格式
        boolean hasNonPrefix = false;   // 存在"值;值"无前缀格式
        int maxRows = 1;
        // ★修复：使用"路径最深"的触发字段，而非第一个，
        //   避免浅层字段（如 CategoryHybridElectricVehicle）把循环容器定位到错误层级。
        XmlTemplateAttribute deepestTriggerAttr = null;
        Set<String> allPrefixes = new LinkedHashSet<>();

        for (XmlTemplateAttribute leaf : leafNodes) {
            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            if (d == null || StringUtils.isBlank(d.getKeyMap())) continue;

            Object raw = jsonMap.get(d.getKeyMap());
            if (raw == null) continue;

            String val = raw.toString().trim();
            if (!val.contains(";")) continue;

            // 发现含分号字段 → 取路径最深的作为 triggerAttr
            if (deepestTriggerAttr == null ||
                    leaf.getAttrPath().split("\\.").length >
                            deepestTriggerAttr.getAttrPath().split("\\.").length) {
                deepestTriggerAttr = leaf;
            }

            String[] items = val.split(";", -1);
            maxRows = Math.max(maxRows, items.length);

            // 判断该字段是否所有项都有前缀（"xxx:yyy"格式）
            boolean allItemsHavePrefix = true;
            for (String item : items) {
                item = item.trim();
                if (item.isEmpty()) continue;
                if (!item.contains(":")) {
                    allItemsHavePrefix = false;
                    break;
                }}

            if (allItemsHavePrefix) {
                hasPrefix = true;
                // 提取所有前缀键
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
            // 无分号字段，无需循环
            result.setLoopMode(LoopMode.NONE);
            return result;
        }

        result.setTriggerAttr(deepestTriggerAttr);

        if (hasPrefix && !hasNonPrefix) {
            // 所有字段均为前缀模式 → 上级循环
            // ★ loopContainerPath = 触发字段上两级（ManufacturerGroup 的父 = ManufacturerTable）
            result.setLoopMode(LoopMode.PARENT_LEVEL);
            result.setGroupKeys(new ArrayList<>(allPrefixes));
            result.setLoopContainerPath(getParentPath(getParentPath(deepestTriggerAttr.getAttrPath())));
        } else {
            // 至少一个字段无前缀 → 同级循环
            // ★修复：loopContainerPath = 触发字段上两级（ManufacturerTable），
            //   而非上一级（ManufacturerGroup）；循环的子结构才是 ManufacturerGroup。
            result.setLoopMode(LoopMode.SIBLING_LEVEL);
            result.setMaxRows(maxRows);
            result.setGroupKeys(IntStream.range(0, maxRows)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.toList()));
            result.setLoopContainerPath(getParentPath(getParentPath(deepestTriggerAttr.getAttrPath())));
        }

        return result;
    }

    // =====================================================
    // 构建普通树（无循环）
    // =====================================================

    /**
     * 构建无循环的普通XML树
     */
    private void buildNormalTree(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                 Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                 Map<String, Element> pathNodeMap, String rootAttrPath) {
        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            String[] parts = attrPath.split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                // 结构节点
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                parentElement.appendChild(structElement);
                pathNodeMap.put(attrPath, structElement);
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                // 叶子节点：直接取值（无分号说明无循环）
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
                addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
            }
        }
    }

    // =====================================================
    // 上级循环（PARENT_LEVEL）
    // =====================================================

    /**
     * 上级循环：每个前缀生成一套完整的 loopContainer 结构。
     * ★ 顺序修复：不再"先建循环容器、再补同级节点"，
     *   而是对 loopContainer 父级的所有直接子节点按 sort_order 顺序一次性遍历：
     *   - 遇到循环容器节点 → 展开为 N 个循环容器
     *   - 遇到其他节点     → 正常生成（结构节点/叶子节点）
     */
    private void buildParentLevelLoop(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                      Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                      Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                      LoopDetectionResult loopResult, String rootAttrPath) {

        String loopContainerPath = loopResult.getLoopContainerPath();

        // 1. 构建到循环容器父节点为止（仅处理祖先链，不含同级及循环容器本身）
        buildTreeUpToPath(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, structNodePaths, loopContainerPath, rootAttrPath);

        // 2. 获取循环容器的父元素
        String parentPath = getParentPath(loopContainerPath);
        Element parentElement = pathNodeMap.getOrDefault(parentPath, root);

        // 3. 按 sort_order 顺序遍历父节点的所有直接子节点，统一决定输出顺序
        int loopDepth = loopContainerPath.split("\\.").length;
        List<XmlTemplateAttribute> directSiblings = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    if (parentPath.isEmpty()) return p.split("\\.").length == loopDepth;
                    return p.startsWith(parentPath + ".") && p.split("\\.").length == loopDepth;
                })
                .collect(Collectors.toList()); // attrList 已全局按 templateOrderComparator 排好序

        List<String> groupKeys = loopResult.getGroupKeys();
        for (XmlTemplateAttribute sibling : directSiblings) {
            String[] parts = sibling.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if (sibling.getAttrPath().equals(loopContainerPath)) {
                // 当前节点是循环容器 → 展开为 N 个循环容器（按前缀顺序）
                for (int i = 0; i < groupKeys.size(); i++) {
                    generateParentLoopContainer(doc, parentElement, loopContainerPath,
                            attrList, dictCodeMap, jsonMap, structNodePaths, groupKeys.get(i), i, pathNodeMap);
                }
            } else if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                // 结构节点（如 AllowedParameterValuesMultistageGroup）
                if (!pathNodeMap.containsKey(sibling.getAttrPath())) {
                    Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                    parentElement.appendChild(structElement);
                    pathNodeMap.put(sibling.getAttrPath(), structElement);
                    buildSubTree(doc, structElement,
                            attrList.stream()
                                    .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                                    .collect(Collectors.toList()),
                            dictCodeMap, jsonMap,
                            buildSubPathNodeMap(pathNodeMap, sibling.getAttrPath(), structElement),
                            sibling.getAttrPath());
                }
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                // 叶子节点：含分号 → 循环字段跳过；无分号 → 正常生成（含空标签）
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
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

            if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                structElement.setUserData("loopGenerated", Boolean.TRUE, null);
                container.appendChild(structElement);
                buildContainerByPrefix(doc, structElement, child.getAttrPath(), attrList, dictCodeMap, jsonMap, prefix, prefixIndex);
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                String value = extractValueByPrefix(jsonMap, dict.getKeyMap(), prefix, prefixIndex);
                addElement(doc, container, sanitizeXmlTagName(dict.getDictLabel()), value);
            }
        }
    }

    // =====================================================
    // 同级循环（SIBLING_LEVEL）
    // =====================================================

    /**
     * 同级循环：在同一个容器内，子结构循环多次。
     * ★ 顺序修复：与 buildParentLevelLoop 相同策略——对 loopContainer 父级的所有直接子节点
     *   按 sort_order 顺序一次性遍历，遇到循环容器时展开内部循环，其余节点正常生成。
     */
    private void buildSiblingLevelLoop(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                       Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                       Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                       LoopDetectionResult loopResult, String rootAttrPath) {

        String loopContainerPath = loopResult.getLoopContainerPath();

        // 1. 构建到循环容器父节点为止（仅处理祖先链，不含同级及循环容器本身）
        buildTreeUpToPath(doc, root, attrList, dictCodeMap, jsonMap, pathNodeMap, structNodePaths, loopContainerPath, rootAttrPath);

        // 2. 获取循环容器的父元素
        String parentOfContainerPath = getParentPath(loopContainerPath);
        Element parentElement = pathNodeMap.getOrDefault(parentOfContainerPath, root);

        // 3. 按 sort_order 顺序遍历父节点的所有直接子节点，统一决定输出顺序
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
                // 容器内不参与循环的直接子叶子节点（如 CategoryHybridElectricVehicle 在容器内的情形）
                addNonLoopSiblingNodes(doc, container, loopContainerPath, loopStructPath, attrList, dictCodeMap, jsonMap);

            } else if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                // 结构节点（如 AllowedParameterValuesMultistageGroup）
                if (!pathNodeMap.containsKey(sibling.getAttrPath())) {
                    Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                    parentElement.appendChild(structElement);
                    pathNodeMap.put(sibling.getAttrPath(), structElement);
                    buildSubTree(doc, structElement,
                            attrList.stream()
                                    .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                                    .collect(Collectors.toList()),
                            dictCodeMap, jsonMap,
                            buildSubPathNodeMap(pathNodeMap, sibling.getAttrPath(), structElement),
                            sibling.getAttrPath());
                }
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                // 叶子节点：含分号 → 循环字段跳过；无分号 → 正常生成（含空标签）
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
                if (!value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    /**
     * 找到循环容器下、包含循环触发字段的直接子结构节点路径
     */
    private String findLoopStructPath(String containerPath, String triggerPath,
                                      Set<String> structNodePaths) {
        for (String structPath : structNodePaths) {
            if (!structPath.startsWith(containerPath + ".")) continue;
            // 必须是容器的直接子节点
            if (structPath.split("\\.").length != containerPath.split("\\.").length + 1) continue;
            // 触发字段必须在该结构节点之下
            if (triggerPath.startsWith(structPath + ".")) {
                return structPath;
            }
        }
        return null;
    }

    /**
     * 添加循环容器下不参与循环的同级节点（叶子节点，不在loopStructPath 下）
     */
    private void addNonLoopSiblingNodes(Document doc, Element container, String containerPath,String loopStructPath, List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap,
                                        Map<String, Object> jsonMap) {
        List<XmlTemplateAttribute> directLeafs = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    if (!p.startsWith(containerPath + ".")) return false;
                    // 必须是容器的直接子叶子节点
                    if (p.split("\\.").length != containerPath.split("\\.").length + 1) return false;
                    // 不在循环结构下
                    if (loopStructPath != null && p.startsWith(loopStructPath + ".")) return false;
                    String[] parts = p.split("\\.");
                    SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                    return d != null && StringUtils.isNotBlank(d.getKeyMap());
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute leaf : directLeafs) {
            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || StringUtils.isBlank(dict.getKeyMap())) continue;

            Object raw = jsonMap.get(dict.getKeyMap());
            String value = raw != null ? raw.toString() : "";
            // 若值含分号，说明该字段本身不是循环触发，取第一个值
            if (value.contains(";")) {
                value = value.split(";")[0].trim();
            }
            // ★修复：无论 value 是否为空，只要模板中定义了该标签，都必须生成（空标签保留）
            addElement(doc, container, sanitizeXmlTagName(dict.getDictLabel()), value);
        }
    }

    /**
     * 按索引填充子结构下的所有叶子字段（同级循环专用）
     */
    private void fillStructByIndex(Document doc, Element structElement, String structPath,
                                   List<XmlTemplateAttribute> attrList,
                                   Map<String, SysDictData> dictCodeMap,
                                   Map<String, Object> jsonMap,
                                   int index) {
        List<XmlTemplateAttribute> fields = attrList.stream()
                .filter(a -> a.getAttrPath().startsWith(structPath + "."))
                .filter(a -> {
                    String[] parts = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                    return d != null && StringUtils.isNotBlank(d.getKeyMap());
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute field : fields) {
            String[] parts = field.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || StringUtils.isBlank(dict.getKeyMap())) continue;

            String value = extractValueByIndex(jsonMap, dict.getKeyMap(), index);
            // ★修复：无论 value 是否为空，只要模板中定义了该标签，都必须生成（空标签保留）
            addElement(doc, structElement, sanitizeXmlTagName(dict.getDictLabel()), value);
        }
    }

    // =====================================================
    // 公共辅助：构建循环前的树
    // =====================================================

    /**
     * 构建循环容器祖先链上的节点（不含 targetPath 本身、其子孙节点、及其同级节点）。
     *
     * 职责划分：
     *  - 本方法：只处理深度 < targetPath 深度 的节点（即 targetPath 的祖先），
     *    以及其他与 targetPath 不在同一父级下的节点。
     *  - buildParentLevelLoop / buildSiblingLevelLoop：通过统一的"直接子节点按 sort_order 顺序遍历"
     *    处理所有同级节点（含循环容器本身），确保输出顺序与模板完全一致。
     */
    private void buildTreeUpToPath(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                   Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                   Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                   String targetPath, String rootAttrPath) {

        String targetParentPath = getParentPath(targetPath);
        int targetDepth = targetPath.split("\\.").length;

        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            // 跳过 targetPath（循环容器）本身及其所有子孙节点
            if (attrPath.equals(targetPath) || attrPath.startsWith(targetPath + ".")) continue;

            // ★核心修复：跳过与 targetPath 同级的所有节点（含 VehicleIdentificationNumber、
            //   ConsolidatedMaximum30MinutesPower、AllowedParameterValuesMultistageGroup、
            //   CategoryHybridElectricVehicle 等）——它们统一由 addSiblingNodesAfterLoop 负责，
            //   在此处处理会导致与 addSiblingNodesAfterLoop 重复生成。
            if (!targetParentPath.isEmpty()
                    && attrPath.startsWith(targetParentPath + ".")
                    && attrPath.split("\\.").length == targetDepth) {
                continue;
            }

            String[] parts = attrPath.split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                // 结构节点：创建并注册到 pathNodeMap，供子节点挂载
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                parentElement.appendChild(structElement);
                pathNodeMap.put(attrPath, structElement);
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
                // 含分号 → 循环字段，由循环逻辑处理，此处跳过
                if (!value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
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

        // 找到所有与 loopContainerPath 同级的节点
        // ★ 不再对 siblings 单独排序——attrList 已按模板 sort_order 全局排好序，stream 保持该顺序
        List<XmlTemplateAttribute> siblings = attrList.stream()
                .filter(a -> {
                    String path = a.getAttrPath();
                    if (path.equals(loopContainerPath)) return false; // 排除循环容器本身
                    if (!path.startsWith(parentPath + ".")) return false;
                    return path.split("\\.").length == loopDepth;
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute sibling : siblings) {
            String[] parts = sibling.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                // 结构节点（如 AllowedParameterValuesMultistageGroup）：
                // 防重：若 buildTreeUpToPath 已创建过（理论上不会，因为同级节点被跳过），则跳过
                if (pathNodeMap.containsKey(sibling.getAttrPath())) continue;
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                parentElement.appendChild(structElement);
                pathNodeMap.put(sibling.getAttrPath(), structElement);
                // 递归填充该结构节点的所有子孙节点
                buildSubTree(doc, structElement,
                        attrList.stream()
                                .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                                .collect(Collectors.toList()),
                        dictCodeMap, jsonMap,
                        buildSubPathNodeMap(pathNodeMap, sibling.getAttrPath(), structElement),
                        sibling.getAttrPath());
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                // 叶子节点：含分号属于循环字段跳过；无分号时无论是否为空都必须生成（空标签保留）
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
                if (!value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    /**
     * 为子树构建构建一个局部 pathNodeMap，根节点已预先注册
     */
    private Map<String, Element> buildSubPathNodeMap(Map<String, Element> existingMap,
                                                     String rootPath, Element rootElement) {
        Map<String, Element> subMap = new HashMap<>(existingMap);
        subMap.put(rootPath, rootElement);
        return subMap;
    }

    /**
     * 在指定父元素下构建子树（供 addSiblingNodesAfterLoop 递归使用）
     */
    private void buildSubTree(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                              Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                              Map<String, Element> pathNodeMap, String rootAttrPath) {
        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            String[] parts = attrPath.split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                parentElement.appendChild(structElement);
                pathNodeMap.put(attrPath, structElement);
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
                addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
            }
        }
    }

    // =====================================================
    // 值提取工具方法
    // =====================================================

    /**
     * 按前缀提取值（上级循环）
     * 例：keyMap对应值="HEV1:北京;HEV2:柏林"，prefix="HEV1" → 返回"北京"
     * 若值不含分号，直接返回原值
     * ★ fallbackIndex：当前缀在值中找不到匹配项时（如数据录入前缀有误），
     *   按该索引位置取值，保证每一列数据都不丢失。
     *   例："HEV1:CN;HEV1:DE"，prefix="HEV2"，fallbackIndex=1 → 返回"DE"
     */
    private String extractValueByPrefix(Map<String, Object> jsonMap, String keyMap,
                                        String prefix, int fallbackIndex) {
        Object raw = jsonMap.get(keyMap);
        if (raw == null) return "";

        String val = raw.toString().trim();
        if (!val.contains(";")) {
            // 无分号：检查是否为"前缀:值"单项
            if (val.contains(":")) {
                int colon = val.indexOf(':');
                String itemPrefix = val.substring(0, colon).trim();
                if (prefix.equals(itemPrefix)) {
                    return val.substring(colon + 1).trim();
                }
                // 前缀不匹配且只有一项，fallbackIndex=0 时回退取该值
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

        // ★ 第二步：前缀匹配失败，按 fallbackIndex 位置取值（容错重复/错误前缀）
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
     * 例：keyMap对应值="北京;柏林"，index=0 → 返回"北京"
     * 若值不含分号，直接返回原值（所有行共享同一值）
     * 若项含冒号（"HEV1:北京"），取冒号后的值
     */
    private String extractValueByIndex(Map<String, Object> jsonMap, String keyMap, int index) {
        Object raw = jsonMap.get(keyMap);
        if (raw == null) return "";

        String val = raw.toString().trim();
        if (!val.contains(";")) {
            // 无分号：所有行共享该值
            if (val.contains(":")) {
                int colon = val.indexOf(':');
                return val.substring(colon + 1).trim();
            }
            return val;
        }

        String[] items = val.split(";", -1);
        if (index< 0 || index >= items.length) return "";

        String item = items[index].trim();
        if (item.isEmpty()) return "";

        // 若含冒号，取冒号后的值
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
        if (templates == null || templates.isEmpty()) return null;

        for (XmlTemplate template : templates) {
            if (!Objects.equals(template.getModelDictCode(), vehicle.getVehicleModel())) continue;
            return template;
        }
        return null;
    }

    // =====================================================
    // XML工具方法
    // =====================================================

    /**
     * 添加XML子元素
     * ★修复：模板中配置的叶子标签无论值是否为空都必须创建，保留空标签（如 <IviReferenceId/>）
     */
    private void addElement(Document doc, Element parent, String tagName, String textContent) {
        Element element = doc.createElement(tagName);
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
     * 递归移除空结构节点（dict_value='NULL' 且无有效子节点的节点）
     * ★修复：只移除"动态循环生成但最终无内容"的结构节点（如前缀匹配失败的 ManufacturerGroup）；
     *   模板本身配置的静态结构节点（在 pathNodeMap 中注册过的）不应删除，它们是合法的空容器标签。
     *   判断依据：节点的 userData "loopGenerated" = true 才是循环动态生成的，才允许删除。
     * ★修复2：不能只判断直接子节点数 == 0——循环生成的子结构（如 ManufacturerGroup）在
     *   越界索引处会挂载空子标签（ManufacturerPlaceOfResidence/、ManufacturerCountryOfResidence/），
     *   导致 getChildNodes().getLength() > 0 但实际无任何有效文本内容。
     *   改为递归判断"所有后代是否均无非空文本"，只有全空才删除。
     */
    private void removeEmptyStructNodes(Element element, List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap) {
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element childElement = (Element) child;
                // 先递归处理子节点（深度优先，先清理孙子节点）
                removeEmptyStructNodes(childElement, attrList, dictCodeMap);
                // 只移除"循环动态生成且全部后代无实质内容"的结构节点
                Boolean loopGenerated = (Boolean) childElement.getUserData("loopGenerated");
                if (Boolean.TRUE.equals(loopGenerated) && !hasNonEmptyDescendantText(childElement)) {
                    element.removeChild(childElement);
                    log.debug("移除空循环结构节点:<{}>", childElement.getTagName());
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
    private String sanitizeXmlTagName(String raw) {
        if (StringUtils.isBlank(raw)) return "field";
        String cleaned = raw.trim().replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        if (cleaned.matches("^[0-9\\-].*")) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

}
