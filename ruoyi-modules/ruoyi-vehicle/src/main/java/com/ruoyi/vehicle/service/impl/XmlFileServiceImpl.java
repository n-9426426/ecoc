package com.ruoyi.vehicle.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.util.DateUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.enums.RuleItemType;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.model.FieldValidationResult;
import com.ruoyi.common.core.model.RuleViolation;
import com.ruoyi.common.core.model.ValidationReport;
import com.ruoyi.common.core.parser.ValueMappingParser;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
    public Long updateXmlFile(XmlFile xmlFile) {
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
        dbXmlFile.setValidateResult(0);
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

        log.info("dbXmlFile.getId={}", dbXmlFile.getId());
        // 12. 重新校验xml文件
        validateXml(dbXmlFile.getId());

        return dbXmlFile.getId();
    }

    /**
     * 批量删除XML文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult deleteXmlFileByIds(Long[] xmlIds) {
        List<XmlFile> xmlFileList = xmlFileMapper.selectXmlFileByIds(xmlIds);

        // 1. 根据xmlIds查询不到的xmlFile集合
        Set<Long> foundIds = xmlFileList.stream()
                .map(XmlFile::getId)
                .collect(Collectors.toSet());
        List<Long> notFoundIds = Arrays.stream(xmlIds)
                .filter(id -> !foundIds.contains(id))
                .collect(Collectors.toList());

        // 2. 取出vehicleInfoId，查询vehicleInfo
        List<Long> vehicleInfoIds = xmlFileList.stream()
                .map(XmlFile::getVehicleInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        List<VehicleInfo> vehicleInfoList = vehicleInfoIds.isEmpty()
                ? Collections.emptyList()
                : vehicleInfoMapper.selectVehicleInfoByIds(vehicleInfoIds.toArray(new Long[0]));

        Set<Long> foundVehicleInfoIds = vehicleInfoList.stream()
                .map(VehicleInfo::getVehicleId)
                .collect(Collectors.toSet());

        // 查询到vehicleInfo的xmlFile集合（不可删除）
        List<XmlFile> xmlFilesWithVehicle = xmlFileList.stream()
                .filter(xml -> xml.getVehicleInfoId() != null
                        && foundVehicleInfoIds.contains(xml.getVehicleInfoId()))
                .collect(Collectors.toList());

        // 查询不到vehicleInfo的xmlFile集合（可删除）
        List<XmlFile> xmlFilesWithoutVehicle = xmlFileList.stream()
                .filter(xml -> xml.getVehicleInfoId() == null
                        || !foundVehicleInfoIds.contains(xml.getVehicleInfoId()))
                .collect(Collectors.toList());

        // 拼装提示信息
        StringBuilder message = new StringBuilder();

        if (!notFoundIds.isEmpty()) {
            message.append("部分XML文件不存在，无法删除；");
        }

        if (!xmlFilesWithVehicle.isEmpty()) {
            message.append("以下XML文件已关联车辆信息，无法删除：")
                    .append(xmlFilesWithVehicle.stream()
                            .map(xml -> xml.getVin())
                            .collect(Collectors.joining("、")))
                    .append("；");
        }

        try {
            Map<String, Object> result = new HashMap<>();

            // 3. 仅删除查询不到vehicleInfo的xmlFile
            if (!xmlFilesWithoutVehicle.isEmpty()) {
                Long[] deletableIds = xmlFilesWithoutVehicle.stream()
                        .map(XmlFile::getId)
                        .toArray(Long[]::new);
                int deleteRows = xmlFileMapper.deleteXmlFileByIds(deletableIds);
                result.put("deleteRows", deleteRows);
                message.append("成功删除").append(deleteRows).append("条XML文件。");
            } else {
                result.put("deleteRows", 0);
                message.append("无可删除的XML文件。");
            }

            result.put("message", message.toString());
            return AjaxResult.success(result);
        } catch (Exception e) {
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
        int successCount = 0;
        for (Long xmlId : xmlIds) {
            XmlFile xmlFile = xmlFileMapper.selectXmlFileById(xmlId);
            if (xmlFile == null) {
                log.warn("上传审批：XML记录不存在，id={}", xmlId);
                continue;
            }
            VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoByVin(xmlFile.getVin());
            if (vehicleInfo == null) {
                throw new ServiceException("找不到关联车辆信息，VIN=" + xmlFile.getVin());
            }
            // 首台车（firstTemplateFlag=1）无需确认即可上传；非首台车需要 uploadAffirm=1
            boolean isFirstVehicle = Integer.valueOf(1).equals(vehicleInfo.getFirstTemplateFlag());
            if (!isFirstVehicle && !Integer.valueOf(1).equals(vehicleInfo.getUploadAffirm())) {
                throw new ServiceException("首台车尚未确认上传，VIN=" + xmlFile.getVin());
            }
            // TODO: 此处调用外部交通部接口；当前先直接标记为已上传
            xmlFile.setUploadResult("4");
            xmlFile.setUploadDate(new Date());
            xmlFile.setUpdateBy(SecurityUtils.getUsername());
            xmlFileMapper.updateXmlFile(xmlFile);

            vehicleInfoMapper.updateUploadStatusByVin(xmlFile.getVin(), 4);

            // 记录生命周期
            VehicleLifecycle lc = new VehicleLifecycle();
            lc.setEntryId(xmlFile.getId());
            lc.setTime(new Date());
            lc.setVin(xmlFile.getVin());
            lc.setOperate(VehicleLifecycleOperation.XML_UPLOAD.getOperation());
            lc.setResult(0);
            vehicleLifecycleMapper.insert(lc);

            successCount++;
        }
        return successCount;
    }

    /**
     * 强制上传：跳过校验失败拦截。
     * 首台车（firstTemplateFlag=1）无需确认即可强制上传；非首台车需要 uploadAffirm=1。
     * 成功后将该条 xml_file 记录标记为 force_uploaded=1，后续普通上传不再因校验失败被拦截。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceUploadXml(Long id) {
        XmlFile xmlFile = xmlFileMapper.selectXmlFileById(id);
        if (xmlFile == null) {
            throw new ServiceException("XML记录不存在，id=" + id);
        }
        VehicleInfo vehicleInfo = vehicleInfoMapper.selectVehicleInfoByVin(xmlFile.getVin());
        if (vehicleInfo == null) {
            throw new ServiceException("找不到关联车辆信息，VIN=" + xmlFile.getVin());
        }
        // 首台车（firstTemplateFlag=1）无需确认即可强制上传；非首台车需要 uploadAffirm=1
        boolean isFirstVehicle = Integer.valueOf(1).equals(vehicleInfo.getFirstTemplateFlag());
        if (!isFirstVehicle && !Integer.valueOf(1).equals(vehicleInfo.getUploadAffirm())) {
            throw new ServiceException("首台车尚未确认上传，VIN=" + xmlFile.getVin());
        }
        // TODO: 此处调用外部交通部接口；当前先直接标记为已上传
        xmlFile.setUploadResult("4");
        xmlFile.setUploadDate(new Date());
        xmlFile.setUpdateBy(SecurityUtils.getUsername());
        xmlFile.setForceUploaded(true);
        xmlFileMapper.updateXmlFile(xmlFile);

        // ★ 同步更新 vehicle_info 上传状态为「已上传」
        vehicleInfoMapper.updateUploadStatusByVin(xmlFile.getVin(), 4);

        // 记录生命周期
        VehicleLifecycle lc = new VehicleLifecycle();
        lc.setEntryId(xmlFile.getId());
        lc.setTime(new Date());
        lc.setVin(xmlFile.getVin());
        lc.setOperate(VehicleLifecycleOperation.XML_UPLOAD.getOperation());
        lc.setResult(0);
        vehicleLifecycleMapper.insert(lc);
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
    private void validateXmlStructure(Document doc, XmlFile xmlFile, List<FieldValidationResult> results) {
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
            // ★ 新增：同步构建"模板标签路径 → 数据类型"映射。dict_value 字段对属性行而言
            //   存储的就是数据类型名（String/Short/DateTime/Date/Decimal/Int/Structure/NULL），
            //   供下面的 DATA_FORMAT 校验直接使用，不需要额外的字典关联表。
            Map<String, String> attrPathToTagName = new LinkedHashMap<>(); // attrPath → tagName
            Map<String, String> attrPathToDataType = new LinkedHashMap<>(); // attrPath → 数据类型（dict_value）
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
                if (d != null && StringUtils.isNotBlank(d.getDictLabel())) {
                    attrPathToTagName.put(attr.getAttrPath(), sanitizeXmlTagName(d.getDictLabel()));
                    attrPathToDataType.put(attr.getAttrPath(), d.getDictValue());
                }
            }

            // 4. 识别循环容器路径集合（与生成逻辑保持一致）
            Map<String, Object> enrichedJsonMap = new HashMap<>(
                    vehicle.getJsonMap() != null ? vehicle.getJsonMap() : new HashMap<>());
            enrichHardcodedLoopFields(enrichedJsonMap, vehicle, attrList, dictCodeMap);
            Set<String> loopContainerPaths = resolveLoopContainerPaths(
                    attrList, dictCodeMap, enrichedJsonMap);

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
            // ★ 新增：tagPath → 数据类型（dict_value），结构遍历时顺带做 DATA_FORMAT 校验
            Map<String, String> tagPathToDataType = new HashMap<>();
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
                tagPathToDataType.put(tagPath, attrPathToDataType.get(attrPath));
                // 记录 is_required：同一 tagPath 只要有一个 required=1 则为必须
                boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
                tagPathIsRequired.merge(tagPath, required, (a, b) -> a || b);
            }

            // 6. 对XML DOM做DFS遍历，按层级路径逐节点与模板比对
            Element root = doc.getDocumentElement();
            checkElementStructure(root, "", tagPathIsLoop, tagPathIsRequired, tagPathToDataType, results);

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
                                       Map<String, String> tagPathToDataType,
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

        // ★ 新增：数据类型校验（DATA_FORMAT，与 STRUCTURE 同级错误）。
        //   dict_value = Structure / NULL（含空白）的节点不做类型校验（结构节点或未指定类型）；
        //   值为空、或为 "N/A"（与 addElement 生成阶段的占位约定保持一致）也不校验，
        //   是否必填由 is_required / STRUCTURE 的"缺少必须标签"检查单独负责，这里只管"有值时值符不符合类型"。
        String dataType = tagPathToDataType.get(currentPath);
        if (StringUtils.isNotBlank(dataType)
                && !"Structure".equalsIgnoreCase(dataType)
                && !"NULL".equalsIgnoreCase(dataType)) {
            String textValue = element.getTextContent();
            String trimmed = textValue == null ? "" : textValue.trim();
            if (StringUtils.isNotBlank(trimmed) && !"N/A".equalsIgnoreCase(trimmed)
                    && !isDataTypeValid(trimmed, dataType)) {
                results.add(buildStructureFieldResult("DATA_FORMAT",
                        String.format("Tag <%s> value \"%s\" does not match declared data type \"%s\" (path: \"%s\")",
                                tagName, trimmed, dataType, currentPath),
                        String.format("标签 <%s> 的值 \"%s\" 不符合声明的数据类型 \"%s\"（路径：%s）",
                                tagName, trimmed, dataType, currentPath)));
            }
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
            checkElementStructure(childEl, currentPath, tagPathIsLoop, tagPathIsRequired, tagPathToDataType, results);
        }
    }

    /**
     * 按"数据类型"字典（sys_dict_data.dict_value，对属性字段而言存储的就是数据类型名）
     * 校验叶子节点文本值是否符合声明类型。调用方已过滤掉 Structure/NULL/空值/N/A，
     * 这里只处理真正需要按类型解析的值。
     * <p>
     * 类型对应关系：
     *   String  → 任意文本，恒为 true
     *   Short   → {@link Short#parseShort}
     *   Int     → {@link Integer#parseInt}
     *   Decimal → {@link BigDecimal#BigDecimal(String)}
     *   DateTime→ 能被任意一种常见日期时间格式解析即算通过
     *   Date    → 纯日期，分隔符允许 "-" 或 "/"
     *   未识别的类型名 → 不阻断校验，按 true 处理（避免字典里出现新类型名时把所有该类型字段都判错）
     */
    private boolean isDataTypeValid(String value, String dataType) {
        switch (dataType.trim()) {
            case "String":
                return true;
            case "Short":
                try {
                    Short.parseShort(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "Int":
                try {
                    Integer.parseInt(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "Decimal":
                try {
                    new BigDecimal(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "DateTime":
                return tryParseDateTime(value);
            case "Date":
                return tryParseDate(value);
            default:
                return true;
        }
    }

    // 纯日期格式，分隔符允许 "-" 或 "/"
    private static final DateTimeFormatter[] DATE_ONLY_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

    // 不带时区/偏移量的日期时间格式（含日期、不含日期两类分隔符写法）
    private static final DateTimeFormatter[] LOCAL_DATETIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
    };

    private boolean tryParseDate(String value) {
        for (DateTimeFormatter f : DATE_ONLY_FORMATS) {
            try {
                LocalDate.parse(value, f);
                return true;
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return false;
    }

    private boolean tryParseDateTime(String value) {
        // 1. 标准 ISO 8601，含 Z / 时区偏移量（如 2026-06-19T16:34:53Z）
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Instant.parse(value);
            return true;
        } catch (Exception ignored) {
        }
        // 2. 不带时区的常见写法
        try {
            LocalDateTime.parse(value);
            return true;
        } catch (Exception ignored) {
        }
        for (DateTimeFormatter f : LOCAL_DATETIME_FORMATS) {
            try {
                LocalDateTime.parse(value, f);
                return true;
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        // 3. 兜底：纯日期也算"能转换为时间"
        return tryParseDate(value);
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

            // 整车是否为纯电动：XML 中所有 EnergySource 标签的值都是 "95"（且至少存在一个）
            boolean isFullyElectric = isFullyElectricByEnergySource(doc);

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

            // ★ 修复：补充识别"跨父节点重复"的结构循环节点。上面的检测只能识别"同一父节点下
            //   兄弟标签重复"（如 4 个 EnergyConvertorGroup 同为 EnergyConvertorTable 的子节点）。
            //   但像 EnergySourceGroup 这样的结构，每个 EnergyConvertorGroup 下只有 1 个实例
            //   （父节点各不相同），不会被上面的逻辑识别为循环节点，导致它在全文档范围内出现了
            //   4 次却被当作"非循环"处理：既不会被第三步收集为行列表，使 COUNT(@EnergySource
            //   IN ['95']) 等聚合规则永远统计不到任何值（恒为 0），结果误判。
            //   这里按"该 STRUCT 标签在全文档范围内出现次数 >= 2"补充识别，只针对结构节点
            //   （不影响叶子字段在第二步的取值逻辑），把这类跨父节点重复的结构节点同样纳入
            //   loopTagNames，交给第三步正确收集为行列表。
            Map<String, Integer> globalTagCount = new HashMap<>();
            for (int i = 0; i < allNodes.getLength(); i++) {
                globalTagCount.merge(((Element) allNodes.item(i)).getTagName(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : globalTagCount.entrySet()) {
                if (entry.getValue() < 2 || loopTagNames.contains(entry.getKey())) continue;
                SysDictData tagDict = labelToDictMap.get(entry.getKey());
                if (tagDict != null && isStructNode(tagDict)) {
                    loopTagNames.add(entry.getKey());
                }
            }

            // ── 第二步：构建非循环节点的 baseJsonMap ──────────────────────────────
            // ★ 修复：统一以 dictLabel（XML 标签名）作为 key 写入 baseJsonMap，每个字段独立，
            //   不再写入 keyMap 兜底，避免同一字段被规则引擎当作两个 key 校验两次（产生重复结果）。
            //   enrichAndMerge 通过同时支持 keyMap 和 dictLabel 两种 key 的查找来适配规则引擎返回值。
            //
            // ★ 新增修复：叶子字段若在全文档范围内重复出现（如 EnergySource 分别位于不同
            //   EnergyConvertorGroup 下各自的 EnergySourceGroup 中，每个父节点下只有 1 个实例，
            //   不会被第一步的"同父节点下兄弟标签重复"逻辑识别为循环节点；又因为它不是结构节点，
            //   第1307-1317行的补充修复也不会把它纳入 loopTagNames）。此前这类字段会在下方循环里
            //   被逐个 put 覆盖，最终只保留文档中最后一个实例的值，导致：
            //     1) COUNT(@EnergySource IN [...]) 等聚合规则统计到的实际可用值只有 1 个，而非真实的
            //        重复次数（即便 FinalRuleExecutor 已对标量管道字段做了拆分兜底，源头只剩 1 个值
            //        也无法还原真实计数）；
            //     2) 该字段自身的枚举/范围规则也只会按最后一个值校验一次，漏过其余实例。
            //   这里按"该叶子标签全局出现次数 >= 2 且未被识别为循环节点"，收集其全部取值，
            //   按 | 拼接后整体写入 baseJsonMap（与内部 JSON 存储的管道分隔约定保持一致），
            //   配合 FinalRuleExecutor 对标量管道字段的拆分计数逻辑，恢复正确的 COUNT 结果。
            Map<String, List<String>> multiLeafValues = new LinkedHashMap<>();
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element element = (Element) allNodes.item(i);
                String tagName  = element.getTagName();
                SysDictData dict = labelToDictMap.get(tagName);
                if (dict == null || isStructNode(dict) || StringUtils.isBlank(dict.getKeyMap())) continue;
                if (loopTagNames.contains(tagName)) continue;
                if (globalTagCount.getOrDefault(tagName, 0) < 2) continue;

                String value = StringUtils.defaultString(element.getTextContent());
                multiLeafValues.computeIfAbsent(sanitizeXmlTagName(dict.getDictLabel()), k -> new ArrayList<>())
                        .add(value);
            }
            if (!multiLeafValues.isEmpty()) {
                log.debug("★ 检测到全局重复但未被识别为循环节点的叶子字段，按 | 拼接: {}", multiLeafValues.keySet());
            }

            Map<String, Object> baseJsonMap = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : multiLeafValues.entrySet()) {
                baseJsonMap.put(entry.getKey(), String.join("|", entry.getValue()));
            }
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element element = (Element) allNodes.item(i);
                String tagName  = element.getTagName();
                SysDictData dict = labelToDictMap.get(tagName);
                if (dict == null || isStructNode(dict) || StringUtils.isBlank(dict.getKeyMap())) continue;

                String dictLabel = sanitizeXmlTagName(dict.getDictLabel());
                if (!loopTagNames.contains(tagName) && !multiLeafValues.containsKey(dictLabel)) {
                    String value = StringUtils.defaultString(element.getTextContent());
                    // 以 dictLabel 为唯一 key，保证每个字段独立，不互相覆盖
                    baseJsonMap.put(dictLabel, value);
                }
            }

            // ── ★ 第三步（新增）：将循环节点收集为列表，注入 baseJsonMap ──────
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
                        // ★ 修复：不再要求必须有 keyMap，所有叶子字段都收入 rowMap，
                        //   确保 SUM/COUNT 聚合规则能通过 dictLabel 找到字段值（如 TechnicallyPermissibleMassAxle）
                        if (childDict == null || isStructNode(childDict)) continue;
                        rowMap.put(sanitizeXmlTagName(childDict.getDictLabel()),
                                StringUtils.defaultString(childEl.getTextContent()));
                    }

                    // ★ 修复：无论 rowMap 是否为空都加入 rowList，
                    //   确保 COUNT(@AxleGroup) 统计到的行数等于实际结构节点实例数，
                    //   而不是"有叶子字段的实例数"
                    rowList.add(rowMap);
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
                        jsonStr, vehicleCategory, stageOfCompletion, isFullyElectric, false);
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
                            jsonStr, vehicleCategory, stageOfCompletion, isFullyElectric, false);

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
        if (vehicle.getIssueDate() == null) {
            vehicle.setIssueDate(new Date());
        }

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
            // 2. 匹配模板
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

            // 2a. 查询 energy_type 字典，把 xmlTemplate.getEnergyType()（dict_code）解析为 dict_value，
            //     据此判断该模板属于哪种能源类型分类：
            //       dict_value = hybrid（NOVC-HEV）或 HEV（OVC-HEV） → 混动，沿用现有逻辑生成一份完整XML
            //       dict_value = pure_electric（纯电）或 fuel_oil（燃油） → 现有生成逻辑复制两份，
            //         一份按纯电分支生成、一份按燃油分支生成，各自独立产出一份XML
            //     模板未设置 energyType，或字典查不到对应记录时，按混动分支处理（与改造前行为保持一致）
            String templateEnergyValue = resolveTemplateEnergyValue(xmlTemplate);
            boolean isNovcOrOvc = templateEnergyValue == null
                    || "hybrid".equals(templateEnergyValue)
                    || "HEV".equals(templateEnergyValue);
            boolean isElectricity = templateEnergyValue == null
                    || "pure_electric".equals(templateEnergyValue);

            List<String> xmlContents = new ArrayList<>();
            if (isNovcOrOvc) {
                // NOVC/OVC 混动：沿用现有逻辑，生成一份完整XML
                Document doc = buildXmlDocumentForNovcOvc(vehicle, xmlTemplate, sysNotice, msg, vehicleParams);
                xmlContents.add(saveGeneratedXmlDocument(doc, vehicle, xmlTemplate));
            } else if (isElectricity){
                // 纯电 / 燃油：现有生成逻辑复制两份，各自独立生成、独立保存
                // （两份逻辑目前内容相同，分开成独立方法是为了后续可以分别单独调整，不互相影响）
                Document docElectric = buildXmlDocumentForPureElectric(vehicle, xmlTemplate, sysNotice, msg, vehicleParams);
                xmlContents.add(saveGeneratedXmlDocument(docElectric, vehicle, xmlTemplate));
            } else {
                Document docFuel = buildXmlDocumentForFuelOil(vehicle, xmlTemplate, sysNotice, msg, vehicleParams);
                xmlContents.add(saveGeneratedXmlDocument(docFuel, vehicle, xmlTemplate));
            }

            return String.join(System.lineSeparator() + System.lineSeparator(), xmlContents);
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
     * 通过 energy_type 字典，把模板的 xmlTemplate.getEnergyType()（dict_code）解析为 dict_value。
     * dict_value 含义同 {@link #resolveEnergyType}：
     *   fuel_oil       = 燃油
     *   pure_electric  = 纯电
     *   hybrid         = NOVC-HEV 混动
     *   HEV            = OVC-HEV
     * 模板未设置 energyType，或字典查不到对应 dict_code 时，返回 null。
     */
    private String resolveTemplateEnergyValue(XmlTemplate xmlTemplate) {
        if (xmlTemplate.getEnergyType() == null) {
            return null;
        }
        List<SysDictData> energyDictList = remoteDictService.getDictDataByType("energy_type").getData();
        for (SysDictData d : energyDictList) {
            if (Objects.equals(d.getDictCode(), xmlTemplate.getEnergyType())) {
                return d.getDictValue();
            }
        }
        return null;
    }

    /**
     * 生成XML文档 —— NOVC/OVC 混动分支。
     * 即改造前 generateXmlFromDatabase 中"匹配模板"之后、"生成XML字符串"之前的原有逻辑（步骤3~13c），
     * 原样保留，未做任何调整。
     */
    private Document buildXmlDocumentForNovcOvc(VehicleInfo vehicle, XmlTemplate xmlTemplate,
                                                SysNotice sysNotice, StringBuilder msg,
                                                Map<String, String> vehicleParams) throws Exception {
        Map<String, Object> jsonMap = vehicle.getJsonMap();
        jsonMap.put("IviReferenceId", UUID.randomUUID().toString());
        // IviVersionDateTime 是 DateTime 类型，需要带时区的完整格式
        jsonMap.put("IviVersionDateTime", DateUtils.format(new Date(), "yyyy-MM-dd'T'HH:mm:ss'Z'"));
//            jsonMap.put("CommercialName", vehicle.getSaleCompanyName());

        // DateManufactureVehicle 和 SignatureDate 是 Date 类型，只需年月日
        if (vehicle.getManufactureDate() != null) {
            jsonMap.put("DateManufactureVehicle", DateUtils.format(vehicle.getManufactureDate(), "yyyy-MM-dd"));
        }
        if (vehicle.getIssueDate() != null) {
            jsonMap.put("SignatureDate", DateUtils.format(vehicle.getIssueDate(), "yyyy-MM-dd"));
        }
        if (StringUtils.isBlank((String) (jsonMap.get("SignatureDate")))) {
            jsonMap.put("SignatureDate", DateUtils.format(new Date(), "yyyy-MM-dd"));
        }

        // Colour 值通过 sys_dict_data 的 value_connection 映射（与 IntendedCountryRegistration 同一套逻辑）
        Object rawColourNovcOvc = vehicle.getColor();
        if (rawColourNovcOvc != null && StringUtils.isNotBlank(rawColourNovcOvc.toString())) {
            jsonMap.put("Colour", resolveColourDictValue(rawColourNovcOvc.toString()));
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

        String methodAttachmentStatutoryPlate = null;
        Object methodAttachmentStatutoryPlateObj = jsonMap.get("MethodAttachmentStatutoryPlate");
        if (methodAttachmentStatutoryPlateObj != null
                && StringUtils.isNotBlank(methodAttachmentStatutoryPlateObj.toString())) {
            methodAttachmentStatutoryPlate = methodAttachmentStatutoryPlateObj.toString();
        }
        if (StringUtils.isBlank(methodAttachmentStatutoryPlate)) {
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null) continue;
                if ("MethodAttachmentStatutoryPlate".equals(sanitizeXmlTagName(dict.getDictLabel()))
                        && StringUtils.isNotBlank(attr.getDefaultValue())) {
                    methodAttachmentStatutoryPlate = attr.getDefaultValue();
                    break;
                }
            }
        }
        if (StringUtils.isNotBlank(methodAttachmentStatutoryPlate)) {
            applyLocationMarkings(jsonMap, methodAttachmentStatutoryPlate);
        }

        // 当 EnergySource 为单段，且燃油/电机四个功率字段均有值（单段混动场景）时，
        // 在 EnergySource 后拼接 "|95" 使其变为两段，复用下面的多段逻辑生成两个 PowerGroup
        // 返回值标记本次是否真正发生了拼接，用于后面精简合成的 95 段 EnergySourceGroup
        boolean hybridSingleSegmentEnergySourceExpanded = appendEnergySourceSegmentIfHybrid(jsonMap);

        // 当 EnergySource 为多段（含 |）时，在 Maximum30MinutesPower、MaximumNetPowerElectric
        // 的值开头各拼接一个 "0|"，使段数与 EnergySource 对齐（第0段对应燃油组，值为0/空均可跳过）
        prependZeroForElectricPowerFields(jsonMap);

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

        // 13a0. 兜底补建：模板中 is_required=1 但生成后整个标签都缺失的，按对应层级位置补建空标签。
        //   须放在 restrictHybridElectricEnergySourceGroup / removeEngineCapacityForElectricEnergySource
        //   等业务规则性删除 **之前**，避免补建的标签被这些规则正常移除后又被误判为遗漏。
        ensureRequiredTagsExist(doc, root, rootAttrPath, attrList, dictCodeMap);

        // 13a. 单段混动场景（appendEnergySourceSegmentIfHybrid 实际拼接出了合成的 95 段）
        //   或多段场景下除首段外全部为 95 且不止 1 个（如 "10|95|95|95"，多个电机共用
        //   同一能量类型代码 95，各自只有 1 个 PowerGroup）时，对应的 95 段 EnergySourceGroup
        //   只保留 EnergySource、PowerGroup 两个标签，去掉从原始数据中共享复制过来的
        //   其它无意义标签（WorkingPrinciple、TestFamilyIdentifiersTable 等）
        boolean shouldRestrictElectricEnergySourceGroup = hybridSingleSegmentEnergySourceExpanded
                || hasRepeatedElectricSegmentsAfterFirst(jsonMap);
        restrictHybridElectricEnergySourceGroup(root, shouldRestrictElectricEnergySourceGroup);

        // 13a2. TestWltpElectricRangeGroup（纯电续航）、TestWltpEnergyConsumptionGroup（能耗）
        //   描述的是车辆的纯电相关数据，按校验规则应挂在每一个 EnergySource=95（电机）的
        //   EnergySourceGroup 下；但源 JSON 中这两组字段未按 EnergySource 分段，树构建时
        //   默认落在第一个 EnergySourceGroup（往往是燃油段）下，需复制到所有 95 段下
        relocateElectricOnlyTestGroupsToElectricEnergySource(root);

        // 13b. 当 EnergySource 为多段时，在 CocDataGroup 下追加汇总标签：
        //   ConsolidatedMaximum30MinutesPower  = Maximum30MinutesPower  各段之和
        //   ConsolidatedMaximumNetPowerElectric = MaximumNetPowerElectric 各段之和
        appendConsolidatedPowerFields(doc, root, jsonMap);

        // 13c. EnergySource 值为 90/91/95（氢能源/其他/电机等非内燃机类型）时，
        //   删除该 EnergySource 父节点的父节点的同级容器（EnergyConvertorGroup）下的
        //   EngineCapacity、NumberOfCylinders、ArrangementCylinders 三个标签
        removeEngineCapacityForElectricEnergySource(doc);

        // 13d. Header 下若不存在 IntendedCountryRegistration 标签，
        //   在 IviVersionDateTime 标签之后补充插入，值取 country 字段的字典映射
        ensureIntendedCountryRegistration(doc, vehicle);

        return doc;
    }

    /**
     * 生成XML文档 —— 纯电分支。
     * 当模板 energyType 解析为"纯电"或"燃油"（非 NOVC/OVC 混动）时，与
     * {@link #buildXmlDocumentForFuelOil} 一起被各调用一次，分别产出一份独立XML。
     * 当前内容与 {@link #buildXmlDocumentForNovcOvc} 完全一致，单独拆成方法是为了
     * 后续可以只针对"纯电"场景单独调整逻辑，不影响另外两条分支。
     */
    private Document buildXmlDocumentForPureElectric(VehicleInfo vehicle, XmlTemplate xmlTemplate,
                                                     SysNotice sysNotice, StringBuilder msg,
                                                     Map<String, String> vehicleParams) throws Exception {
        Map<String, Object> jsonMap = vehicle.getJsonMap();
        jsonMap.put("IviReferenceId", UUID.randomUUID().toString());
        // IviVersionDateTime 是 DateTime 类型，需要带时区的完整格式
        jsonMap.put("IviVersionDateTime", DateUtils.format(new Date(), "yyyy-MM-dd'T'HH:mm:ss'Z'"));
//            jsonMap.put("CommercialName", vehicle.getSaleCompanyName());

        // DateManufactureVehicle 和 SignatureDate 是 Date 类型，只需年月日
        if (vehicle.getManufactureDate() != null) {
            jsonMap.put("DateManufactureVehicle", DateUtils.format(vehicle.getManufactureDate(), "yyyy-MM-dd"));
        }
        if (vehicle.getIssueDate() != null) {
            jsonMap.put("SignatureDate", DateUtils.format(vehicle.getIssueDate(), "yyyy-MM-dd"));
        }
        if (StringUtils.isBlank((String) (jsonMap.get("SignatureDate")))) {
            jsonMap.put("SignatureDate", DateUtils.format(new Date(), "yyyy-MM-dd"));
        }

        // Colour 值通过 sys_dict_data 的 value_connection 映射（与 IntendedCountryRegistration 同一套逻辑）
        Object rawColourPureElectric = vehicle.getColor();
        if (rawColourPureElectric != null && StringUtils.isNotBlank(rawColourPureElectric.toString())) {
            jsonMap.put("Colour", resolveColourDictValue(rawColourPureElectric.toString()));
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

        String methodAttachmentStatutoryPlate = null;
        Object methodAttachmentStatutoryPlateObj = jsonMap.get("MethodAttachmentStatutoryPlate");
        if (methodAttachmentStatutoryPlateObj != null
                && StringUtils.isNotBlank(methodAttachmentStatutoryPlateObj.toString())) {
            methodAttachmentStatutoryPlate = methodAttachmentStatutoryPlateObj.toString();
        }
        if (StringUtils.isBlank(methodAttachmentStatutoryPlate)) {
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null) continue;
                if ("MethodAttachmentStatutoryPlate".equals(sanitizeXmlTagName(dict.getDictLabel()))
                        && StringUtils.isNotBlank(attr.getDefaultValue())) {
                    methodAttachmentStatutoryPlate = attr.getDefaultValue();
                    break;
                }
            }
        }
        if (StringUtils.isNotBlank(methodAttachmentStatutoryPlate)) {
            applyLocationMarkings(jsonMap, methodAttachmentStatutoryPlate);
        }

        // 当 EnergySource 为单段，且燃油/电机四个功率字段均有值（单段混动场景）时，
        // 在 EnergySource 后拼接 "|95" 使其变为两段，复用下面的多段逻辑生成两个 PowerGroup
        // 返回值标记本次是否真正发生了拼接，用于后面精简合成的 95 段 EnergySourceGroup
        boolean hybridSingleSegmentEnergySourceExpanded = appendEnergySourceSegmentIfHybrid(jsonMap);

        // 当 EnergySource 为多段（含 |）时，在 Maximum30MinutesPower、MaximumNetPowerElectric
        // 的值开头各拼接一个 "0|"，使段数与 EnergySource 对齐（第0段对应燃油组，值为0/空均可跳过）
        prependZeroForElectricPowerFields(jsonMap);

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

        // 13a0. 兜底补建：模板中 is_required=1 但生成后整个标签都缺失的，按对应层级位置补建空标签。
        //   须放在 restrictHybridElectricEnergySourceGroup / removeEngineCapacityForElectricEnergySource
        //   等业务规则性删除 **之前**，避免补建的标签被这些规则正常移除后又被误判为遗漏。
        ensureRequiredTagsExist(doc, root, rootAttrPath, attrList, dictCodeMap);

        // 13a. 单段混动场景（appendEnergySourceSegmentIfHybrid 实际拼接出了合成的 95 段）
        //   或多段场景下除首段外全部为 95 且不止 1 个（如 "10|95|95|95"，多个电机共用
        //   同一能量类型代码 95，各自只有 1 个 PowerGroup）时，对应的 95 段 EnergySourceGroup
        //   只保留 EnergySource、PowerGroup 两个标签，去掉从原始数据中共享复制过来的
        //   其它无意义标签（WorkingPrinciple、TestFamilyIdentifiersTable 等）
        boolean shouldRestrictElectricEnergySourceGroup = hybridSingleSegmentEnergySourceExpanded
                || hasRepeatedElectricSegmentsAfterFirst(jsonMap);
        restrictHybridElectricEnergySourceGroup(root, shouldRestrictElectricEnergySourceGroup);

        // 13a2. TestWltpElectricRangeGroup（纯电续航）、TestWltpEnergyConsumptionGroup（能耗）
        //   描述的是车辆的纯电相关数据，按校验规则应挂在每一个 EnergySource=95（电机）的
        //   EnergySourceGroup 下；但源 JSON 中这两组字段未按 EnergySource 分段，树构建时
        //   默认落在第一个 EnergySourceGroup（往往是燃油段）下，需复制到所有 95 段下
        relocateElectricOnlyTestGroupsToElectricEnergySource(root);

        // 13b. 当 EnergySource 为多段时，在 CocDataGroup 下追加汇总标签：
        //   ConsolidatedMaximum30MinutesPower  = Maximum30MinutesPower  各段之和
        //   ConsolidatedMaximumNetPowerElectric = MaximumNetPowerElectric 各段之和
        appendConsolidatedPowerFields(doc, root, jsonMap);

        // 13c. EnergySource 值为 90/91/95（氢能源/其他/电机等非内燃机类型）时，
        //   删除该 EnergySource 父节点的父节点的同级容器（EnergyConvertorGroup）下的
        //   EngineCapacity、NumberOfCylinders、ArrangementCylinders 三个标签
        removeEngineCapacityForElectricEnergySource(doc);

        // 13d. Header 下若不存在 IntendedCountryRegistration 标签，
        //   在 IviVersionDateTime 标签之后补充插入，值取 country 字段的字典映射
        ensureIntendedCountryRegistration(doc, vehicle);

        return doc;
    }

    /**
     * 生成XML文档 —— 燃油分支。
     * 当模板 energyType 解析为"纯电"或"燃油"（非 NOVC/OVC 混动）时，与
     * {@link #buildXmlDocumentForPureElectric} 一起被各调用一次，分别产出一份独立XML。
     * 当前内容与 {@link #buildXmlDocumentForNovcOvc} 完全一致，单独拆成方法是为了
     * 后续可以只针对"燃油"场景单独调整逻辑，不影响另外两条分支。
     */
    private Document buildXmlDocumentForFuelOil(VehicleInfo vehicle, XmlTemplate xmlTemplate,
                                                SysNotice sysNotice, StringBuilder msg,
                                                Map<String, String> vehicleParams) throws Exception {
        Map<String, Object> jsonMap = vehicle.getJsonMap();
        jsonMap.put("IviReferenceId", UUID.randomUUID().toString());
        // IviVersionDateTime 是 DateTime 类型，需要带时区的完整格式
        jsonMap.put("IviVersionDateTime", DateUtils.format(new Date(), "yyyy-MM-dd'T'HH:mm:ss'Z'"));
//            jsonMap.put("CommercialName", vehicle.getSaleCompanyName());

        // DateManufactureVehicle 和 SignatureDate 是 Date 类型，只需年月日
        if (vehicle.getManufactureDate() != null) {
            jsonMap.put("DateManufactureVehicle", DateUtils.format(vehicle.getManufactureDate(), "yyyy-MM-dd"));
        }
        if (vehicle.getIssueDate() != null) {
            jsonMap.put("SignatureDate", DateUtils.format(vehicle.getIssueDate(), "yyyy-MM-dd"));
        }
        if (StringUtils.isBlank((String) (jsonMap.get("SignatureDate")))) {
            jsonMap.put("SignatureDate", DateUtils.format(new Date(), "yyyy-MM-dd"));
        }

        // Colour 值通过 sys_dict_data 的 value_connection 映射（与 IntendedCountryRegistration 同一套逻辑）
        Object rawColourFuelOil = vehicle.getColor();
        if (rawColourFuelOil != null && StringUtils.isNotBlank(rawColourFuelOil.toString())) {
            jsonMap.put("Colour", resolveColourDictValue(rawColourFuelOil.toString()));
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

        String methodAttachmentStatutoryPlate = null;
        Object methodAttachmentStatutoryPlateObj = jsonMap.get("MethodAttachmentStatutoryPlate");
        if (methodAttachmentStatutoryPlateObj != null
                && StringUtils.isNotBlank(methodAttachmentStatutoryPlateObj.toString())) {
            methodAttachmentStatutoryPlate = methodAttachmentStatutoryPlateObj.toString();
        }
        if (StringUtils.isBlank(methodAttachmentStatutoryPlate)) {
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null) continue;
                if ("MethodAttachmentStatutoryPlate".equals(sanitizeXmlTagName(dict.getDictLabel()))
                        && StringUtils.isNotBlank(attr.getDefaultValue())) {
                    methodAttachmentStatutoryPlate = attr.getDefaultValue();
                    break;
                }
            }
        }
        if (StringUtils.isNotBlank(methodAttachmentStatutoryPlate)) {
            applyLocationMarkings(jsonMap, methodAttachmentStatutoryPlate);
        }

        // 当 EnergySource 为单段，且燃油/电机四个功率字段均有值（单段混动场景）时，
        // 在 EnergySource 后拼接 "|95" 使其变为两段，复用下面的多段逻辑生成两个 PowerGroup
        // 返回值标记本次是否真正发生了拼接，用于后面精简合成的 95 段 EnergySourceGroup
        boolean hybridSingleSegmentEnergySourceExpanded = appendEnergySourceSegmentIfHybrid(jsonMap);

        // 当 EnergySource 为多段（含 |）时，在 Maximum30MinutesPower、MaximumNetPowerElectric
        // 的值开头各拼接一个 "0|"，使段数与 EnergySource 对齐（第0段对应燃油组，值为0/空均可跳过）
        prependZeroForElectricPowerFields(jsonMap);

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

        // 13a0. 兜底补建：模板中 is_required=1 但生成后整个标签都缺失的，按对应层级位置补建空标签。
        //   须放在 restrictHybridElectricEnergySourceGroup / removeEngineCapacityForElectricEnergySource
        //   等业务规则性删除 **之前**，避免补建的标签被这些规则正常移除后又被误判为遗漏。
        ensureRequiredTagsExist(doc, root, rootAttrPath, attrList, dictCodeMap);

        // 13a. 单段混动场景（appendEnergySourceSegmentIfHybrid 实际拼接出了合成的 95 段）
        //   或多段场景下除首段外全部为 95 且不止 1 个（如 "10|95|95|95"，多个电机共用
        //   同一能量类型代码 95，各自只有 1 个 PowerGroup）时，对应的 95 段 EnergySourceGroup
        //   只保留 EnergySource、PowerGroup 两个标签，去掉从原始数据中共享复制过来的
        //   其它无意义标签（WorkingPrinciple、TestFamilyIdentifiersTable 等）
        boolean shouldRestrictElectricEnergySourceGroup = hybridSingleSegmentEnergySourceExpanded
                || hasRepeatedElectricSegmentsAfterFirst(jsonMap);
        restrictHybridElectricEnergySourceGroup(root, shouldRestrictElectricEnergySourceGroup);

        // 13a2. TestWltpElectricRangeGroup（纯电续航）、TestWltpEnergyConsumptionGroup（能耗）
        //   描述的是车辆的纯电相关数据，按校验规则应挂在每一个 EnergySource=95（电机）的
        //   EnergySourceGroup 下；但源 JSON 中这两组字段未按 EnergySource 分段，树构建时
        //   默认落在第一个 EnergySourceGroup（往往是燃油段）下，需复制到所有 95 段下
        relocateElectricOnlyTestGroupsToElectricEnergySource(root);

        // 13b. 当 EnergySource 为多段时，在 CocDataGroup 下追加汇总标签：
        //   ConsolidatedMaximum30MinutesPower  = Maximum30MinutesPower  各段之和
        //   ConsolidatedMaximumNetPowerElectric = MaximumNetPowerElectric 各段之和
        appendConsolidatedPowerFields(doc, root, jsonMap);

        // 13c. EnergySource 值为 90/91/95（氢能源/其他/电机等非内燃机类型）时，
        //   删除该 EnergySource 父节点的父节点的同级容器（EnergyConvertorGroup）下的
        //   EngineCapacity、NumberOfCylinders、ArrangementCylinders 三个标签
        removeEngineCapacityForElectricEnergySource(doc);

        // 13d. Header 下若不存在 IntendedCountryRegistration 标签，
        //   在 IviVersionDateTime 标签之后补充插入，值取 country 字段的字典映射
        ensureIntendedCountryRegistration(doc, vehicle);

        // 13e. 燃油类型专属：每个 GearRatioGroup 的 FinalDriveTable 下，
        //   只保留 FinalDriveNumber 等于该 GearRatioGroup 的 GearNumber 的那一组 FinalDriveGroup，
        //   其余 FinalDriveGroup 全部删除
        restrictFinalDriveGroupByGearNumber(doc);

        return doc;
    }

    private String saveGeneratedXmlDocument(Document doc, VehicleInfo vehicle, XmlTemplate xmlTemplate) throws Exception {
        VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
        SysNotice sysNotice = new SysNotice();
        sysNotice.setIsRead(false);
        sysNotice.setNoticeType("1");
        sysNotice.setNoticeTitle("XML文件生成通知");
        sysNotice.setCreateBy("自动提醒");
        sysNotice.setCreateTime(new Date());

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
        updateObj.setIssueDate(vehicle.getIssueDate());
        updateObj.setUploadStatus(2);

        // 20. 记录生命周期
        vehicleLifecycle.setEntryId(vehicle.getVehicleId());
        vehicleLifecycle.setTime(new Date());
        vehicleLifecycle.setVin(vehicle.getVin());
        vehicleLifecycle.setOperate(VehicleLifecycleOperation.VEHICLE_BUILD_XML.getOperation());
        vehicleLifecycle.setResult(0);
        vehicleInfoService.updateVehicleInfo(updateObj, false);
        vehicleLifecycleMapper.insert(vehicleLifecycle);

        StringBuilder msg = new StringBuilder();
        msg.append(System.lineSeparator());
        msg.append("车辆vin ");
        msg.append(vehicle.getVin());
        msg.append("生成XML文件的结果为: ");
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
                    // ★ 修复：同时检查 DOM 层，若父节点下已存在同名子元素（由 loopGenerated 生成），
                    //   说明该结构节点已由 buildSiblingLevelLoop 处理过，直接跳过，不重复创建。
                    String childTag = sanitizeXmlTagName(dict.getDictLabel());
                    if (hasChildElement(currentElement, childTag)) {
                        // 已有 DOM 节点但不在 pathNodeMap：写入占位防止 BFS 再次进入，然后跳过
                        // 不进 queue，避免对其子孙做多余的补充构建
                        continue;
                    }

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
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        Object raw = jsonMap.get(d.getDictLabel());
                        if (raw == null || !raw.toString().contains(";") || raw.toString().contains("|")) return false;
                        // ★ 含 ; 的叶子深度必须在 childDepth+2 以内（Table→Group→Field），
                        //   否则说明循环数据在深层嵌套结构，不在此展开
                        return p.length <= childDepth + 2;
                    });

                    // ★ 若子树中有更深层的循环数据（含 ; 但深度>childDepth+2，如 GearRatioTable→GearRatioGroup→GearNumber），
                    //   用 detectLoopPattern 重新检测，走完整的 buildSiblingLevelLoop 逻辑展开
                    boolean hasDeepLoop = !hasPipe && !hasSemi && subAttrs.stream().anyMatch(a -> {
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        Object raw = jsonMap.get(d.getDictLabel());
                        return raw != null && raw.toString().contains(";") && !raw.toString().contains("|");
                    });

                    Element structEl = createElementWithDefault(doc,
                            sanitizeXmlTagName(dict.getDictLabel()), child.getDefaultValue());

                    log.info("=== buildUnprocessedNodes CREATE tag={} childPath末段={} parentTag={}",
                            dict.getDictLabel(),
                            childPath.substring(childPath.lastIndexOf('.')+1),
                            currentElement.getTagName());

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
                    } else if (hasDeepLoop) {
                        // ★ 深层循环（如 GearRatioTable→GearRatioGroup×7）：
                        //   重新检测循环模式，用完整的 buildSiblingLevelLoop 逻辑展开
                        List<XmlTemplateAttribute> subLeafNodes = subAttrs.stream()
                                .filter(a -> {
                                    String[] p = a.getAttrPath().split("\\.");
                                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                                    return d != null && !isStructNode(d);
                                })
                                .collect(Collectors.toList());
                        LoopDetectionResult subLoopResult = detectLoopPattern(subLeafNodes, dictCodeMap, jsonMap);
                        if (subLoopResult.getLoopMode() != LoopMode.NONE) {
                            // 在本方法内计算子树的 structNodePaths
                            Set<String> subStructNodePaths = subAttrs.stream()
                                    .filter(a -> {
                                        String[] p = a.getAttrPath().split("\\.");
                                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                                        return d != null && isStructNode(d);
                                    })
                                    .map(XmlTemplateAttribute::getAttrPath)
                                    .collect(Collectors.toSet());
                            Map<String, Element> subPathNodeMap = buildSubPathNodeMap(pathNodeMap, childPath, structEl);
                            buildSiblingLevelLoop(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                                    subPathNodeMap, subStructNodePaths, subLoopResult, childPath);
                            pathNodeMap.putAll(subPathNodeMap);
                        } else {
                            buildSubTree(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                                    buildSubPathNodeMap(pathNodeMap, childPath, structEl),
                                    childPath, -1);
                        }
                    } else {
                        buildSubTree(doc, structEl, subAttrs, dictCodeMap, jsonMap,
                                buildSubPathNodeMap(pathNodeMap, childPath, structEl),
                                childPath, -1);
                    }

                } else {
                    // 已在 pathNodeMap，检查对应 DOM 节点是否由循环生成
                    // 若是 loopGenerated 节点，其子孙已由 fillStructByIndex 处理，不再 BFS
                    Element existingEl = pathNodeMap.get(childPath);
                    log.info("=== buildUnprocessedNodes ALREADY_IN_MAP tag={} childPath末段={} loopGenerated={}",
                            dict.getDictLabel(),
                            childPath.substring(childPath.lastIndexOf('.')+1),
                            Boolean.TRUE.equals(existingEl != null ? existingEl.getUserData("loopGenerated") : null));
                    if (existingEl != null
                            && Boolean.TRUE.equals(existingEl.getUserData("loopGenerated"))) {
                        continue;
                    }
                    // 普通节点继续往下 BFS，让深层未处理节点被发现
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

        // ★ 第一遍：只找最浅触发字段（loopStructPath 由它决定），不在此计算 maxRows
        for (XmlTemplateAttribute leaf : leafNodes) {
            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            if (d == null || StringUtils.isBlank(d.getDictLabel())) continue;

            Object raw = jsonMap.get(d.getDictLabel());
            if (raw == null) continue;

            String val = raw.toString().trim();
            if (!val.contains(";")) continue;

            // 选路径最浅的触发字段，避免深层字段（如 TyreAxleGroup 下的字段）
            // 抢占 loopContainerPath，破坏整体结构
            if (deepestTriggerAttr == null ||
                    leaf.getAttrPath().split("\\.").length <
                            deepestTriggerAttr.getAttrPath().split("\\.").length) {
                deepestTriggerAttr = leaf;
            }
        }

        if (deepestTriggerAttr == null) {
            result.setLoopMode(LoopMode.NONE);
            return result;
        }

        // ★ 计算 loopStructPath（触发字段所在 Group 的路径 = 触发字段路径的上一级）
        String triggerGroupPath = getParentPath(deepestTriggerAttr.getAttrPath());
        // ★ 第二遍：只统计与触发字段同属一个 Group（相同 triggerGroupPath 前缀且深度相同）的叶子字段的 ; 段数
        //   排除更深层字段（如 TyreAxleGroup 下的字段）污染 maxRows
        int triggerDepth = deepestTriggerAttr.getAttrPath().split("\\.").length;
        for (XmlTemplateAttribute leaf : leafNodes) {
            // 只看与触发字段同层（相同深度）且同属一个 Group 的字段
            if (leaf.getAttrPath().split("\\.").length != triggerDepth) continue;
            if (!leaf.getAttrPath().startsWith(triggerGroupPath + ".")) continue;

            String[] parts = leaf.getAttrPath().split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            if (d == null || StringUtils.isBlank(d.getDictLabel())) continue;

            Object raw = jsonMap.get(d.getDictLabel());
            if (raw == null) continue;

            String val = raw.toString().trim();
            if (!val.contains(";")) continue;

            String[] items = val.split(";", -1);
            // ★ 使用 countNonTrailingEmpty 避免末尾空段被计入
            int rowCount = countNonTrailingEmpty(items);
            maxRows = Math.max(maxRows, rowCount);

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
        // ★ 优先扫描 rootAttrPath 直接子 Group 层（depth+2，即 Table→Group→Field）的叶子字段，
        //   避免更深层（如 TyreAxleGroup 下）的 | 字段段数污染外层 AxleGroup 的展开次数。
        int rootDepth = rootAttrPath.split("\\.").length;
        boolean foundPipeAtDirectLevel = false;
        for (XmlTemplateAttribute attr : attrList) {
            if (attr.getAttrPath().equals(rootAttrPath)) continue;
            // 仅处理深度恰好为 rootDepth+2 的叶子（Group 直属字段），跳过更深层节点
            if (attr.getAttrPath().split("\\.").length != rootDepth + 2) continue;
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString();
            if (val.contains("|")) {
                // ★ 修复：不能用 countNonTrailingEmpty 截断尾部空段，否则当某字段恰好只在
                //   最后一组才有值（如 TechnicallyPermissibleMaximumCombinationMass="||4142"），
                //   或恰好最后几组该字段都为空（如 BrakedTypeTrail="BRK||"）时，
                //   会把行数错误地砍成比实际组数少，导致后面的组整体丢失。
                //   外层行数应取「所有候选字段的最大段数」，按 split 长度直接计数，不做尾部截断。
                int rows = val.split("\\|", -1).length;
                maxRows = Math.max(maxRows, rows);
                foundPipeAtDirectLevel = true;
            }
        }

        // ★ 修复：若直接子 Group 层（rootDepth+2）没有找到含 | 的叶子字段
        //   （如 EnergyConvertorGroup 下直接子是 EnergySourceTable 结构节点，而非直属叶子），
        //   则向下递归扫描所有更深层的叶子字段，取含 | 字段的最大段数，
        //   正确处理 Table→Group→Table→Group→Field 这种多层嵌套下的外层循环行数计算。
        //   注意：此递归扫描仅确定外层 Group（rootDepth+1）的循环次数，
        //   内层嵌套循环展开由 expandNestedPipeLoop 负责，不受此处影响。
        if (!foundPipeAtDirectLevel) {
            for (XmlTemplateAttribute attr : attrList) {
                if (attr.getAttrPath().equals(rootAttrPath)) continue;
                if (attr.getAttrPath().split("\\.").length <= rootDepth + 2) continue;
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
        // ★ 新增：记录已通过 expandPipeLoop（按 | 展开）处理过的结构节点路径，
        //   主循环遇到其子孙时跳过，避免被当作普通节点再创建一遍导致重复
        Set<String> expandedStructPaths = new java.util.HashSet<>();

        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            // ★ 新增：跳过已被 | 展开逻辑处理过的结构节点的子孙
            boolean underExpanded = expandedStructPaths.stream()
                    .anyMatch(ep -> attrPath.startsWith(ep + "."));
            if (underExpanded) continue;

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

                // ★ 新增：检查该结构节点子树是否含 | 字段需要展开。
                //   背景：detectLoopPattern 只看 ; 字段来决定整篇文档的 LoopMode；
                //   若某辆车全文档没有任何 ; 多值字段（LoopMode=NONE），只有 EnergySource
                //   这种单纯用 | 分段的字段，此前 buildNormalTree 完全没有处理 | 的逻辑，
                //   会导致 EnergySource="10|95" 之类的值被当成普通单值原样写入，
                //   PowerGroup 也不会按段拆分。这里补上与 buildUnprocessedNodes 的
                //   hasPipe 分支一致的逻辑，确保两条路径行为一致。
                List<XmlTemplateAttribute> subAttrs = attrList.stream()
                        .filter(a -> a.getAttrPath().startsWith(attrPath + "."))
                        .collect(Collectors.toList());

                boolean subHasPipe = subAttrs.stream().anyMatch(a -> {
                    String[] p = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                    if (d == null || isStructNode(d)) return false;
                    Object raw = jsonMap.get(d.getDictLabel());
                    return raw != null && raw.toString().contains("|");
                });

                // ★ 检查该结构节点子树是否含 ; 字段需要展开
                // ★ 修复：仅在 buildNormalTree（无循环场景）下触发，含 ; 不含 | 的孙级叶子才展开
                boolean subHasSemi = !subHasPipe && attrList.stream().anyMatch(a -> {
                    if (!a.getAttrPath().startsWith(attrPath + ".")) return false;
                    // ★ 修复：不限制深度，扫描所有子孙叶子字段
                    //   原 nodeDepth+2 会漏掉 Group→Field 只有一层的结构（如 ColourGroup→Colour）
                    String[] p = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                    if (d == null || isStructNode(d)) return false;
                    Object raw = jsonMap.get(d.getDictLabel());
                    return raw != null && raw.toString().contains(";") && !raw.toString().contains("|");
                });

                log.info("=== buildNormalTree struct subHasPipe={} subHasSemi={} label={}",
                        subHasPipe, subHasSemi, dict.getDictLabel());
                if (subHasPipe) {
                    int pipeRows = detectPipeRows(subAttrs, dictCodeMap, jsonMap, attrPath);
                    Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap, attrPath, structElement);
                    expandPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                            subMap, attrPath, pipeRows);
                    // ★ 新增：标记该子树已完整展开，主循环跳过其子孙，避免重复创建
                    expandedStructPaths.add(attrPath);
                } else if (subHasSemi) {
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
                addElement(doc, parentElement, dict, value, required);
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
                boolean required = sibling.getIsRequired() != null && sibling.getIsRequired() == 1;
                if (!value.contains(";")) {
                    addElement(doc, parentElement, dict, value, required);
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
                boolean required = child.getIsRequired() != null && child.getIsRequired() == 1;
                addElement(doc, container, dict, value, required);
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

        log.info("=== buildSiblingLevelLoop START loopContainerPath末段={} parentOfContainerPath末段={} maxRows={}",
                loopContainerPath.substring(loopContainerPath.lastIndexOf('.')+1),
                parentOfContainerPath.isEmpty() ? "ROOT" : parentOfContainerPath.substring(parentOfContainerPath.lastIndexOf('.')+1),
                loopResult.getMaxRows());

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
                // ★ 同时把 parentElement（loopContainerPath 的父节点）也确保登记在 pathNodeMap 里，
                //   防止 buildUnprocessedNodes BFS 时因找不到父节点的 DOM 而重复创建 loopContainerPath 节点
                pathNodeMap.putIfAbsent(parentOfContainerPath, parentElement);

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
                log.info("=== buildSiblingLevelLoop STRUCT sibling末段={} tag={} inPathNodeMap={} isAncestorOfLoop={}",
                        sibling.getAttrPath().substring(sibling.getAttrPath().lastIndexOf('.')+1),
                        dict.getDictLabel(),
                        pathNodeMap.containsKey(sibling.getAttrPath()),
                        loopContainerPath != null && loopContainerPath.startsWith(sibling.getAttrPath() + "."));
                // ★ 修复：同时检查 pathNodeMap（逻辑层）和 DOM（物理层），防止 buildTreeUpToPath 已写入后再重复创建
                if (!pathNodeMap.containsKey(sibling.getAttrPath())
                        && !hasChildElement(parentElement, sanitizeXmlTagName(dict.getDictLabel()))) {
                    List<XmlTemplateAttribute> subAttrs = attrList.stream()
                            .filter(a -> a.getAttrPath().startsWith(sibling.getAttrPath() + "."))
                            .collect(Collectors.toList());

                    // ★ 该节点是否是 loopContainerPath 的祖先（如 GearGroup 包含 GearRatioTable）
                    boolean isAncestorOfLoop = loopContainerPath != null
                            && loopContainerPath.startsWith(sibling.getAttrPath() + ".");

                    // ★ 检查该子树下是否有 | 字段（仅检查直接子叶子，不跨子树污染）
                    boolean hasPipe = !isAncestorOfLoop && subAttrs.stream().anyMatch(a -> {
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
                        pathNodeMap.putAll(subMap);
                    } else if (isAncestorOfLoop) {
                        // ★ 该节点是 loopContainerPath 的祖先（如 GearGroup 包含 GearRatioTable）：
                        //   buildTreeUpToPath 已在 pathNodeMap 里建好该节点及其到 loopContainerPath 的祖先链，
                        //   structElement 已 append 到 DOM，只需确保 pathNodeMap 登记。
                        //   不调 buildSubTree——否则会在 loopContainerPath 节点上再次创建子节点，
                        //   绕过 equals(loopContainerPath) 分支，导致 GearRatioTable 被创建多次。
                        //   GearRatioTable 的 7 个 GearRatioGroup 由 directSiblings 的
                        //   equals(loopContainerPath) 分支负责展开。
                        //   只补充该节点下不在 loopContainerPath 子树内的直接叶子字段（如 GearboxType）。
                        pathNodeMap.put(sibling.getAttrPath(), structElement);
                        for (XmlTemplateAttribute leafAttr : subAttrs) {
                            String[] lp = leafAttr.getAttrPath().split("\\.");
                            SysDictData ld = dictCodeMap.get(lp[lp.length - 1]);
                            if (ld == null || isStructNode(ld)) continue;
                            // 只处理直接子叶子（不在 loopContainerPath 子树内）
                            if (loopContainerPath != null && leafAttr.getAttrPath().startsWith(loopContainerPath + ".")) continue;
                            String leafParentPath = getParentPath(leafAttr.getAttrPath());
                            Element leafParent = pathNodeMap.get(leafParentPath);
                            if (leafParent == null) continue;
                            Object raw = jsonMap.get(ld.getDictLabel());
                            String val = raw != null ? raw.toString() : "";
                            boolean leafRequired = leafAttr.getIsRequired() != null && leafAttr.getIsRequired() == 1;
                            if (!val.contains(";") && !hasChildElement(leafParent, sanitizeXmlTagName(ld.getDictLabel()))) {
                                addElement(doc, leafParent, ld, val, leafRequired);
                            }
                        }
                    } else {
                        // ★ 检测含 ; 不含 | 的叶子字段（如 ColourGroup→Colour），直接展开，不走 buildSubTree
                        // ★ 条件：含 ; 的叶子字段深度 <= sibling深度+2（即 Table→Group→Field 两层内）
                        //   若含 ; 的字段在更深层（如 GearGroup→GearRatioTable→GearRatioGroup→GearNumber，深度差=4），
                        //   说明循环在深层嵌套结构中，应交给 buildUnprocessedNodes 处理，不在此展开。
                        int siblingDepth = sibling.getAttrPath().split("\\.").length;
                        boolean hasSemi = subAttrs.stream().anyMatch(a -> {
                            String[] p = a.getAttrPath().split("\\.");
                            SysDictData d = dictCodeMap.get(p[p.length - 1]);
                            if (d == null || isStructNode(d)) return false;
                            Object raw = jsonMap.get(d.getDictLabel());
                            if (raw == null || !raw.toString().contains(";") || raw.toString().contains("|")) return false;
                            // 含 ; 的叶子深度必须在 sibling深度+2 以内（Table→Group→Field）
                            return p.length <= siblingDepth + 2;
                        });
                        if (hasSemi) {
                            int semiRows = detectSemicolonRows(subAttrs, dictCodeMap, jsonMap, sibling.getAttrPath());
                            log.info("=== buildSiblingLevelLoop hasSemi: sibling末段={} semiRows={} tag={}",
                                    sibling.getAttrPath().substring(sibling.getAttrPath().lastIndexOf('.')+1),
                                    semiRows,
                                    dict.getDictLabel());
                            Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap, sibling.getAttrPath(), structElement);
                            expandPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                                    subMap, sibling.getAttrPath(), semiRows);
                            pathNodeMap.putAll(subMap);
                        } else {
                            pathNodeMap.put(sibling.getAttrPath(), structElement);
                            buildSubTree(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                                    pathNodeMap, sibling.getAttrPath(), -1);
                        }
                    }
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel()) && !isStructNode(dict)) {
                Object raw = jsonMap.get(dict.getDictLabel());
                String value = getValueOrDefault(raw, sibling.getDefaultValue());
                boolean required = sibling.getIsRequired() != null && sibling.getIsRequired() == 1;
                // ★ 已有同名子节点则跳过，防止 buildTreeUpToPath 已写过的字段重复输出
                if (!value.contains(";") && !hasChildElement(parentElement, sanitizeXmlTagName(dict.getDictLabel()))) {
                    addElement(doc, parentElement, dict, value, required);
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
            boolean required = leaf.getIsRequired() != null && leaf.getIsRequired() == 1;
            addElement(doc, container, dict, value, required);
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
                boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
                // ★ 修复：加重复检查，防止后续 buildSiblingLevelLoop 遍历 directSiblings 时再次写入同名节点
                if (!value.contains(";") && !hasChildElement(parentElement, sanitizeXmlTagName(dict.getDictLabel()))) {
                    addElement(doc, parentElement, dict, value, required);
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

        // ★ 记录已通过 expandPipeLoop 展开的结构节点路径，主循环遇到其子孙时跳过，避免重复写入
        Set<String> expandedStructPaths = new java.util.HashSet<>();

        // 正常逐节点处理
        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            // ★ 跳过已展开结构节点的子孙
            boolean underExpanded = expandedStructPaths.stream()
                    .anyMatch(ep -> attrPath.startsWith(ep + "."));
            if (underExpanded) continue;

            String[] parts = attrPath.split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            String parentPath = getParentPath(attrPath);
            Element parentElement = pathNodeMap.get(parentPath);
            if (parentElement == null) continue;

            if (isStructNode(dict)) {
                log.info("=== buildSubTree STRUCT attrPath末段={} tag={} rowIndex={}",
                        attrPath.substring(attrPath.lastIndexOf('.')+1),
                        dict.getDictLabel(), rowIndex);

                List<XmlTemplateAttribute> subAttrs = attrList.stream()
                        .filter(a -> a.getAttrPath().startsWith(attrPath + "."))
                        .collect(Collectors.toList());

                if (rowIndex < 0) {
                    // ★ 修复：非循环场景下，先检测子树是否含深层 ; 循环
                    //   （如 GearRatioTable→GearRatioGroup→GearNumber）。
                    //   若含，则【不提前创建本节点】，直接委托 buildSiblingLevelLoop 处理整个子树。
                    //   buildSiblingLevelLoop 会自己创建 GearRatioTable 并正确展开 GearRatioGroup×N。
                    //   若提前 createElement + pathNodeMap.put 再调 buildSiblingLevelLoop，
                    //   buildTreeUpToPath 会因 GearRatioTable 已在 map 而错误跳过，导致子节点丢失。
                    boolean hasDeepSemi = subAttrs.stream().anyMatch(a -> {
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        Object raw = jsonMap.get(d.getDictLabel());
                        return raw != null && raw.toString().contains(";") && !raw.toString().contains("|");
                    });
                    if (hasDeepSemi) {
                        List<XmlTemplateAttribute> subLeafNodes = subAttrs.stream()
                                .filter(a -> {
                                    String[] p = a.getAttrPath().split("\\.");
                                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                                    return d != null && !isStructNode(d);
                                })
                                .collect(Collectors.toList());
                        LoopDetectionResult subLoopResult = detectLoopPattern(subLeafNodes, dictCodeMap, jsonMap);
                        if (subLoopResult.getLoopMode() != LoopMode.NONE) {
                            // ★ 修复：不提前 createElement，完全委托 buildSiblingLevelLoop 创建并展开。
                            //   attrList 传本节点自身 + 子孙（含 GearRatioTable 本身的 attr），
                            //   rootAttrPath 传父路径，parentElement 传父 DOM 节点，
                            //   buildSiblingLevelLoop 会找到 loopContainerPath(=attrPath) 并自行 appendChild。
                            // 构造包含本节点自身的 attrList（从原始 attrList 过滤）
                            final String selfPath = attrPath;
                            List<XmlTemplateAttribute> selfAndSubAttrs = attrList.stream()
                                    .filter(a -> a.getAttrPath().equals(selfPath)
                                            || a.getAttrPath().startsWith(selfPath + "."))
                                    .collect(Collectors.toList());
                            Set<String> subStructNodePaths = selfAndSubAttrs.stream()
                                    .filter(a -> {
                                        String[] p = a.getAttrPath().split("\\.");
                                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                                        return d != null && isStructNode(d);
                                    })
                                    .map(XmlTemplateAttribute::getAttrPath)
                                    .collect(Collectors.toSet());
                            Map<String, Element> subPathNodeMap = new HashMap<>(pathNodeMap);
                            buildSiblingLevelLoop(doc, parentElement, selfAndSubAttrs, dictCodeMap, jsonMap,
                                    subPathNodeMap, subStructNodePaths, subLoopResult, parentPath);
                            pathNodeMap.putAll(subPathNodeMap);
                            expandedStructPaths.add(attrPath);
                            continue;
                        }
                    }
                    // 无深层循环：正常创建节点，继续主循环处理子节点
                    Element structElement = createElementWithDefault(doc,
                            sanitizeXmlTagName(dict.getDictLabel()), attr.getDefaultValue());
                    parentElement.appendChild(structElement);
                    pathNodeMap.put(attrPath, structElement);
                } else {
                    Element structElement = createElementWithDefault(doc,
                            sanitizeXmlTagName(dict.getDictLabel()), attr.getDefaultValue());
                    parentElement.appendChild(structElement);
                    pathNodeMap.put(attrPath, structElement);
                    // 循环场景（rowIndex>=0）：每个父行独立创建自己的嵌套结构，使用纯局部 subMap，不回写主 map

                    // ★ 修复：检测子结构的直接子 Group 层（attrPath深度+2）是否有 | 字段。
                    //   若有，说明该 | 是本层 Table 自身 Group 的行数
                    //   （如 EnergySourceTable 内 EnergySource="10|95" 代表 2 个 EnergySourceGroup）。
                    //   此时应直接用 expandPipeLoop 展开，不应走 expandNestedPipeLoop 的外层切片逻辑：
                    //   切片会将 EnergySource="10|95" 按父行 rowIndex 取成单值 "10"，导致只生成 1 个 Group。
                    //
                    //   ★ 新增修复：但若该 | 字段的段数恰好等于外层容器（rootAttrPath，如
                    //   EnergyConvertorGroup）自身的总行数，说明它实际是与外层行一一对齐的
                    //   （如 4 个 EnergyConvertorGroup 对应 EnergySource="10|95|95|95"：第0段对应
                    //   燃油机，第1~3段分别对应3个电机），而不是"本 Table 自身另有一套独立循环"。
                    //   这种对齐场景必须改走 expandNestedPipeLoop 按外层 rowIndex 切出当前行专属的
                    //   单段值，否则每个外层行都会用同一份未切片数据重新展开全部段，
                    //   导致笛卡尔积式重复（如本例 4 外层 × 4 内层 = 16）。
                    int attrDepth = attrPath.split("\\.").length;
                    int totalOuterRows = calcTotalRowsForGroup(attrList, dictCodeMap, jsonMap, rootAttrPath);
                    boolean hasDirectGroupPipe = subAttrs.stream().anyMatch(a -> {
                        if (a.getAttrPath().split("\\.").length != attrDepth + 2) return false;
                        String[] p = a.getAttrPath().split("\\.");
                        SysDictData d = dictCodeMap.get(p[p.length - 1]);
                        if (d == null || isStructNode(d)) return false;
                        Object raw = jsonMap.get(d.getDictLabel());
                        if (raw == null || !raw.toString().contains("|")) return false;
                        int segs = raw.toString().trim().split("\\|", -1).length;
                        if (totalOuterRows > 1 && segs <= totalOuterRows) return false;
                        return true;
                    });

                    if (hasDirectGroupPipe) {
                        // 直接子 Group 层有 | 字段：本 Table 自身即为管道循环容器，
                        // 用 expandPipeLoop 直接展开（不做外层 rowIndex 切片）
                        int pipeRows = detectPipeRows(subAttrs, dictCodeMap, jsonMap, attrPath);
                        Map<String, Element> subMap = buildSubPathNodeMap(pathNodeMap, attrPath, structElement);
                        expandPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                                subMap, attrPath, pipeRows);
                        expandedStructPaths.add(attrPath);
                    } else {
                        int nestedRows = detectNestedRowsForIndex(subAttrs, dictCodeMap, jsonMap, attrPath, rowIndex, totalOuterRows);
                        if (nestedRows > 0) {
                            Map<String, Element> subMap = new LinkedHashMap<>();
                            subMap.put(attrPath, structElement);
                            // 外层 rowIndex 切片后按 ; 展开子行（如 TyreAxleTable 内 TyreSize="A;B|C;D"）
                            expandNestedPipeLoop(doc, structElement, subAttrs, dictCodeMap, jsonMap,
                                    subMap, attrPath, nestedRows, rowIndex);
                            // 循环场景：不回写主 pathNodeMap，标记已展开，主循环跳过其子孙
                            expandedStructPaths.add(attrPath);
                        }
                    }
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel())) {
                int totalRows = (rowIndex >= 0)
                        ? calcTotalRowsForGroup(attrList, dictCodeMap, jsonMap, rootAttrPath)
                        : -1;

                String value = getValueByRow(jsonMap, dict.getDictLabel(),
                        attr.getDefaultValue(), rowIndex, totalRows, LAST_ROW_FORBIDDEN_LABELS);
                boolean required = attr.getIsRequired() != null && attr.getIsRequired() == 1;
                if (StringUtils.isNotBlank(value) || required) {
                    addElement(doc, parentElement, dict, value, required);
                }
            }
        }
    }

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
        for (XmlTemplateAttribute attr : attrList) {
            if (attr.getAttrPath().equals(rootAttrPath)) continue;
            // ★ 修复：不限制深度，扫描所有子孙叶子字段
            //   原 rootDepth+2 会漏掉 Group→Field 只有一层的结构（如 ColourGroup→Colour）
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString();
            log.info("=== detectSemicolonRows check label={} val={}", dict.getDictLabel(), val);
            if (val.contains(";") && !val.contains("|")) {
                int rows = countNonTrailingEmpty(val.split(";", -1));
                maxRows = Math.max(maxRows, rows);
            }
        }
        log.info("=== detectSemicolonRows result rootAttrPath末段={} maxRows={}",
                rootAttrPath.substring(rootAttrPath.lastIndexOf('.')+1), maxRows);
        return maxRows;
    }

    /**
     * 循环场景下，计算某个结构节点（如 TyreAxleTable）在第 rowIndex 个父行中，
     * 其直接子 Group 层叶子字段应展开的次数。
     * <p>
     * 字段值格式：外层按 | 区分父行，每段内按 ; 区分子行，例如：
     *   TyreSize = "215/55R18;215/55R18|215/55R18;215/55R18"
     * 表示第 0 个 AxleGroup 下有 2 个 TyreAxleGroup，第 1 个 AxleGroup 下也有 2 个。
     * <p>
     * 若字段只含 | 不含 ;，则该字段在该行内是单值，贡献行数 = 1。
     * 若字段既无 | 也无 ;，则所有行共用单值，贡献行数 = 1。
     * 返回 0 表示该结构节点在此 rowIndex 无需展开（无分隔字段或当前行所有字段均为空）。
     */
    private int detectNestedRowsForIndex(List<XmlTemplateAttribute> subAttrs,
                                         Map<String, SysDictData> dictCodeMap,
                                         Map<String, Object> jsonMap,
                                         String containerPath,
                                         int rowIndex,
                                         int totalOuterRows) {
        int maxRows = 0;
        int containerDepth = containerPath.split("\\.").length;
        boolean hasPipeField = false; // 子树中是否存在含 | 的字段
        boolean foundDirectGroupLeaf = false; // containerDepth+2 层是否找到含 | 的叶子

        for (XmlTemplateAttribute attr : subAttrs) {
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString().trim();

            if (val.contains("|")) {
                hasPipeField = true;
                int attrDepth = attr.getAttrPath().split("\\.").length;
                if (attrDepth == containerDepth + 2) {
                    int segs = val.split("\\|", -1).length;
                    // ★ 修复：若该字段的 | 段数恰好等于外层容器自身总行数（totalOuterRows，>1），
                    //   说明它是与外层行一一对齐的字段（如 EnergySource="10|95|95|95" 对应
                    //   4 个 EnergyConvertorGroup），而不是"本 Table 自身另有一套独立循环"，
                    //   不应据此把行数撑大——交由调用方传入的 slicedJsonMap（已按外层 rowIndex
                    //   切到单段）走下面"至少 1 行"的兜底分支即可。
                    if (totalOuterRows > 1 && segs <= totalOuterRows) {
                        continue;
                    }
                    // ★ 关键修复：直接子 Group 层叶子含 | 时，以 | 总段数作为子行数（即本 Table 内的 Group 数），
                    //   不再按 rowIndex 切片取单段。
                    //   原逻辑对 EnergySource="10|95"（containerDepth+2）按 rowIndex=0 取段 "10"（单值）→ maxRows=1，
                    //   导致 EnergySourceGroup 只创建 1 个。
                    //   正确语义：EnergySourceTable 内有几个 | 段就应创建几个 EnergySourceGroup。
                    foundDirectGroupLeaf = true;
                    maxRows = Math.max(maxRows, segs);
                }
            } else if (val.contains(";") && attr.getAttrPath().split("\\.").length == containerDepth + 2) {
                // 无外层 |，直接按 ; 展开（所有父行共用）
                String[] items = val.split(";", -1);
                maxRows = Math.max(maxRows, countNonTrailingEmpty(items));
            }
        }

        // ★ 若直接子 Group 层没有含 | 的叶子，但更深层有 | 字段，
        //   则至少返回 1，确保 expandNestedPipeLoop 被触发以正确切片各组数据。
        if (hasPipeField && !foundDirectGroupLeaf && maxRows == 0) {
            maxRows = 1;
        }

        return maxRows;
    }

    /**
     * 扫描 attrList 里的叶子节点，看是否有 | 分隔的字段
     * 返回最大行数（没有 | 则返回 1）
     */
    /**
     * 嵌套循环场景下（外层 rowIndex >= 0）的子树展开。
     * <p>
     * 与 expandPipeLoop 的区别：先将 jsonMap 中所有含 | 的字段按 parentRowIndex 切片，
     * 得到一个仅含当前外层行数据的 slicedJsonMap，再复用原有 buildSubTree(rowIndex=-1) 逻辑展开子树。
     * <p>
     * 这样可以完整复用所有已有逻辑：
     * - 含 ; 的字段（如 TestFamilyIdentifier）由 buildSubTree 内部的 expandPipeLoop/detectSemicolonRows 正确展开
     * - 空值字段（该 EnergyConvertor 组不含的 Power 字段）因切片后为空而被跳过，不写入 XML
     * - 单值字段（所有组共用，如 SoundLevelDriveBy）直接保留原值
     * <p>
     * 例：parentRowIndex=0（SQRH4J15，燃油机）时切片结果：
     *   EnergySource            "10|95|95|95"     → "10"
     *   MaximumNetPowerCombustion "105"            → "105"（无|，共用）
     *   Maximum30MinutesPower   "|45|90|175"      → ""（第0段为空，该组无此字段）
     *   TestFamilyIdentifier    "IP;ATCT;..."     → "IP;ATCT;..."（无|，共用，后续由;展开）
     */
    private void expandNestedPipeLoop(Document doc, Element parentElement,
                                      List<XmlTemplateAttribute> attrList,
                                      Map<String, SysDictData> dictCodeMap,
                                      Map<String, Object> jsonMap,
                                      Map<String, Element> pathNodeMap,
                                      String rootAttrPath, int rows, int parentRowIndex) {

        // 1. 构造切片后的 jsonMap：所有含 | 的字段按 parentRowIndex 取对应段，其余保留原值
        Map<String, Object> slicedJsonMap = sliceJsonMapByOuterRow(jsonMap, parentRowIndex);

        // 1b. 根据当前行的 EnergySource 值过滤 PowerGroup 字段：
        //   - EnergySource != 95（燃油组）：只保留 MaximumNetPowerCombustion、EngineSpeedMaximumNetPower
        //   - EnergySource == 95（电机组）：只保留 Maximum30MinutesPower、MaximumNetPowerElectric
        filterPowerGroupByEnergySource(slicedJsonMap);

        // 2. 找到 rootAttrPath 下的直接子节点（通常是 EnergySourceGroup）
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
                // Group 节点 → 循环展开 rows 次
                // 注：rows 既可能是「该 Group 本身按 ; 重复的次数」（如 BodyworkTypeGroup），
                //   也可能是「该 Group 本身只有1份，但其更深层还有一套独立的 ;循环」的场景
                //   （如 EnergySourceGroup 包了一层 TestFamilyIdentifiersTable）。
                //   这两种场景需要分别传给 buildSubTree 不同的 rowIndex，否则：
                //   - 若该 Group 直接含 ; 字段却传 rowIndex=-1，getValueByRow 在非循环场景下
                //     对含 ; 的字段直接返回空，导致整组字段丢失；
                //   - 若该 Group 本身不含 ; 字段（深层才循环）却传 rowIndex>=0，
                //     会误触发末行禁填等循环语义，偏离原有行为。
                List<XmlTemplateAttribute> groupChildren = attrList.stream()
                        .filter(a -> a.getAttrPath().startsWith(child.getAttrPath() + "."))
                        .collect(Collectors.toList());

                int groupDepth = child.getAttrPath().split("\\.").length;
                boolean selfHasSemiLeaf = groupChildren.stream().anyMatch(a -> {
                    if (a.getAttrPath().split("\\.").length != groupDepth + 1) return false;
                    String[] p = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                    if (d == null || isStructNode(d)) return false;
                    Object raw = slicedJsonMap.get(d.getDictLabel());
                    return raw != null && raw.toString().contains(";");
                });

                for (int i = 0; i < rows; i++) {
                    Element groupEl = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                    parentElement.appendChild(groupEl);

                    // ★ 关键：用 slicedJsonMap 替代原始 jsonMap，复用完整的 buildSubTree 逻辑。
                    //   selfHasSemiLeaf=true（如 BodyworkTypeGroup 自身含 ; 叶子）→ 传 i，
                    //     使 getValueByRow 按 ; 取第 i 段，每次循环拿到对应的那一份值；
                    //   selfHasSemiLeaf=false（如 EnergySourceGroup，深层才循环）→ 传 -1，
                    //     保持原有行为，由 buildSubTree 内部检测深层 ; 并递归展开。
                    Map<String, Element> subMap = new LinkedHashMap<>();
                    subMap.put(child.getAttrPath(), groupEl);
                    buildSubTree(doc, groupEl, groupChildren, dictCodeMap, slicedJsonMap,
                            subMap, child.getAttrPath(), selfHasSemiLeaf ? i : -1);
                }
            } else if (StringUtils.isNotBlank(dict.getDictLabel())) {
                // 直接叶子节点：取切片后的值
                Object raw = slicedJsonMap.get(dict.getDictLabel());
                String value = raw != null ? raw.toString().trim() : "";
                if (StringUtils.isBlank(value) && StringUtils.isNotBlank(child.getDefaultValue())) {
                    value = child.getDefaultValue();
                }
                boolean required = child.getIsRequired() != null && child.getIsRequired() == 1;
                if (StringUtils.isNotBlank(value) || required) {
                    addElement(doc, parentElement, dict, value, required);
                }
            }
        }
    }

    /**
     * 将 jsonMap 中所有含 | 的字段按 outerRowIndex 切到对应段，返回新 Map。
     * <p>
     * 规则：
     * <ul>
     *   <li>字段值含 |：按 | 拆分，取第 outerRowIndex 段（越界或空段则保留空字符串）</li>
     *   <li>字段值不含 |：原样保留（所有外层行共用，如 SoundLevelDriveBy、TestFamilyIdentifier）</li>
     * </ul>
     * 切片后为空的字段保留在 map 中（值为空字符串），由调用方（buildSubTree）按空值逻辑跳过输出。
     */
    private Map<String, Object> sliceJsonMapByOuterRow(Map<String, Object> jsonMap, int outerRowIndex) {
        Map<String, Object> sliced = new HashMap<>(jsonMap);
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            if (entry.getValue() == null) continue;
            String val = entry.getValue().toString().trim();
            if (!val.contains("|")) continue; // 无 |，所有行共用，原样保留
            String[] outerItems = val.split("\\|", -1);
            String segment = (outerRowIndex >= 0 && outerRowIndex < outerItems.length)
                    ? outerItems[outerRowIndex].trim() : "";
            sliced.put(entry.getKey(), segment);
        }
        return sliced;
    }

    /**
     * 当 EnergySource 为多段（含 |）时，在 DOM 中找到 CocDataGroup 节点，
     * 在 VehicleCertified156Indicator 标签之后插入以下两个汇总子标签：
     * <ul>
     *   <li>ConsolidatedMaximum30MinutesPower  —— Maximum30MinutesPower  各段数值之和</li>
     *   <li>ConsolidatedMaximumNetPowerElectric —— MaximumNetPowerElectric 各段数值之和</li>
     * </ul>
     * 求和时跳过空段和无法解析为数字的段（如拼接的前缀 "0"）。
     * 若 EnergySource 为单段、分段后 "95"（电机组）数量少于两个，或两个字段均无有效值，
     * 则不写入任何标签。
     */
    private void appendConsolidatedPowerFields(Document doc, Element root, Map<String, Object> jsonMap) {
        Object energySourceObj = jsonMap.get("EnergySource");
        if (energySourceObj == null) return;
        String energySource = energySourceObj.toString().trim();
        if (!energySource.contains("|")) return; // 单段，无需汇总

        // 统计分段后 "95"（电机组）的数量：只有 0 或 1 个电机组时，单个 PowerGroup
        // 已能表达功率，无需额外的 Consolidated 汇总标签，只有 >=2 个电机组才需要汇总
        String[] energySourceSegs = energySource.split("\\|", -1);
        long electricSegCount = Arrays.stream(energySourceSegs)
                .filter(seg -> "95".equals(seg.trim()))
                .count();
        if (electricSegCount < 2) return;

        // 注意：此时 jsonMap 中的值已经过 prependZeroForElectricPowerFields 处理（开头有 "0|"）
        // 求和时对所有段累加，"0" 段不影响结果
        BigDecimal sum30Min   = sumPipeSegments(jsonMap.get("Maximum30MinutesPower"));
        BigDecimal sumNetElec = sumPipeSegments(jsonMap.get("MaximumNetPowerElectric"));

        if (sum30Min == null && sumNetElec == null) return;

        // 找到 CocDataGroup 节点
        NodeList cocList = doc.getElementsByTagName("CocDataGroup");
        if (cocList.getLength() == 0) return;
        Element cocDataGroup = (Element) cocList.item(0);

        // 定位 VehicleCertified156Indicator 节点，插入到其后；若找不到则回退为追加到末尾
        Node refNode = null;
        NodeList certList = cocDataGroup.getElementsByTagName("VehicleCertified156Indicator");
        if (certList.getLength() > 0) {
            refNode = certList.item(0).getNextSibling();
        }

        if (sum30Min != null) {
            Element el30Min = doc.createElement("ConsolidatedMaximum30MinutesPower");
            el30Min.setTextContent(sum30Min.stripTrailingZeros().toPlainString());
            cocDataGroup.insertBefore(el30Min, refNode);
            refNode = el30Min.getNextSibling();
        }
        if (sumNetElec != null) {
            Element elNetElec = doc.createElement("ConsolidatedMaximumNetPowerElectric");
            elNetElec.setTextContent(sumNetElec.stripTrailingZeros().toPlainString());
            cocDataGroup.insertBefore(elNetElec, refNode);
        }
    }

    /**
     * 根据生成后 XML 中所有 EnergySource 标签的值，判断整车是否为纯电动：
     * 当且仅当文档中存在至少一个 EnergySource 标签，且其值全部为 "95" 时返回 true。
     * 文档中不存在任何 EnergySource 标签，或存在非 "95" 的值（如燃油 "10"、氢能源 "90" 等），
     * 均返回 false。
     */
    private boolean isFullyElectricByEnergySource(Document doc) {
        NodeList energySourceNodes = doc.getElementsByTagName("EnergySource");
        if (energySourceNodes.getLength() == 0) return false;
        for (int i = 0; i < energySourceNodes.getLength(); i++) {
            String value = energySourceNodes.item(i).getTextContent();
            value = value == null ? "" : value.trim();
            if (!"95".equals(value)) return false;
        }
        return true;
    }

    /**
     * 当某个 EnergySource 标签的值为 "90"、"91"、"95" 之一时（代表氢能源/其他/电机等
     * 非内燃机类型的能量源），删除该 EnergySource 父节点的父节点的同级容器
     * （即 EnergyConvertorGroup）下的 EngineCapacity、NumberOfCylinders、ArrangementCylinders
     * 三个标签。
     * <p>
     * 即：EnergySource → 父节点 → 父节点的父节点（祖父节点）→ 祖父节点的父节点（同级容器，
     * 通常是 EnergyConvertorGroup），在该容器的直属子标签中查找并删除上述三个标签。
     * 三者均只对内燃机有意义（气缸容量、气缸数、气缸排列方式），当对应分组的能量源
     * 为非内燃机类型时，该分组下不应再保留这三个标签；EngineCapacity 不存在等价于
     * NumberOfCylinders/ArrangementCylinders 也不该保留，故合并为同一次删除。
     * <p>
     * 该方法仅依据 DOM 相对位置定位（父的父的同级容器），不对中间层级标签名称
     * （如 EnergySourceGroup、EnergySourceTable）做硬编码假设。
     * 若某一层级缺失（结构层级不够深）或未找到对应标签，则跳过，不报错。
     */
    private void removeEngineCapacityForElectricEnergySource(Document doc) {
        Set<String> tagsToRemove = new HashSet<>(Arrays.asList(
                "EngineCapacity", "NumberOfCylinders", "ArrangementCylinders"));

        NodeList energySourceNodes = doc.getElementsByTagName("EnergySource");
        Set<Element> toRemove = new LinkedHashSet<>();

        for (int i = 0; i < energySourceNodes.getLength(); i++) {
            Node esNode = energySourceNodes.item(i);
            String value = esNode.getTextContent() == null ? "" : esNode.getTextContent().trim();
            if (!"90".equals(value) && !"91".equals(value) && !"95".equals(value)) continue;

            Node parent = esNode.getParentNode();                              // EnergySource 的父节点
            Node grandParent = (parent != null) ? parent.getParentNode() : null; // 父的父（祖父节点）
            Node siblingOwner = (grandParent != null) ? grandParent.getParentNode() : null; // 祖父节点的父节点（同级容器，即 EnergyConvertorGroup）
            if (siblingOwner == null || siblingOwner.getNodeType() != Node.ELEMENT_NODE) continue;

            NodeList siblingCandidates = siblingOwner.getChildNodes();
            for (int j = 0; j < siblingCandidates.getLength(); j++) {
                Node sibling = siblingCandidates.item(j);
                if (sibling.getNodeType() == Node.ELEMENT_NODE && tagsToRemove.contains(sibling.getNodeName())) {
                    toRemove.add((Element) sibling);
                }
            }
        }

        for (Element el : toRemove) {
            Node parent = el.getParentNode();
            if (parent != null) {
                parent.removeChild(el);
            }
        }
        if (!toRemove.isEmpty()) {
            log.info("=== removeEngineCapacityForElectricEnergySource 已删除 EngineCapacity/NumberOfCylinders/ArrangementCylinders 标签数={}", toRemove.size());
        }
    }

    /**
     * 若生成后的 XML 中 Header 节点下不存在 IntendedCountryRegistration 标签，
     * 则在 IviVersionDateTime 标签之后补充插入一个，值取车辆 country 字段
     * 通过 sys_dict_data（dict_type=country）的 value_connection 映射后的结果
     * （见 {@link #resolveCountryDictCode}）。
     * <p>
     * 若 Header 节点不存在、IntendedCountryRegistration 已存在（幂等）、
     * 或 country 值在字典中找不到映射，均直接跳过，不影响主流程。
     */
    private void ensureIntendedCountryRegistration(Document doc, VehicleInfo vehicle) {
        NodeList headerList = doc.getElementsByTagName("Header");
        if (headerList.getLength() == 0) return;
        Element header = (Element) headerList.item(0);

        // 已存在则跳过，保证幂等
        if (header.getElementsByTagName("IntendedCountryRegistration").getLength() > 0) return;

        String countryCode = resolveCountryDictCode(vehicle.getCountry());
        if (StringUtils.isBlank(countryCode)) {
            log.warn("[ensureIntendedCountryRegistration] vin={} country 为空，跳过补充 IntendedCountryRegistration",
                    vehicle.getVin());
            return;
        }

        // 定位 IviVersionDateTime 节点，插入到其后；找不到则回退为追加到 Header 末尾
        Node refNode = null;
        NodeList iviList = header.getElementsByTagName("IviVersionDateTime");
        if (iviList.getLength() > 0) {
            refNode = iviList.item(0).getNextSibling();
        }

        Element intendedCountryEl = doc.createElement("IntendedCountryRegistration");
        intendedCountryEl.setTextContent(countryCode);
        header.insertBefore(intendedCountryEl, refNode);
        log.info("[ensureIntendedCountryRegistration] vin={} 已补充 IntendedCountryRegistration={}", vehicle.getVin(), countryCode);
    }

    /**
     * 燃油类型专属：每个 GearRatioGroup 下的 FinalDriveTable，只保留
     * FinalDriveNumber 与该 GearRatioGroup 的 GearNumber 相等的那一组 FinalDriveGroup，
     * 其余 FinalDriveGroup 全部删除。
     * <p>
     * 背景：建树阶段 FinalDriveTable 是按完整的 FinalDriveNumber/FinalDriveRatio 集合
     * 展开的，每个 GearRatioGroup 下都会重复出现全部档位的 FinalDriveGroup；而实际只有
     * FinalDriveNumber 与当前 GearNumber 一致的那一组才是该挡位真正对应的最终传动比，
     * 其余是从原始数据共享复制过来的冗余项，需要按挡位过滤掉。
     * <p>
     * 找不到 GearNumber、找不到 FinalDriveTable，或 FinalDriveGroup 下没有
     * FinalDriveNumber 子标签时，均跳过该 GearRatioGroup，不报错、不影响其余分组处理。
     */
    private void restrictFinalDriveGroupByGearNumber(Document doc) {
        NodeList gearRatioGroups = doc.getElementsByTagName("GearRatioGroup");
        int totalRemoved = 0;

        for (int i = 0; i < gearRatioGroups.getLength(); i++) {
            Node grNode = gearRatioGroups.item(i);
            if (!(grNode instanceof Element)) continue;
            Element gearRatioGroup = (Element) grNode;
            NodeList children = gearRatioGroup.getChildNodes();

            // 取该 GearRatioGroup 的直接子标签 GearNumber
            String gearNumber = null;
            Element finalDriveTable = null;
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                if ("GearNumber".equals(child.getNodeName())) {
                    gearNumber = child.getTextContent() == null ? "" : child.getTextContent().trim();
                } else if ("FinalDriveTable".equals(child.getNodeName())) {
                    finalDriveTable = (Element) child;
                }
            }
            if (StringUtils.isBlank(gearNumber) || finalDriveTable == null) continue;

            // 删除 FinalDriveTable 下 FinalDriveNumber 不等于 gearNumber 的 FinalDriveGroup
            NodeList fdGroupNodes = finalDriveTable.getChildNodes();
            List<Node> toRemove = new ArrayList<>();
            for (int j = 0; j < fdGroupNodes.getLength(); j++) {
                Node fdNode = fdGroupNodes.item(j);
                if (fdNode.getNodeType() != Node.ELEMENT_NODE || !"FinalDriveGroup".equals(fdNode.getNodeName())) continue;
                Element fdGroup = (Element) fdNode;

                String finalDriveNumber = null;
                NodeList fdChildren = fdGroup.getChildNodes();
                for (int k = 0; k < fdChildren.getLength(); k++) {
                    Node fdChild = fdChildren.item(k);
                    if (fdChild.getNodeType() == Node.ELEMENT_NODE && "FinalDriveNumber".equals(fdChild.getNodeName())) {
                        finalDriveNumber = fdChild.getTextContent() == null ? "" : fdChild.getTextContent().trim();
                        break;
                    }
                }
                if (!gearNumber.equals(finalDriveNumber)) {
                    toRemove.add(fdGroup);
                }
            }
            for (Node n : toRemove) {
                finalDriveTable.removeChild(n);
            }
            totalRemoved += toRemove.size();
        }

        if (totalRemoved > 0) {
            log.info("[restrictFinalDriveGroupByGearNumber] 已按 GearNumber 过滤 FinalDriveGroup，共删除 {} 组", totalRemoved);
        }
    }

    /**
     * 通用的 sys_dict_data value_connection 值映射：
     * 按 dictType 查出该字典类型下的全部记录，用 dictLabel（通常与目标 XML 标签名一致）
     * 匹配到对应记录，取其 value_connection 字段，用 {@link ValueMappingParser#mergeValueConnection}
     * 按 "MES_xx": {原始值: 目标值} 的结构合并为单层 Map，再直接按 rawValue 查出映射后的结果
     * （不经过 {@link ValueMappingParser#convertWithDictMap}，原因见方法内注释）。
     * <p>
     * 查不到对应记录、value_connection 为空、或合并表中查不到映射结果时，
     * 均原样返回传入的 rawValue 作为兜底，而不是 null，避免因字典配置缺失导致标签整体缺失。
     *
     * @param dictType  sys_dict_data 的字典类型（dict_type）
     * @param dictLabel 用于匹配记录的字典标签（dict_label），通常与目标 XML 标签名一致
     * @param rawValue  待映射的原始值
     * @return 映射后的值；找不到映射时原样返回 rawValue；rawValue 本身为空时返回 null
     */
    private String resolveDictConnectionValue(String dictType, String dictLabel, String rawValue) {
        if (StringUtils.isBlank(rawValue)) return null;
        List<SysDictData> dictList = remoteDictService.getDictDataByType(dictType).getData();
        if (dictList == null) return rawValue;

        // 用标签名匹配 dict_label，定位对应的字典记录
        SysDictData matched = dictList.stream()
                .filter(d -> dictLabel.equals(d.getDictLabel()))
                .findFirst()
                .orElse(null);
        if (matched == null || StringUtils.isBlank(matched.getValueConnection())) {
            return rawValue;
        }

        // ★ 不走 ValueMappingParser.convertWithDictMap：该方法在 value_map 列为空、
        //   或未配置为 "DICT_MAP" 时会直接原样返回 rawValue（见其源码：
        //   `if (valueMap == null || isBlank(valueMap)) return rawValue;`）。
        //   这里命中的字典记录的 value_map 列通常并未配置，导致映射逻辑被整体跳过——
        //   这正是"没有走 value_connection 逻辑映射"的根因。
        //   直接对合并后的 Map 做查找，不依赖 value_map 列的配置。
        Map<String, String> mergedMap = ValueMappingParser.mergeValueConnection(matched.getValueConnection());
        String converted = mergedMap.get(rawValue.trim());
        return StringUtils.isNotBlank(converted) ? converted : rawValue;
    }

    /**
     * country -> IntendedCountryRegistration 的值映射，委托给通用方法 {@link #resolveDictConnectionValue}。
     *
     * @param country 车辆信息中的原始 country 值（即 dict_value，如 "POL"）
     * @return 映射后的值；找不到映射时原样返回 country；country 本身为空时返回 null
     */
    private String resolveCountryDictCode(String country) {
        return resolveDictConnectionValue("vehicle_attribute", "IntendedCountryRegistration", country);
    }

    /**
     * color -> Colour 的值映射，委托给通用方法 {@link #resolveDictConnectionValue}。
     * 与 resolveCountryDictCode 同一套逻辑：用标签名 "Colour" 匹配 dict_label，
     * 取其 value_connection 做映射，查不到映射时原样返回对应值。
     * <p>
     * 颜色可能是双色车的 "主色;副色" 两段式值（如 "Z3;CP"），此时按 ; 拆开后
     * 逐段独立映射，再按原顺序和原分隔位置拼回（保留空段，如只有主色没有副色时
     * 的尾部空段），不会把整个 "Z3;CP" 当成一个整体去查找（那样必然查不到）。
     *
     * @param color 车辆信息中的原始 color 值，单色或 ; 分隔的双色
     * @return 映射后的值；找不到映射的段原样返回该段；color 本身为空时返回 null
     */
    private String resolveColourDictValue(String color) {
        if (StringUtils.isBlank(color)) return null;
        if (!color.contains(";")) {
            return resolveDictConnectionValue("vehicle_attribute", "Colour", color);
        }

        // 双色：按 ; 拆开，逐段映射后按原顺序拼回，保留空段（不对空段做映射）
        String[] segments = color.split(";", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append(";");
            String seg = segments[i].trim();
            if (StringUtils.isNotBlank(seg)) {
                sb.append(resolveDictConnectionValue("vehicle_attribute", "Colour", seg));
            }
        }
        return sb.toString();
    }

    /**
     * 将 | 分隔的多段字符串中各段解析为 BigDecimal 并累加，跳过空段和非数字段。如果没有 | 分隔符，则用 ; 分隔符
     * 返回 null 表示没有任何有效数值段。
     */
    private BigDecimal sumPipeSegments(Object raw) {
        if (raw == null) return null;
        String val = raw.toString().trim();
        if (StringUtils.isBlank(val)) return null;

        String[] segments;
        if (val.contains("|")) {
            segments = val.split("\\|", -1);
        } else {
            segments = val.split(";", -1);
        }

        BigDecimal sum = null;
        for (String seg : segments) {
            String s = seg.trim();
            if (StringUtils.isBlank(s)) continue;
            try {
                BigDecimal num = new BigDecimal(s);
                sum = (sum == null) ? num : sum.add(num);
            } catch (NumberFormatException ignored) {
                // 无法解析的段直接跳过
            }
        }
        return sum;
    }

    /**
     * 当 EnergySource 为单段（不含 |）、且 MaximumNetPowerCombustion、EngineSpeedMaximumNetPower、
     * Maximum30MinutesPower、MaximumNetPowerElectric 四个字段均有值时（单段混动场景，
     * 同一行数据里同时存在燃油和电机功率），在 EnergySource 后面拼接 "|95"，使其变为两段。
     * <p>
     * 拼接后 EnergySource 即被视为多段，后续 prependZeroForElectricPowerFields →
     * sliceJsonMapByOuterRow → filterPowerGroupByEnergySource → appendConsolidatedPowerFields
     * 等现有多段逻辑会按原有规则自动生成两个 PowerGroup（第0段燃油，第1段电机=95）。
     * <p>
     * 必须在 prependZeroForElectricPowerFields 之前调用：本方法依据的是四个字段“拼接前”的
     * 原始单值，一旦 EnergySource 先变成多段，prependZeroForElectricPowerFields 会提前给
     * 电机字段拼接 "0|" 前缀，干扰这里对原始值是否有值的判断。
     * <p>
     * 若 EnergySource 已是多段，或四个字段中有任意一个为空，则不做任何处理，保持原样。
     *
     * @return 是否实际执行了拼接（true=拼接成功，EnergySource 已变为两段；false=未做任何改动）
     */
    private boolean appendEnergySourceSegmentIfHybrid(Map<String, Object> jsonMap) {
        Object energySourceObj = jsonMap.get("EnergySource");
        if (energySourceObj == null) {
            log.info("=== appendEnergySourceSegmentIfHybrid skip: EnergySource为null");
            return false;
        }
        String energySource = energySourceObj.toString().trim();
        if (StringUtils.isBlank(energySource) || energySource.contains("|")) {
            log.info("=== appendEnergySourceSegmentIfHybrid skip: EnergySource={}（空值或已是多段）", energySource);
            return false; // 空值或已是多段，不处理
        }

        for (String fieldName : Arrays.asList("MaximumNetPowerCombustion", "EngineSpeedMaximumNetPower",
                "Maximum30MinutesPower", "MaximumNetPowerElectric")) {
            Object raw = jsonMap.get(fieldName);
            if (raw == null || StringUtils.isBlank(raw.toString())) {
                log.info("=== appendEnergySourceSegmentIfHybrid skip: EnergySource={}，字段{}无值（raw={}）",
                        energySource, fieldName, raw);
                return false; // 四个字段任一为空，不是单段混动场景，保持原样
            }
        }

        jsonMap.put("EnergySource", energySource + "|95");
        log.info("=== appendEnergySourceSegmentIfHybrid 拼接成功，EnergySource变为: {}", jsonMap.get("EnergySource"));
        return true;
    }

    /**
     * 判断 EnergySource 是否为「多段，且第一段不是 95，且从第二段开始全部为 95」的场景。
     * <p>
     * 适用场景举例：
     * <ul>
     *   <li>"10|95"     — 真实单电机混动，1 个 95 段</li>
     *   <li>"10|95|95|95" — 多电机混动，多个 95 段</li>
     * </ul>
     * 命中该场景时，所有 95 段对应的 EnergySourceGroup 只应保留 EnergySource、PowerGroup
     * 两个标签，去掉从燃油段共享复制过来的其它无意义标签（WorkingPrinciple、
     * TestFamilyIdentifiersTable 等）。
     * <p>
     * 若 EnergySource 为单段，或第一段本身就是 95，或存在非 95 的后续段，则返回 false，
     * 保持各 EnergySourceGroup 的完整内容不变。
     */
    private boolean hasRepeatedElectricSegmentsAfterFirst(Map<String, Object> jsonMap) {
        Object raw = jsonMap.get("EnergySource");
        if (raw == null) return false;
        String val = raw.toString().trim();
        if (!val.contains("|")) return false; // 单段，无需处理
        String[] segs = val.split("\\|", -1);
        if (segs.length < 2) return false;
        if ("95".equals(segs[0].trim())) return false; // 首段本身是 95，不属于此场景
        for (int i = 1; i < segs.length; i++) {
            if (!"95".equals(segs[i].trim())) return false; // 存在非 95 的后续段
        }
        return true;
    }

    /**
     * 当 EnergySource 满足「第一段不是 95、且从第二段开始全部为 95」的条件时（包括：
     * 单段混动由 appendEnergySourceSegmentIfHybrid 合成出 "|95" 段，以及真实多段如
     * "10|95"、"10|95|95|95" 等场景），将所有 EnergySource 值为 "95" 的
     * EnergySourceGroup 下除 EnergySource、PowerGroup（及其子标签）外的其余直属子标签
     * 全部移除，只保留 EnergySource 和 PowerGroup 两个标签。
     * <p>
     * 移除原因：这些 Group 是从同一行数据切片而来，WorkingPrinciple、
     * TestFamilyIdentifiersTable 等非功率字段对"电机段"而言没有实际意义，
     * 应当精简以保证生成 XML 的正确性。
     * <p>
     * 若 {@code shouldRestrict} 为 false（EnergySource 为单段，或首段本身是 95，
     * 或后续段存在非 95 值），则直接跳过，各 EnergySourceGroup 保持完整内容。
     * <p>
     * 当 {@code shouldRestrict} 为 true 时，首段（燃油组，EnergySource 非 95）也会被处理：
     * 其余子标签保持原样，但仍需移除其下的 WltpEmissionTestParametersGroup 标签
     * （该字段按约定只保留在某一个 Group 中，避免多段重复）。
     *
     * @param root             已构建完成的 XML 根节点
     * @param shouldRestrict   是否需要执行精简（由调用方根据 EnergySource 形态判断传入）
     */
    private void restrictHybridElectricEnergySourceGroup(Element root, boolean shouldRestrict) {
        if (!shouldRestrict) {
            return; // 不满足精简条件，EnergySourceGroup 保持原有完整内容
        }
        NodeList groupNodes = root.getElementsByTagName("EnergySourceGroup");
        for (int i = 0; i < groupNodes.getLength(); i++) {
            Node groupNode = groupNodes.item(i);
            if (!(groupNode instanceof Element)) continue;
            Element groupEl = (Element) groupNode;
            NodeList children = groupEl.getChildNodes();

            // 找到该 Group 下直属的 EnergySource 子标签，判断其值是否为 "95"（电机段）
            String energySourceValue = null;
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE && "EnergySource".equals(child.getNodeName())) {
                    energySourceValue = child.getTextContent() == null ? "" : child.getTextContent().trim();
                    break;
                }
            }
            if (!"95".equals(energySourceValue)) {
                // 非电机段（首段燃油组）：其余内容保持原样，但仍需移除 WltpEmissionTestParametersGroup 标签
                List<Node> wltpToRemove = new ArrayList<>();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE && "WltpEmissionTestParametersGroup".equals(child.getNodeName())) {
                        wltpToRemove.add(child);
                    }
                }
                for (Node n : wltpToRemove) {
                    groupEl.removeChild(n);
                }
                if (!wltpToRemove.isEmpty()) {
                    log.info("=== restrictHybridElectricEnergySourceGroup 首段燃油组已移除 WltpEmissionTestParametersGroup 标签数={}",
                            wltpToRemove.size());
                }
                continue; // 非电机段（首段燃油组），其余内容保持原样
            }

            // 移除该 Group 下除 EnergySource、PowerGroup 外的所有直属子标签
            List<Node> toRemove = new ArrayList<>();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                String tagName = child.getNodeName();
                if (!"EnergySource".equals(tagName) && !"PowerGroup".equals(tagName) && !"WltpEmissionTestParametersGroup".equals(tagName)) {
                    toRemove.add(child);
                }
            }
            for (Node n : toRemove) {
                groupEl.removeChild(n);
            }
            log.info("=== restrictHybridElectricEnergySourceGroup 已精简95段EnergySourceGroup，仅保留EnergySource与PowerGroup，移除标签数={}",
                    toRemove.size());
        }
    }

    /**
     * TestWltpElectricRangeGroup（纯电续航：WltpEquivalentAllElectricOffVehicleChargingRange 等）
     * 和 TestWltpEnergyConsumptionGroup（纯电能耗：WltpEnergyConsumptionExternallyChargedXxx 等）
     * 描述的是车辆的纯电相关数据，按校验规则的设计意图应挂在每一个 EnergySource=95（电机）的
     * EnergySourceGroup 下；但源 JSON 中这两组字段未按 EnergySource 分段（只有一份单一值），
     * 树构建时默认落在第一个 EnergySourceGroup 下——若该 EnergySourceGroup 恰好是燃油段
     * （EnergySource≠95，如混动场景的首段），就会出现"挂错分组"的问题，导致依赖
     * "EnergySource=95 且本字段有值"的条件必填规则误判为缺失。
     * <p>
     * 本方法在树构建完成后扫描全文档：若这两个标签当前挂在 EnergySource≠95 的
     * EnergySourceGroup 下，则深拷贝一份分别追加到文档中**每一个** EnergySource=95 的
     * EnergySourceGroup 下，并从原（非 95）分组中移除；若文档中不存在任何 95 段，
     * 或这两个标签本就不存在，则不做改动。
     *
     * @param root 已构建完成的 XML 根节点
     */
    private void relocateElectricOnlyTestGroupsToElectricEnergySource(Element root) {
        String[] tagsToRelocate = {"TestWltpElectricRangeGroup", "TestWltpEnergyConsumptionGroup"};

        List<Element> electricGroups = findAllElectricEnergySourceGroups(root);
        if (electricGroups.isEmpty()) {
            return; // 文档中不存在 EnergySource=95 的分组，无迁移目标，保持原样
        }

        NodeList groupNodes = root.getElementsByTagName("EnergySourceGroup");
        // groupNodes 是“活”列表，先收集快照再处理，避免在迁移过程中改变其长度导致遍历错乱
        List<Element> groupSnapshot = new ArrayList<>();
        for (int i = 0; i < groupNodes.getLength(); i++) {
            Node n = groupNodes.item(i);
            if (n instanceof Element) groupSnapshot.add((Element) n);
        }

        int copiedCount = 0;
        for (Element groupEl : groupSnapshot) {
            if (electricGroups.contains(groupEl)) continue; // 95 段本身，跳过

            String energySourceValue = getDirectChildText(groupEl, "EnergySource");
            if ("95".equals(energySourceValue)) continue; // 双重保险，理论上已被上面排除

            for (String tagName : tagsToRelocate) {
                List<Node> toMove = new ArrayList<>();
                NodeList children = groupEl.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                        toMove.add(child);
                    }
                }
                for (Node original : toMove) {
                    // 深拷贝一份，分别追加到每一个 95 段（各段互不影响）
                    for (Element electricGroup : electricGroups) {
                        electricGroup.appendChild(original.cloneNode(true));
                    }
                    // 原（非 95）分组下的这一份不再保留
                    groupEl.removeChild(original);
                    copiedCount++;
                }
            }
        }
        if (copiedCount > 0) {
            log.info("=== relocateElectricOnlyTestGroupsToElectricEnergySource 已将标签复制到全部{}个EnergySource=95分组，处理标签数={}",
                    electricGroups.size(), copiedCount);
        }
    }

    /**
     * 在文档中查找所有 EnergySource 直属子标签值为 "95" 的 EnergySourceGroup。
     * 未找到返回空列表。
     */
    private List<Element> findAllElectricEnergySourceGroups(Element root) {
        List<Element> result = new ArrayList<>();
        NodeList groupNodes = root.getElementsByTagName("EnergySourceGroup");
        for (int i = 0; i < groupNodes.getLength(); i++) {
            Node n = groupNodes.item(i);
            if (!(n instanceof Element)) continue;
            Element groupEl = (Element) n;
            if ("95".equals(getDirectChildText(groupEl, "EnergySource"))) {
                result.add(groupEl);
            }
        }
        return result;
    }

    /**
     * 获取某元素下指定标签名的直属子节点的文本内容（trim 后），未找到返回 null。
     */
    private String getDirectChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                String text = child.getTextContent();
                return text == null ? "" : text.trim();
            }
        }
        return null;
    }

    /**
     * 当 EnergySource 字段值含 | 分隔符（即存在多个能量转换器组）时，
     * 在 Maximum30MinutesPower 和 MaximumNetPowerElectric 的值开头各拼接 "0|"。
     * <p>
     * 背景：EnergySource 第0段为燃油组（如 "10"），其后各段为电机组（如 "95"）。
     * 电机功率字段原始值只覆盖电机组（如 "45|90|175"），段数比 EnergySource 少1，
     * 拼接后变为 "0|45|90|175"，使 sliceJsonMapByOuterRow 按 parentRowIndex 切片时能正确对齐：
     *   index=0（燃油组）→ "0"（filterPowerGroupByEnergySource 再将其清除）
     *   index=1,2,3（电机组）→ 各自的实际值
     * <p>
     * 仅在 EnergySource 含 | 时执行，单段场景不影响。
     */
    private void prependZeroForElectricPowerFields(Map<String, Object> jsonMap) {
        if (!hasRepeatedElectricSegmentsAfterFirst(jsonMap)) {
            return;
        }

        for (String fieldName : Arrays.asList("Maximum30MinutesPower", "MaximumNetPowerElectric")) {
            Object raw = jsonMap.get(fieldName);
            if (raw == null) continue;
            String val = raw.toString().trim();
            if (StringUtils.isBlank(val)) continue;
            // 避免重复拼接（如重复调用场景）
            if (!val.startsWith("0|")) {
                jsonMap.put(fieldName, "0|" + val);
            }
        }
    }

    /**
     * 根据切片后 jsonMap 中的 EnergySource 值，过滤 PowerGroup 字段：
     * <ul>
     *   <li>EnergySource == "95"（电机组）：清除 MaximumNetPowerCombustion、EngineSpeedMaximumNetPower</li>
     *   <li>EnergySource != "95"（燃油组）：清除 Maximum30MinutesPower、MaximumNetPowerElectric</li>
     * </ul>
     * 保证每类能量源只输出对应的 PowerGroup 子字段，避免共用单值字段被错误写入。
     */
    private void filterPowerGroupByEnergySource(Map<String, Object> slicedJsonMap) {
        Object energySourceObj = slicedJsonMap.get("EnergySource");
        if (energySourceObj == null) return;
        String energySource = energySourceObj.toString().trim();
        if ("95".equals(energySource)) {
            // 电机组：只保留电机相关功率字段，清除燃油功率字段
            slicedJsonMap.put("MaximumNetPowerCombustion", "");
            slicedJsonMap.put("EngineSpeedMaximumNetPower", "");
        } else {
            // 燃油组：只保留燃油相关功率字段，清除电机功率字段
            slicedJsonMap.put("Maximum30MinutesPower", "");
            slicedJsonMap.put("MaximumNetPowerElectric", "");
        }
    }

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
                int actualRows = calcGroupActualRows(groupChildren, child.getAttrPath(), dictCodeMap, jsonMap, rows);

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
                // 直接叶子节点：本身不是循环节点（如 CombustionCycle 与 EnergySourceTable 同级），
                // 但值可能仍带 | 或 ; 分隔（如 CombustionCycle="4ST|"，只有第一段是真实值，
                // 其余段是占位空段，对应不适用该字段的能量源）。
                // ★ 修复：不能再用 getValueByRow(rowIndex=-1) 直接判空丢弃整个值——
                //   该写法会让任何含分隔符的直接叶子字段无论第几段有值都被清空，导致标签整体丢失。
                //   改成：含分隔符时取第一个非空段；不含分隔符时按原逻辑取值。
                Object rawLeaf = jsonMap.get(dict.getDictLabel());
                String value;
                if (rawLeaf != null && (rawLeaf.toString().contains("|") || rawLeaf.toString().contains(";"))) {
                    String sep = rawLeaf.toString().contains("|") ? "\\|" : ";";
                    String firstNonBlank = Arrays.stream(rawLeaf.toString().trim().split(sep, -1))
                            .map(String::trim)
                            .filter(StringUtils::isNotBlank)
                            .findFirst()
                            .orElse(null);
                    value = StringUtils.isNotBlank(firstNonBlank) ? firstNonBlank : getValueOrDefault(null, child.getDefaultValue());
                } else {
                    value = getValueByRow(jsonMap, dict.getDictLabel(), child.getDefaultValue(), -1);
                }
                boolean required = child.getIsRequired() != null && child.getIsRequired() == 1;
                if (StringUtils.isNotBlank(value) || required) {
                    addElement(doc, parentElement, dict, value, required);
                }
            }
        }
    }

    private String getValueByRow(Map<String, Object> jsonMap, String dictLabel,
                                 String defaultValue, int rowIndex) {
        return getValueByRow(jsonMap, dictLabel, defaultValue, rowIndex, -1, null);
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
     *   <li>只遍历该 Group 的直接子叶子字段（depth+1），排除更深层结构（如嵌套的 TyreAxleGroup）</li>
     *   <li>若某个字段的值含 | 或 ;，则按分隔符拆分后取非空条目数（忽略末尾空段）</li>
     *   <li>所有字段条目数的最大值即为该 Group 的实际行数</li>
     *   <li>若所有字段均为单值（无分隔符），返回 1</li>
     *   <li>若计算结果超过外层传入的 maxRows，以 maxRows 为上限（防止数据异常撑爆）</li>
     * </ul>
     *
     * @param groupChildren Group 下的所有子节点模板属性
     * @param groupPath     该 Group 节点的 attrPath（用于计算直接子层深度）
     * @param dictCodeMap   字段字典映射
     * @param jsonMap       数据 Map
     * @param maxRows       外层 Table 计算出的最大行数（上限）
     * @return 该 Group 实际应展开的次数
     */
    private int calcGroupActualRows(List<XmlTemplateAttribute> groupChildren,
                                    String groupPath,
                                    Map<String, SysDictData> dictCodeMap,
                                    Map<String, Object> jsonMap,
                                    int maxRows) {
        int actual = 1;
        int groupDepth = groupPath.split("\\.").length;

        // 先尝试只扫直接子叶子（groupDepth+1）
        boolean foundDirectLeaf = false;
        for (XmlTemplateAttribute attr : groupChildren) {
            if (attr.getAttrPath().split("\\.").length != groupDepth + 1) continue;
            String[] parts = attr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || isStructNode(dict)) continue;
            foundDirectLeaf = true;
            Object raw = jsonMap.get(dict.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString().trim();
            if (val.contains("|")) {
                // ★ 修复：| 是外层行分隔符，不能用 countNonTrailingEmpty 截断尾部空段，
                //   否则当某字段恰好只在最后一组才有值（如 "||4142"），
                //   或恰好最后几组该字段为空（如 "BRK||"）时，会把行数错误地砍少，
                //   导致后面的组整体丢失。直接取 split 长度，不做尾部截断。
                actual = Math.max(actual, val.split("\\|", -1).length);
            } else if (val.contains(";")) {
                actual = Math.max(actual, countNonTrailingEmpty(val.split(";", -1)));
            }
        }

        // ★ 修复：若直接子没有叶子（全是结构节点，如 GearRatioGroupItem 包裹了 GearNumber/GearRatio），
        //   则递归扫所有子孙叶子，取含分隔符字段的最大段数
        if (!foundDirectLeaf) {
            for (XmlTemplateAttribute attr : groupChildren) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null || isStructNode(dict)) continue;
                Object raw = jsonMap.get(dict.getDictLabel());
                if (raw == null) continue;
                String val = raw.toString().trim();
                if (val.contains("|")) {
                    // 同上：| 分隔符不截断尾部空段
                    actual = Math.max(actual, val.split("\\|", -1).length);
                } else if (val.contains(";")) {
                    actual = Math.max(actual, countNonTrailingEmpty(val.split(";", -1)));
                }
            }
        }

        return Math.min(actual, maxRows);
    }

    /**
     * 按行取值：
     *  - rowIndex == -1：取完整值（非循环场景）
     *  - rowIndex >= 0：值若含分号则按行分割取第 rowIndex 个，否则所有行共用该值
     */
    /**
     * 按行取值：
     *  - rowIndex == -1：取完整值（非循环场景）
     *  - rowIndex >= 0：值若含分号则按行分割取第 rowIndex 个，否则所有行共用该值
     *
     * ★ 新增：lastRowForbiddenLabels 中的字段在最后一行（rowIndex == totalRows-1）强制返回空
     */
    private String getValueByRow(Map<String, Object> jsonMap, String dictLabel,
                                 String defaultValue, int rowIndex,
                                 int totalRows, Set<String> lastRowForbiddenLabels) {
        Object raw = jsonMap.get(dictLabel);

        // ★ R234c：末行禁填字段 → 最后一行强制返回空（不写入 XML）
        if (rowIndex >= 0
                && totalRows > 1
                && rowIndex == totalRows - 1
                && lastRowForbiddenLabels != null
                && lastRowForbiddenLabels.contains(dictLabel)) {
            log.debug("=== getValueByRow LAST_ROW_FORBIDDEN label={} rowIndex={} totalRows={}",
                    dictLabel, rowIndex, totalRows);
            return "";
        }

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
            return val;
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

    /**
     * 统计数组中有效（非末尾空白）条目数。
     * 例如 ["1","2",""] → 2，["1","","2"] → 3（中间空条目保留）。
     * 用于避免 "1|2|" 被错误计为 3 行。
     */
    private int countNonTrailingEmpty(String[] items) {
        int last = items.length - 1;
        while (last >= 0 && items[last].trim().isEmpty()) {
            last--;
        }
        return last + 1;
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
        if (templates == null || templates.isEmpty()) return null;

        // 查询国家字典，构建 dict_value -> dict_code 映射
        List<SysDictData> countryDictList = remoteDictService.getDictDataByType("country").getData();
        Map<String, String> countryValueToCode = new HashMap<>();
        for (SysDictData d : countryDictList) {
            if (d.getDictValue() != null && d.getDictCode() != null) {
                // key: dict_value(POL), value: dict_code(5442)
                countryValueToCode.put(d.getDictValue(), String.valueOf(d.getDictCode()));
            }
        }

        // 查询能源类型字典，构建 dict_code -> dict_value 映射
        List<SysDictData> energyDictList = remoteDictService.getDictDataByType("energy_type").getData();
        Map<Long, String> dictCodeToValue = new HashMap<>();
        for (SysDictData d : energyDictList) {
            if (d.getDictCode() != null) {
                dictCodeToValue.put(d.getDictCode(), d.getDictValue());
            }
        }

        // 车辆国家 dict_value → dict_code
        String vehicleCountryCode = countryValueToCode.get(vehicle.getCountry());
        String vehicleEnergyValue = resolveEnergyType(vehicle.getJsonMap());

        for (XmlTemplate template : templates) {
            // 1. 必须是最新版本
            if (!Objects.equals(template.getIsLast(), 1)) {
                continue;
            }

            // 2. 匹配车型
            if (!Objects.equals(template.getModelDictCode(), vehicle.getVehicleModel())) {
                continue;
            }

            // 3. 匹配国家（模板 country 是逗号分隔的 dict_code，车辆转换后的 code 任一匹配即可）
            if (StringUtils.isNotBlank(template.getCountry()) && StringUtils.isNotBlank(vehicleCountryCode)) {
                boolean countryMatch = Arrays.stream(template.getCountry().split(","))
                        .map(String::trim)
                        .anyMatch(c -> c.equals(vehicleCountryCode));
                if (!countryMatch) {
                    continue;
                }
            }

            // 4. 匹配能源类型
            if (template.getEnergyType() != null) {
                String templateEnergyValue = dictCodeToValue.get(template.getEnergyType());
                if (!Objects.equals(templateEnergyValue, vehicleEnergyValue)) {
                    continue;
                }
            }

            return template;
        }
        return null;
    }

    /**
     * 从车辆 jsonMap 推断能源类型，返回 dict_value
     *   fuel_oil       = 燃油
     *   pure_electric  = 纯电
     *   hybrid         = NOVC-HEV 混动
     *   HEV            = OVC-HEV
     */
    private String resolveEnergyType(Map<String, Object> jsonMap) {
        String pureElectric = getString(jsonMap, "PureElectricVehicleIndicator");
        String classHybrid  = getString(jsonMap, "ClassHybridVehicle");

        if ("Y".equalsIgnoreCase(pureElectric)) {
            return "pure_electric";
        }
        if ("OVC-HEV".equalsIgnoreCase(classHybrid)) {
            return "HEV";
        }
        if ("NOVC-HEV".equalsIgnoreCase(classHybrid)) {
            return "hybrid";
        }
        return "fuel_oil";
    }

    private String getString(Map<String, Object> jsonMap, String key) {
        Object val = jsonMap.get(key);
        return val == null ? "" : val.toString().trim();
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

    /**
     * ★ 新增：按 SysDictData.dict_value（数据类型名）转换后再写入元素。
     * tagName 由 dict.getDictLabel() 计算，与原先各调用点 sanitizeXmlTagName(dict.getDictLabel())
     * 的写法保持一致；转换失败时原值穿透（不抛异常、不中断生成），失败信息记 warn 日志。
     * <p>
     * 与 {@link #isDataTypeValid} 用的是同一套类型判断标准（Short/Int/Decimal/DateTime/Date/
     * Structure/NULL/String），保证"生成时按类型转换"和"校验时按类型校验"语义一致。
     */
    private void addElement(Document doc, Element parent, SysDictData dict, String textContent) {
        addElement(doc, parent, dict, textContent, false);
    }

    private void addElement(Document doc, Element parent, SysDictData dict, String textContent, boolean required) {
        String tagName = sanitizeXmlTagName(dict.getDictLabel());
        String converted = convertValueByDataType(textContent, dict);
        addElement(doc, parent, tagName, converted, required);
    }

    /**
     * 按数据类型转换 value，转换失败（或类型本身不需要转换）时原值穿透：
     *   String / Structure / NULL（含空白）/ 未识别类型 → 原样返回
     *   Short   → Short.parseShort 后转回字符串
     *   Int     → Integer.parseInt 后转回字符串
     *   Decimal → new BigDecimal(...).toPlainString()（不使用科学计数法）
     *   DateTime/ Date → 只校验"是否能转换为时间"，文本本身不重新格式化，原样返回
     * 任何解析异常（NumberFormatException 等）一律捕获，记 warn 日志后返回原始 value，
     * 不影响 XML 生成流程——这是"原值穿透"的字面含义：转换失败不等于生成失败。
     */
    private String convertValueByDataType(String value, SysDictData dict) {
        if (StringUtils.isBlank(value) || dict == null) return value;
        String dataType = dict.getDictValue();
        if (StringUtils.isBlank(dataType)) return value;
        String trimmed = value.trim();
        if ("N/A".equalsIgnoreCase(trimmed)) return value; // 占位值交给 addElement 现有 N/A 处理逻辑

        try {
            switch (dataType.trim()) {
                case "Short":
                    short s = (short) Math.round(Double.parseDouble(trimmed));
                    return String.valueOf(s);
                case "Int":
                    int n = (int) Math.round(Double.parseDouble(trimmed));
                    return String.valueOf(n);
                case "Decimal":
                    return new BigDecimal(trimmed).toPlainString();
                case "DateTime":
                    if (!tryParseDateTime(trimmed)) {
                        log.warn("数据类型转换失败（DateTime），原值穿透: field={}, value={}",
                                dict.getDictLabel(), value);
                    }
                    return value;
                case "Date":
                    if (!tryParseDate(trimmed)) {
                        log.warn("数据类型转换失败（Date），原值穿透: field={}, value={}",
                                dict.getDictLabel(), value);
                    }
                    return value;
                case "String":
                case "Structure":
                case "NULL":
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            log.warn("数据类型转换失败，原值穿透: field={}, value={}, dataType={}, error={}",
                    dict.getDictLabel(), value, dataType, e.getMessage());
            return value;
        }
    }

    private void addElement(Document doc, Element parent, String tagName, String textContent, boolean required) {
        if (textContent != null && "N/A".equals(textContent.trim())) {
            textContent = "";
        }
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
     * 文本内容为字符串 "null"（不区分大小写）时也视为空。
     */
    private boolean hasNonEmptyDescendantText(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getTextContent() != null ? child.getTextContent().trim() : "";
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
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
     * 生成结束后的兜底校验：模板中 is_required=1 的标签（自身必填，或结构节点的子孙含必填项），
     * 若在最终 XML 对应层级中完全不存在该标签，则补建一个空标签（文本内容留空），插入到
     * 按模板 sort_order 排序后的正确位置；结构节点本身缺失时，同步补建容器并递归补建其下
     * 必填子项，非必填的兄弟节点不做任何补建（与 addElement / removeEmptyStructNodesInternal
     * 的 is_required 语义保持一致：required=1 的标签必须存在，哪怕值为空）。
     * <p>
     * 仅按 tagName 在父节点下逐层匹配模板路径（与全文 buildTagRequiredMap 的约定一致），
     * 不感知具体业务分支。调用方应在 restrictHybridElectricEnergySourceGroup、
     * removeEngineCapacityForElectricEnergySource 等"按业务规则有意删除特定标签"的
     * 后置处理 **之前** 调用本方法，避免补建的标签被业务规则正常移除后又造成误判。
     * <p>
     * 循环容器（如多个 AxleGroup）：父节点下该 tagName 已存在至少一个实例时，对每个
     * 已存在实例分别递归校验其必填子项；一个实例都不存在时，只补建 1 个占位实例
     * （不臆测应有的循环行数）。幂等：重复调用不会产生重复标签。
     */
    private void ensureRequiredTagsExist(Document doc, Element root, String rootAttrPath,
                                         List<XmlTemplateAttribute> attrList,
                                         Map<String, SysDictData> dictCodeMap) {
        ensureRequiredTagsExistRecursive(doc, root, rootAttrPath, attrList, dictCodeMap);
    }

    private void ensureRequiredTagsExistRecursive(Document doc, Element parentElement, String parentPath,
                                                  List<XmlTemplateAttribute> attrList,
                                                  Map<String, SysDictData> dictCodeMap) {
        int parentDepth = parentPath.split("\\.").length;
        List<XmlTemplateAttribute> directChildren = attrList.stream()
                .filter(a -> {
                    String p = a.getAttrPath();
                    return p.startsWith(parentPath + ".") && p.split("\\.").length == parentDepth + 1;
                })
                .sorted(Comparator.comparingInt(a -> a.getSortOrder() != null ? a.getSortOrder() : 0))
                .collect(Collectors.toList());
        if (directChildren.isEmpty()) return;

        // 该层级模板顺序（用于补建标签时定位插入点，与 directChildren 顺序一致）
        List<String> orderedTagNames = directChildren.stream()
                .map(a -> {
                    String[] p = a.getAttrPath().split("\\.");
                    SysDictData d = dictCodeMap.get(p[p.length - 1]);
                    return (d != null && StringUtils.isNotBlank(d.getDictLabel()))
                            ? sanitizeXmlTagName(d.getDictLabel()) : null;
                })
                .collect(Collectors.toList());

        for (XmlTemplateAttribute childAttr : directChildren) {
            String[] parts = childAttr.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null || StringUtils.isBlank(dict.getDictLabel())) continue;
            String tagName = sanitizeXmlTagName(dict.getDictLabel());

            List<Element> existingInstances = getDirectChildren(parentElement, tagName);

            if (isStructNode(dict)) {
                if (!existingInstances.isEmpty()) {
                    // 已有实例（循环场景可能有多个）：逐一递归校验其下必填子项
                    for (Element instance : existingInstances) {
                        ensureRequiredTagsExistRecursive(doc, instance, childAttr.getAttrPath(), attrList, dictCodeMap);
                    }
                } else if (subtreeHasRequiredAttr(childAttr.getAttrPath(), attrList, dictCodeMap)) {
                    // 整个结构标签缺失，且其自身或子孙含必填字段 → 补建占位容器，再递归补建必填子项
                    Element newStruct = createElementWithDefault(doc, tagName, childAttr.getDefaultValue());
                    insertAtTemplateOrder(parentElement, newStruct, tagName, orderedTagNames);
                    ensureRequiredTagsExistRecursive(doc, newStruct, childAttr.getAttrPath(), attrList, dictCodeMap);
                }
                // 非必填且缺失：不补建，维持"无值不生成"的既有约定
            } else {
                boolean required = childAttr.getIsRequired() != null && childAttr.getIsRequired() == 1;
                if (existingInstances.isEmpty() && required) {
                    Element leaf = createElementWithDefault(doc, tagName, null);
                    insertAtTemplateOrder(parentElement, leaf, tagName, orderedTagNames);
                }
            }
        }
    }

    /**
     * 判断模板路径 path 本身或其任意子孙节点是否含 is_required=1（且该路径在字典中
     * 确有对应标签定义，dictLabel 非空）。
     */
    private boolean subtreeHasRequiredAttr(String path, List<XmlTemplateAttribute> attrList,
                                           Map<String, SysDictData> dictCodeMap) {
        return attrList.stream().anyMatch(a -> {
            String p = a.getAttrPath();
            if (!p.equals(path) && !p.startsWith(path + ".")) return false;
            if (a.getIsRequired() == null || a.getIsRequired() != 1) return false;
            String[] parts = p.split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            return d != null && StringUtils.isNotBlank(d.getDictLabel());
        });
    }

    /**
     * 取父节点下所有 tagName 匹配的直接子元素，保留 DOM 中的原始顺序。
     */
    private List<Element> getDirectChildren(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && tagName.equals(((Element) n).getTagName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    /**
     * 按模板定义顺序（orderedTagNames）将新建标签插入父节点下的正确位置：
     * 找到第一个"模板顺序排在 newChild 之后"的已存在兄弟节点，插入到它之前；
     * 找不到（newChild 在模板顺序中最靠后，或没有任何可比较的兄弟）则追加到末尾。
     */
    private void insertAtTemplateOrder(Element parentElement, Element newChild, String tagName,
                                       List<String> orderedTagNames) {
        int targetIndex = orderedTagNames.indexOf(tagName);
        Node refNode = null;
        if (targetIndex >= 0) {
            NodeList existingChildren = parentElement.getChildNodes();
            for (int i = 0; i < existingChildren.getLength(); i++) {
                Node n = existingChildren.item(i);
                if (!(n instanceof Element)) continue;
                int idx = orderedTagNames.indexOf(((Element) n).getTagName());
                if (idx > targetIndex) {
                    refNode = n;
                    break;
                }
            }
        }
        parentElement.insertBefore(newChild, refNode);
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
     * 构建校验结果字段名 → List<[tagLabel, rule, rangeRule]> 映射。
     * <p>
     * 规则引擎返回的 fieldName 可能是 keyMap 值，也可能是 dictLabel（XML 标签名）。
     * 为两种 key 都建立索引，确保 enrichAndMerge 无论收到哪种 fieldName 都能命中。
     * <p>
     * ★ 同一 keyMap 可能对应多个字段（如 BodyworkTypeTrailer / BrakedTypeTrail 共享 18.4.），
     *   改用 List 存储，防止 HashMap 覆盖导致其中一个字段丢失。
     */
    private Map<String, List<String[]>> buildKeyMapMeta(Map<String, SysDictData> dictCodeMap) {
        Map<String, List<String[]>> keyMapMeta = new HashMap<>();
        for (SysDictData dict : dictCodeMap.values()) {
            if (StringUtils.isBlank(dict.getKeyMap())) continue;
            if (isStructNode(dict)) continue;
            String tagLabel  = sanitizeXmlTagName(dict.getDictLabel());
            String rule      = StringUtils.defaultString(dict.getRule());
            String rangeRule = StringUtils.defaultString(dict.getRangeRule());
            String[] meta    = new String[]{tagLabel, rule, rangeRule};
            // 以 keyMap 为 key 索引（规则引擎原始返回值）
            keyMapMeta.computeIfAbsent(dict.getKeyMap(), k -> new ArrayList<>()).add(meta);
            // ★ 同时以 dictLabel 为 key 索引（baseJsonMap 改用 dictLabel 后规则引擎可能直接返回 dictLabel）
            //   若 dictLabel 与 keyMap 相同则已写入，putIfAbsent 不会重复添加
            if (!dict.getKeyMap().equals(tagLabel)) {
                keyMapMeta.computeIfAbsent(tagLabel, k -> new ArrayList<>()).add(meta);
            }
        }
        return keyMapMeta;
    }

    /**
     * 补充 violation 消息，并将 report 合并到 merged 中。
     * <p>
     * ★ 修复：同一 keyMap 对应多个字段（如 NumberOfAxles / NumberOfWheels 共享同一 keyMap）时，
     *   不再合并为 "A / B" 一条，而是为每个字段各自生成一条独立的 FieldValidationResult，
     *   确保每个字段有独立的 fieldName、rule 描述和 violation 消息。
     */
    private ValidationReport enrichAndMerge(ValidationReport merged,
                                            ValidationReport report,
                                            Map<String, List<String[]>> keyMapMeta) {
        if (report == null) return merged;

        if (report.getFieldResults() != null) {
            // 收集需要拆分的额外条目，避免在遍历中修改列表
            List<FieldValidationResult> extraResults = new ArrayList<>();

            for (FieldValidationResult fr : report.getFieldResults()) {
                List<String[]> metaList = keyMapMeta.get(fr.getFieldName());
                // 全量字典里也找不到（如 STRUCTURE 等虚拟字段），保持原 fieldName 不变
                if (metaList == null || metaList.isEmpty()) continue;

                if (metaList.size() == 1) {
                    // 单字段：直接在原条目上设置
                    String tagLabel  = metaList.get(0)[0];
                    String rule      = metaList.get(0)[1];
                    String rangeRule = metaList.get(0)[2];
                    fr.setFieldName(tagLabel);
                    if (!fr.isValid() && fr.getViolations() != null) {
                        fillViolationMessages(fr, tagLabel, rule, rangeRule);
                    }
                } else {
                    // ★ 多字段共享 keyMap：为每个字段单独生成一条 FieldValidationResult
                    // 先处理第一个字段，复用原条目
                    String[] first = metaList.get(0);
                    fr.setFieldName(first[0]);
                    if (!fr.isValid() && fr.getViolations() != null) {
                        fillViolationMessages(fr, first[0], first[1], first[2]);
                    }
                    // 从第二个字段起，克隆原条目并追加到 extraResults
                    for (int idx = 1; idx < metaList.size(); idx++) {
                        String[] meta = metaList.get(idx);
                        FieldValidationResult cloned = cloneFieldResult(fr, meta[0]);
                        if (!cloned.isValid() && cloned.getViolations() != null) {
                            fillViolationMessages(cloned, meta[0], meta[1], meta[2]);
                        }
                        extraResults.add(cloned);
                    }
                }
            }
            report.getFieldResults().addAll(extraResults);
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
     * 为 FieldValidationResult 的每个 violation 补充双语消息。
     */
    private void fillViolationMessages(FieldValidationResult fr, String tagLabel,
                                       String rule, String rangeRule) {
        String ruleDescEn = buildRuleDesc(rule, rangeRule, false);
        String ruleDescZh = buildRuleDesc(rule, rangeRule, true);
        for (RuleViolation v : fr.getViolations()) {
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

    /**
     * 浅克隆一个 FieldValidationResult，替换 fieldName，violations 做深拷贝以免消息互相覆盖。
     */
    private FieldValidationResult cloneFieldResult(FieldValidationResult src, String newFieldName) {
        FieldValidationResult cloned = new FieldValidationResult();
        cloned.setFieldName(newFieldName);
        cloned.setValue(src.getValue());
        cloned.setValid(src.isValid());
        if (src.getViolations() != null) {
            List<RuleViolation> clonedViolations = new ArrayList<>();
            for (RuleViolation v : src.getViolations()) {
                RuleViolation cv = new RuleViolation();
                cv.setRuleId(v.getRuleId());
                cv.setRawRule(v.getRawRule());
                // messageEn/messageZh 留空，由 fillViolationMessages 重新生成
                clonedViolations.add(cv);
            }
            cloned.setViolations(clonedViolations);
        }
        return cloned;
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
        if (vehicle.getIssueDate() != null) {
            params.put("issueDate", com.ruoyi.common.core.utils.DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", vehicle.getIssueDate()));
        }
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
     * 首台车（firstTemplateFlag=1）：无需 uploadAffirm=1，直接放行
     * 非首台车：需要该模版下首台车已确认（uploadAffirm=1）
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

    /**
     * 将生成阶段硬编码注入的循环字段分号值补充进 jsonMap，
     * 使校验阶段的 resolveLoopContainerPaths 能识别 LocationMarkingsGroup 为循环节点。
     * <p>
     * MethodAttachmentStatutoryPlate 的值优先从 jsonMap 中读取；
     * 若 jsonMap 中没有，则从模板属性的 defaultValue 中查找（生成阶段也走此兜底逻辑）。
     */
    private void enrichHardcodedLoopFields(Map<String, Object> jsonMap, VehicleInfo vehicle,
                                           List<XmlTemplateAttribute> attrList,
                                           Map<String, SysDictData> dictCodeMap) {
        String methodAttach = Optional.ofNullable(jsonMap.get("MethodAttachmentStatutoryPlate"))
                .map(Object::toString).filter(StringUtils::isNotBlank).orElse(null);

        // jsonMap 中没有时，从模板 defaultValue 兜底读取（与生成逻辑保持一致）
        if (StringUtils.isBlank(methodAttach)) {
            for (XmlTemplateAttribute attr : attrList) {
                String[] parts = attr.getAttrPath().split("\\.");
                SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
                if (dict == null) continue;
                if ("MethodAttachmentStatutoryPlate".equals(sanitizeXmlTagName(dict.getDictLabel()))
                        && StringUtils.isNotBlank(attr.getDefaultValue())) {
                    methodAttach = attr.getDefaultValue();
                    break;
                }
            }
        }

        if (StringUtils.isBlank(methodAttach)) return;

        applyLocationMarkings(jsonMap, methodAttach);
    }

    /**
     * 根据 MethodAttachmentStatutoryPlate 的值向 jsonMap 写入对应的 LocationMarkings 循环字段。
     * <p>
     * 生成阶段（generateXmlFromDatabase）和校验阶段（enrichHardcodedLoopFields）共用此逻辑，
     * 保证两处行为完全一致：使用 put 强制覆盖，确保含 ; 的值能被 resolveLoopContainerPaths
     * 正确识别为循环节点，避免校验时报"非循环节点重复出现"的错误。
     *
     * @param jsonMap      目标 Map，字段将直接写入（覆盖已有值）
     * @param methodAttach MethodAttachmentStatutoryPlate 的实际值，如 "A1"、"B2"
     */
    private void applyLocationMarkings(Map<String, Object> jsonMap, String methodAttach) {
        switch (methodAttach) {
            case "B2":
                jsonMap.put("LocationMarkingsSubject",               "STAT;VIN");
                jsonMap.put("LocationMarkingsVehiclePart",           "BPILR;PASCT");
                jsonMap.put("LocationMarkingsVehiclePartSide",       "RIGHTSIDE;RIGHTSIDE");
                jsonMap.put("LocationMarkingsVehiclePartSideSection", ";FRONT");
                break;
            case "A0":
                jsonMap.remove("MethodAttachmentStatutoryPlate");
                jsonMap.put("MethodAttachmentStatutoryPlate",  "A1");
                jsonMap.put("LocationMarkingsSubject",         "STAT;VIN");
                jsonMap.put("LocationMarkingsVehiclePart",     "BPILR;ENGCT");
                jsonMap.put("LocationMarkingsVehiclePartSide", "RIGHTSIDE;RIGHTSIDE");
                break;
            default:
                break;
        }
    }

    private static final Set<String> LAST_ROW_FORBIDDEN_LABELS = new HashSet<>(Arrays.asList(
            "AxleSpacing"   // R234c: Forbidden for the last axle
            // 如有新增末行禁填字段，在此追加
    ));

    /**
     * 计算当前 Group（rootAttrPath 对应节点）的总展开行数。
     * 用于末行判断，避免每次重复扫描时重新计算。
     */
    private int calcTotalRowsForGroup(List<XmlTemplateAttribute> attrList,
                                      Map<String, SysDictData> dictCodeMap,
                                      Map<String, Object> jsonMap,
                                      String rootAttrPath) {
        int max = 1;
        int rootDepth = rootAttrPath.split("\\.").length;
        for (XmlTemplateAttribute a : attrList) {
            if (a.getAttrPath().split("\\.").length != rootDepth + 1) continue;
            String[] parts = a.getAttrPath().split("\\.");
            SysDictData d = dictCodeMap.get(parts[parts.length - 1]);
            if (d == null || isStructNode(d)) continue;
            Object raw = jsonMap.get(d.getDictLabel());
            if (raw == null) continue;
            String val = raw.toString().trim();
            // ★ 不截断尾部空段，直接用 split 长度，确保 totalOuterRows 与外层实际组数一致
            if (val.contains("|")) {
                max = Math.max(max, val.split("\\|", -1).length);
            } else if (val.contains(";")) {
                max = Math.max(max, val.split(";", -1).length);
            }
        }
        return max;
    }
}