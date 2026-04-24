package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteFileService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.domain.SysDictData;
import com.ruoyi.vehicle.domain.*;
import com.ruoyi.vehicle.domain.vo.DiffLineVO;
import com.ruoyi.vehicle.domain.vo.DiffResultVO;
import com.ruoyi.vehicle.mapper.*;
import com.ruoyi.vehicle.service.IVehicleInfoService;
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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.math.BigDecimal;
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
        String vin = oldFileName.substring(oldFileName.indexOf("_") + 1, oldFileName.lastIndexOf("_"));

        // 4. 计算新版本号（当前版本 +1）
        String oldVersion = xmlFileMapper.selectVersionByFileName("vehicle_" + vin);
        String newVersion = String.valueOf(new BigDecimal(oldVersion).add(new BigDecimal(1)));

        // 5. 生成新文件名和路径
        String newFileName = "vehicle_" + vin + "_" + System.currentTimeMillis() + ".xml";
        // 获取项目根路径下的 /xml 目录
        String xmlDir = "xml" + File.separator;
        File dir = new File(xmlDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String newFilePath = File.separator + xmlDir + newFileName;

        // 6. 将 content 写入本地文件
        try (Writer writer = new OutputStreamWriter(
                Files.newOutputStream(Paths.get(System.getProperty("user.dir") + newFilePath)), StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
        } catch (IOException e) {
            throw new ServiceException("XML文件写入失败: " + e.getMessage());
        }

        // 7. 计算文件大小
        long fileSize = new File(newFilePath).length();

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
            xmlFile.setFileName(file.getOriginalFilename());
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
        String query = "vehicle_" + xmlFile.getFileName().substring(xmlFile.getFileName().indexOf("_") + 1, xmlFile.getFileName().lastIndexOf("_"));
        return xmlFileMapper.selectXmlFileVersions(query);
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
            String oldContent = new String(Files.readAllBytes(
                    new File(projectPath + oldFile.getFilePath()).toPath()), StandardCharsets.UTF_8);
            String newContent = new String(Files.readAllBytes(
                    new File(projectPath + newFile.getFilePath()).toPath()), StandardCharsets.UTF_8);

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

        // 2. 遍历 filePaths 删除本地文件
        String projectPath = System.getProperty("user.dir");
        for (String filePath : filePaths) {
            try {
                Path absolutePath = Paths.get(projectPath, filePath);
                if (Files.exists(absolutePath)) {
                    Files.delete(absolutePath);
                    log.info("成功删除文件: {}", absolutePath);
                } else {
                    log.warn("文件不存在，无法删除: {}", absolutePath);
                }
            } catch (Exception e) {
                // 可以选择抛异常回滚，也可以记录日志继续
                log.error("删除文件失败: " + filePath, e);
                throw new RuntimeException("删除文件失败：" + filePath, e);
            }
        }
        xmlVersionMapper.deleteXmlVersionByFileId(xmlIds);
        return xmlFileMapper.permanentlyDeleteXmlByIds(xmlIds);
    }

    /**
     * 校验XML文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean validateXml(Long id) {
        try {
            XmlFile xmlFile = xmlFileMapper.selectXmlFileById(id);
            if (xmlFile == null) {
                return false;
            }
            File file = new File(xmlFile.getFilePath());
            // todo 校验

            boolean validateResult = true;
            xmlFile.setValidateResult(1);
            xmlFileMapper.updateXmlFile(xmlFile);
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(xmlFile.getVin());
            vehicleLifecycle.setOperate("3");
            vehicleLifecycle.setResult(validateResult ? 0 : 1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);
            return validateResult;
        } catch (Exception e) {
            log.error("校验XML文件失败", e);
            return false;
        }
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
            // 1. 查询车辆信息
            VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoById(vehicleId);
            if (vehicle == null) {
                throw new RuntimeException("车辆信息不存在");
            }
            Map<String, Object> jsonMap = vehicle.getJsonMap();
            if (jsonMap == null) {
                jsonMap = new HashMap<>();
            }

            // 2.匹配模板
            XmlTemplate xmlTemplate = matchTemplate(vehicle);
            if (xmlTemplate == null) {
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
                throw new ServiceException("模板无属性定义，无法生成XML");
            }

            // 5. 单根节点校验
            List<XmlTemplateAttribute> topLevelAttrs = attrList.stream()
                    .filter(a -> a.getAttrPath() != null && a.getAttrPath().split("\\.").length == 1)
                    .collect(Collectors.toList());
            if (topLevelAttrs.isEmpty()) {
                throw new ServiceException("模板无顶层节点，XML必须有唯一根节点");
            }
            if (topLevelAttrs.size() > 1) {
                throw new ServiceException("模板存在多个顶层节点，XML 不允许多根节点");
            }

            // 6. 按路径深度排序（确保父节点先于子节点处理）
            attrList.sort(Comparator.comparingInt(a -> a.getAttrPath().split("\\.").length));

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
            xmlVersion = StringUtils.isBlank(xmlVersion) ? "1.0"
                    : String.valueOf(new BigDecimal(xmlVersion).add(new BigDecimal(1)));

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
            xmlFileMapper.insertXmlFile(xmlFile);

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
            xmlFileMapper.updateIsLatestToFalse("vehicle_" + vehicle.getVin());
            vehicle.setUploadStatus(1);
            vehicleInfoService.updateVehicleInfo(vehicle);

            // 20. 记录生命周期
            VehicleLifecycle vehicleLifecycle = new VehicleLifecycle();
            vehicleLifecycle.setTime(new Date());
            vehicleLifecycle.setVin(vehicle.getVin());
            vehicleLifecycle.setOperate("3");
            vehicleLifecycle.setResult(1);
            vehicleLifecycleMapper.insert(vehicleLifecycle);

            log.info("成功生成XML文件，VIN={}", vehicle.getVin());
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
     * 上级循环：每个前缀生成一套完整的容器结构
     * 示例：{"10.0.1":"HEV1:HEV1;HEV2:HEV2","11.1":"HEV1:北京;HEV2:柏林"}
     * → 生成两个ManufacturerTable 容器，分别对应 HEV1 和 HEV2
     */
    private void buildParentLevelLoop(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                      Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                      Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                      LoopDetectionResult loopResult, String rootAttrPath) {

        String loopContainerPath = loopResult.getLoopContainerPath();

        // 1. 构建到循环容器父节点为止（不含同级节点）
        buildTreeUpToPath(doc, root, attrList, dictCodeMap, jsonMap,
                pathNodeMap, structNodePaths, loopContainerPath, rootAttrPath);

        // 2. 获取父元素
        String parentPath = getParentPath(loopContainerPath);
        Element parentElement = pathNodeMap.get(parentPath);
        if (parentElement == null) {
            parentElement = root;
        }

        // 3. 为每个前缀生成循环容器
        List<String> groupKeys = loopResult.getGroupKeys();
        for (int i = 0; i < groupKeys.size(); i++) {
            generateParentLoopContainer(doc, parentElement, loopContainerPath, attrList,
                    dictCodeMap, jsonMap, structNodePaths, groupKeys.get(i), i, pathNodeMap);
        }

        // ✅ 4. 补充同级非循环节点（如 AllowedParameterValuesMultistageGroup）
        addSiblingNodesAfterLoop(doc, parentElement, loopContainerPath, attrList,
                dictCodeMap, jsonMap, pathNodeMap);
    }

    /**
     * 生成单个父级循环容器（修正版）
     */
    private void generateParentLoopContainer(Document doc, Element parentElement, String loopContainerPath,
                                             List<XmlTemplateAttribute> attrList,
                                             Map<String, SysDictData> dictCodeMap,
                                             Map<String, Object> jsonMap,
                                             Set<String> structNodePaths,
                                             String prefix,
                                             int prefixIndex,
                                             Map<String, Element> pathNodeMap) {

        // 找到循环容器的定义
        String[] containerParts = loopContainerPath.split("\\.");
        SysDictData containerDict = dictCodeMap.get(containerParts[containerParts.length - 1]);
        if (containerDict == null) return;

        // 创建容器元素（如 ManufacturerTable）
        Element container = doc.createElement(sanitizeXmlTagName(containerDict.getDictLabel()));
        parentElement.appendChild(container);

        // 递归构建容器内的所有节点（按前缀匹配值，前缀找不到时按位置回退）
        buildContainerByPrefix(doc, container, loopContainerPath, attrList, dictCodeMap, jsonMap, prefix, prefixIndex);
    }

    /**
     * 按前缀递归填充容器内容（上级循环专用）
     * prefixIndex：该前缀在 groupKeys 中的位置，用于前缀匹配失败时按位置回退取值
     */
    private void buildContainerByPrefix(Document doc, Element container, String containerPath,
                                        List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap,
                                        Map<String, Object> jsonMap,
                                        String prefix,
                                        int prefixIndex) {
        // 找到当前容器的直接子节点
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
                // 结构节点 → 递归
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                // ★ loopGenerated=true：循环动态生成的结构节点，前缀匹配全部失败时允许被清除
                structElement.setUserData("loopGenerated", Boolean.TRUE, null);
                container.appendChild(structElement);
                buildContainerByPrefix(doc, structElement, child.getAttrPath(),
                        attrList, dictCodeMap, jsonMap, prefix, prefixIndex);
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                // 叶子节点 → 按前缀提取值，找不到时按位置回退
                String value = extractValueByPrefix(jsonMap, dict.getKeyMap(), prefix, prefixIndex);
                if (StringUtils.isNotBlank(value)) {
                    addElement(doc, container, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    // =====================================================
    // 同级循环（SIBLING_LEVEL）
    // =====================================================

    /**
     * 同级循环：在同一个容器内，子结构循环多次
     * 示例：{"10.0.1":"HEV1","11.1":"北京;柏林","12":"CN;DE"}
     * → 生成一个 ManufacturerTable，内含两个 ManufacturerGroup
     */
    private void buildSiblingLevelLoop(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                       Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                       Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                       LoopDetectionResult loopResult, String rootAttrPath) {

        String loopContainerPath = loopResult.getLoopContainerPath();

        // 1. 构建循环容器之前的节点（不含循环容器本身）
        buildTreeUpToPath(doc, root, attrList, dictCodeMap, jsonMap,
                pathNodeMap, structNodePaths, loopContainerPath, rootAttrPath);

        // 2. 创建循环容器节点
        String[] containerParts = loopContainerPath.split("\\.");
        SysDictData containerDict = dictCodeMap.get(containerParts[containerParts.length - 1]);
        if (containerDict == null) {
            log.warn("循环容器节点字典数据不存在，path={}", loopContainerPath);
            return;
        }

        String parentOfContainerPath = getParentPath(loopContainerPath);
        Element parentElement = pathNodeMap.get(parentOfContainerPath);
        if (parentElement == null) {
            parentElement = root;
        }

        Element container = doc.createElement(sanitizeXmlTagName(containerDict.getDictLabel()));
        parentElement.appendChild(container);
        pathNodeMap.put(loopContainerPath, container);

        // 3. 找到容器下需要循环的子结构节点（包含循环触发字段的直接父结构）
        String triggerPath = loopResult.getTriggerAttr().getAttrPath();
        String loopStructPath = findLoopStructPath(loopContainerPath, triggerPath, structNodePaths);

        // 4. 按maxRows 循环生成子结构（ManufacturerGroup × N）
        if (loopStructPath != null) {
            String[] structParts = loopStructPath.split("\\.");
            SysDictData structDict = dictCodeMap.get(structParts[structParts.length - 1]);
            if (structDict == null) return;

            for (int i = 0; i < loopResult.getMaxRows(); i++) {
                Element structElement = doc.createElement(sanitizeXmlTagName(structDict.getDictLabel()));
                // ★ loopGenerated=true：循环动态生成，索引取值全部为空时允许被清除
                structElement.setUserData("loopGenerated", Boolean.TRUE, null);
                container.appendChild(structElement);
                // 填充该子结构下的所有叶子字段（按索引取值）
                fillStructByIndex(doc, structElement, loopStructPath, attrList, dictCodeMap, jsonMap, i);
            }
        }

        // ★修复：非循环同级节点（如 CategoryHybridElectricVehicle）在循环完成后追加，
        //   保证 XML 顺序为：ManufacturerGroup... → CategoryHybridElectricVehicle
        addNonLoopSiblingNodes(doc, container, loopContainerPath, loopStructPath,
                attrList, dictCodeMap, jsonMap);
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
            if (StringUtils.isNotBlank(value)) {
                addElement(doc, container, sanitizeXmlTagName(dict.getDictLabel()), value);
            }
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
            if (StringUtils.isNotBlank(value)) {
                addElement(doc, structElement, sanitizeXmlTagName(dict.getDictLabel()), value);
            }
        }
    }

    // =====================================================
    // 公共辅助：构建循环前的树
    // =====================================================

    /**
     * 构建到指定路径之前的所有节点（不含targetPath 及其子节点）
     */
    /**
     * 构建到指定路径的父节点为止（不含 targetPath 及其同级节点）
     */
    private void buildTreeUpToPath(Document doc, Element root, List<XmlTemplateAttribute> attrList,
                                   Map<String, SysDictData> dictCodeMap, Map<String, Object> jsonMap,
                                   Map<String, Element> pathNodeMap, Set<String> structNodePaths,
                                   String targetPath, String rootAttrPath) {

        // 计算 targetPath 的父路径
        String targetParentPath = getParentPath(targetPath);
        int targetDepth = targetPath.split("\\.").length;

        for (XmlTemplateAttribute attr : attrList) {
            String attrPath = attr.getAttrPath();
            if (attrPath.equals(rootAttrPath)) continue;

            // 跳过 targetPath 及其所有子节点
            if (attrPath.equals(targetPath) || attrPath.startsWith(targetPath + ".")) continue;

            // ✅ 关键修正：跳过与 targetPath 同级的所有节点
            if (attrPath.split("\\.").length == targetDepth &&
                    attrPath.startsWith(targetParentPath + ".")) {
                continue;
            }

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
                if (StringUtils.isNotBlank(value) && !value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
            }
        }
    }

    /**
     * 补充循环容器的同级节点（在循环完成后添加）
     */
    private void addSiblingNodesAfterLoop(Document doc, Element parentElement, String loopContainerPath,
                                          List<XmlTemplateAttribute> attrList,
                                          Map<String, SysDictData> dictCodeMap,
                                          Map<String, Object> jsonMap,
                                          Map<String, Element> pathNodeMap) {

        String parentPath = getParentPath(loopContainerPath);
        int loopDepth = loopContainerPath.split("\\.").length;

        // 找到所有与 loopContainerPath 同级的节点
        List<XmlTemplateAttribute> siblings = attrList.stream()
                .filter(a -> {
                    String path = a.getAttrPath();
                    if (path.equals(loopContainerPath)) return false; // 排除循环容器本身
                    if (!path.startsWith(parentPath + ".")) return false;
                    return path.split("\\.").length == loopDepth;
                })
                .sorted(Comparator.comparing(XmlTemplateAttribute::getAttrPath))
                .collect(Collectors.toList());

        for (XmlTemplateAttribute sibling : siblings) {
            String[] parts = sibling.getAttrPath().split("\\.");
            SysDictData dict = dictCodeMap.get(parts[parts.length - 1]);
            if (dict == null) continue;

            if ("NULL".equalsIgnoreCase(dict.getDictValue())) {
                // 结构节点（如 AllowedParameterValuesMultistageGroup）
                Element structElement = doc.createElement(sanitizeXmlTagName(dict.getDictLabel()));
                parentElement.appendChild(structElement);
                pathNodeMap.put(sibling.getAttrPath(), structElement);
            } else if (StringUtils.isNotBlank(dict.getKeyMap())) {
                // 叶子节点
                Object raw = jsonMap.get(dict.getKeyMap());
                String value = raw != null ? raw.toString() : "";
                if (StringUtils.isNotBlank(value) && !value.contains(";")) {
                    addElement(doc, parentElement, sanitizeXmlTagName(dict.getDictLabel()), value);
                }
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
     */
    private void removeEmptyStructNodes(Element element, List<XmlTemplateAttribute> attrList,
                                        Map<String, SysDictData> dictCodeMap) {
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element childElement = (Element) child;
                // 先递归处理子节点
                removeEmptyStructNodes(childElement, attrList, dictCodeMap);
                // 只移除"循环动态生成且为空"的结构节点，模板静态节点保留
                Boolean loopGenerated = (Boolean) childElement.getUserData("loopGenerated");
                if (Boolean.TRUE.equals(loopGenerated) && childElement.getChildNodes().getLength() == 0) {
                    element.removeChild(childElement);
                    log.debug("移除空循环结构节点:<{}>", childElement.getTagName());
                }
            }
        }
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