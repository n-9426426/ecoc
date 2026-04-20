package com.ruoyi.vehicle.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteDictService;
import com.ruoyi.system.api.RemoteFileService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.vehicle.domain.VehicleInfo;
import com.ruoyi.vehicle.domain.XmlFile;
import com.ruoyi.vehicle.domain.XmlVersion;
import com.ruoyi.vehicle.domain.vo.DiffLineVO;
import com.ruoyi.vehicle.domain.vo.DiffResultVO;
import com.ruoyi.vehicle.mapper.XmlFileMapper;
import com.ruoyi.vehicle.mapper.XmlVersionMapper;
import com.ruoyi.vehicle.service.IVehicleInfoService;
import com.ruoyi.vehicle.service.IXmlFileService;
import com.ruoyi.vehicle.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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

            // 获取项目根目录，拼接相对路径
            String projectPath = System.getProperty("user.dir");
            String fullPath = projectPath + xmlFile.getFilePath();

            File file = new File(fullPath);
            if (!file.exists()) {
                throw new RuntimeException(StringUtils.format(remoteTranslateService.translate("common.file.not.found", null), fullPath));
            }

            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("预览XML文件失败", e);
            throw new RuntimeException(StringUtils.format(remoteTranslateService.translate("common.file.preview.failed", null), e.getMessage()));
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

    /**
     * 校验XML文件
     */
    @Override
    public boolean validateXml(Long id) {
        try {
            XmlFile xmlFile = xmlFileMapper.selectXmlFileById(id);
            if (xmlFile == null) {
                return false;
            }
            File file = new File(xmlFile.getFilePath());
            // todo 校验

            return true;
        } catch (Exception e) {
            log.error("校验XML文件失败", e);
            return false;
        }
    }

    /**
     * 从数据库生成XML文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateXmlFromDatabase(Long vehicleId) {
        try {
            // 查询车辆信息
            VehicleInfo vehicle = vehicleInfoService.selectVehicleInfoById(vehicleId);
            if (vehicle == null) {
                throw new RuntimeException("车辆信息不存在");
            }

            // 创建XML文档
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            String xmlVersion = xmlFileMapper.selectVersionByFileName("vehicle_" + vehicle.getVin());
            if (xmlVersion == null) {
                xmlVersion = "1.0";
            } else {
                xmlVersion = String.valueOf(new BigDecimal(xmlVersion).add(new BigDecimal(1)));
            }

            // todo 生成xml文件内容树

            // 转换为字符串
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            String xmlContent = writer.toString();

            MultipartFile multipartFile = FileUtils.createMultipartFile(
                    xmlContent,
                    "generated.xml",      // 文件名
                    "application/xml"     // Content-Type
            );

            String filePath = remoteFileService.upload(multipartFile).getData().getUrl();
            String newFileName = "vehicle_" + vehicle.getVin() + "_" + System.currentTimeMillis() + ".xml";

            // 保存到数据库
            XmlFile xmlFile = new XmlFile();
            xmlFile.setFileName(newFileName);
            xmlFile.setFilePath(filePath);
            xmlFile.setFileSize((long) xmlContent.getBytes(StandardCharsets.UTF_8).length);
            xmlFile.setFileLevel("1");
            xmlFile.setVersion(xmlVersion);
            xmlFile.setIsLatest(true);
            xmlFile.setStatus("0");
            xmlFile.setDeleted(0);
            xmlFile.setCreateBy(SecurityUtils.getUsername());
            xmlFile.setCreateTime(new Date());
            xmlFile.setRemark("由车辆VIN: " + vehicle.getVin() + " 生成XML, 版本: " + xmlVersion);
            xmlFileMapper.insertXmlFile(xmlFile);

            // 保存版本记录
            XmlVersion version = new XmlVersion();
            version.setFileId(xmlFile.getId());
            version.setVersion(xmlVersion);
            version.setFilePath(filePath);
            version.setChangeType("生成");
            version.setChangeDesc("由车辆VIN: " + vehicle.getVin() + " 生成XML, 版本: " + xmlVersion);
            version.setCreateBy(SecurityUtils.getUsername());
            version.setCreateTime(new Date());
            xmlVersionMapper.insertXmlVersion(version);

            xmlFileMapper.updateIsLatestToFalse("vehicle_" + vehicle.getVin());

            log.info("成功生成XML文件: {}", newFileName);
            return xmlContent;
        } catch (Exception e) {
            log.error("生成XML文件失败", e);
            throw new RuntimeException("生成XML失败: " + e.getMessage());
        }
    }

    /**
     * 添加XML元素
     */
    private void addElement(Document doc, Element parent, String tagName, String textContent) {
        Element element = doc.createElement(tagName);
        element.setTextContent(textContent != null ? textContent : "");
        parent.appendChild(element);
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
}