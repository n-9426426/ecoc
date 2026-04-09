package com.ruoyi.xml.service.impl;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteFileService;
import com.ruoyi.system.api.RemoteJobService;
import com.ruoyi.system.api.RemoteTranslateService;
import com.ruoyi.system.api.RemoteVehicleService;
import com.ruoyi.system.api.domain.SysJob;
import com.ruoyi.system.api.domain.VehicleInfo;
import com.ruoyi.system.api.enums.JobType;
import com.ruoyi.xml.domain.XmlFile;
import com.ruoyi.xml.domain.XmlVersion;
import com.ruoyi.xml.mapper.XmlFileMapper;
import com.ruoyi.xml.mapper.XmlVersionMapper;
import com.ruoyi.xml.service.IXmlFileService;
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
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
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
    private RemoteVehicleService remoteVehicleService;

    @Autowired
    private RemoteFileService remoteFileService;

    @Autowired
    private RemoteJobService remoteJobService;

    @Autowired
    private RemoteTranslateService remoteTranslateService;

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

        // 3. 从旧文件名中提取 VIN（vehicle_LSYZHRZ9ZY3G7B_1775179634552.xml）
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
        xmlFileMapper.updateIsLatestToFalse(dbXmlFile.getId());

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
            if (!(xmlIds.length == deleteRows)) {
                return AjaxResult.error(remoteTranslateService.translate("common.failed", null));
            }
            int jobRows = 0;
            for (Long xmlId : xmlIds) {
                SysJob job = new SysJob(
                        "XML" + xmlId,
                        JobType.XML_CLEAN_EXPIRED.getType(),
                        JobType.XML_CLEAN_EXPIRED.getInvoke() + "(" + xmlId + ")",
                        JobType.XML_CLEAN_EXPIRED.getCron(),
                        xmlId.toString()
                );
                int code = remoteJobService.createJob(job, SecurityConstants.INNER).getCode();
                jobRows += code == 200 ? 1 : 0;
            }
            Map<String, Integer> result = new HashMap<>();
            result.put("deleteRows", deleteRows);
            result.put("jobRows", jobRows);
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
    public String uploadXmlFile(MultipartFile file, String fileLevel) {
        try {
            String filePath = remoteFileService.upload(file).getData().getUrl();

            XmlFile xmlFile = new XmlFile();
            xmlFile.setFileName(file.getOriginalFilename());
            xmlFile.setFilePath(filePath);
            xmlFile.setFileSize(file.getSize());
            xmlFile.setFileLevel(fileLevel);
            xmlFile.setVersion("1.0");
            xmlFile.setIsLatest(true);
            xmlFile.setStatus("0");
            xmlFile.setDeleted(0);
            xmlFile.setCreateBy(SecurityUtils.getUsername());

            xmlFileMapper.insertXmlFile(xmlFile);

            // 保存版本记录
            XmlVersion version = new XmlVersion();
            version.setFileId(xmlFile.getId());
            version.setVersion("1.0");
            version.setFilePath(filePath);
            version.setChangeType("新建");
            version.setChangeDesc("初始版本");
            version.setCreateBy(SecurityUtils.getUsername());
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
        return xmlFileMapper.selectXmlFileVersions(xmlFile.getFileName());
    }

    /**
     * 版本对比
     */
    @Override
    public String compareVersions(Long oldVersionId, Long newVersionId) {
        try {
            XmlFile oldFile = xmlFileMapper.selectXmlFileById(oldVersionId);
            XmlFile newFile = xmlFileMapper.selectXmlFileById(newVersionId);

            if (oldFile == null || newFile == null) {
                throw new RuntimeException(remoteTranslateService.translate("common.file.not.exist", null));
            }

            String projectPath = System.getProperty("user.dir");
            String oldContent = new String(Files.readAllBytes(new File(projectPath + oldFile.getFilePath()).toPath()), StandardCharsets.UTF_8);
            String newContent = new String(Files.readAllBytes(new File(projectPath + newFile.getFilePath()).toPath()), StandardCharsets.UTF_8);

            String[] oldLines = oldContent.split("\n");
            String[] newLines = newContent.split("\n");

            StringBuilder diff = new StringBuilder();
            int maxLen = Math.max(oldLines.length, newLines.length);

            for (int i = 0; i < maxLen; i++) {
                String oldLine = i < oldLines.length ? oldLines[i] : "";
                String newLine = i < newLines.length ? newLines[i] : "";

                if (!oldLine.equals(newLine)) {
                    diff.append(remoteTranslateService.translate("xml.diff.line", null)).append(i + 1).append(":\n");
                    diff.append("- ").append(oldLine).append("\n");
                    diff.append("+ ").append(newLine).append("\n\n");
                }
            }

            return diff.length() > 0 ? diff.toString() : remoteTranslateService.translate("common.diff.content.same", null);
        } catch (Exception e) {
            log.error("版本对比失败", e);
            throw new RuntimeException(StringUtils.format(remoteTranslateService.translate("common.diff.compare.failed", null), e.getMessage()));
        }
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
            String projectPath = System.getProperty("user.dir");
            String fullPath = projectPath + xmlFile.getFilePath();
            File file = new File(fullPath);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.parse(file);
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
            R<VehicleInfo> r = remoteVehicleService.getVehicleInfo(vehicleId, SecurityConstants.INNER);
            VehicleInfo vehicle = r.getData();
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

            // 根元素
            Element root = doc.createElement("Ecoc");
            root.setAttribute("version", xmlVersion);
            root.setAttribute("xmlns", "http://www.example.com/ecoc");
            doc.appendChild(root);

            // 车辆基本信息
            Element vehicleInfo = doc.createElement("VehicleInfo");
            root.appendChild(vehicleInfo);

            addElement(doc, vehicleInfo, "VehicleId", String.valueOf(vehicle.getVehicleId()));
            addElement(doc, vehicleInfo, "VIN", vehicle.getVin());
            addElement(doc, vehicleInfo, "Model", vehicle.getModel());
            addElement(doc, vehicleInfo, "EngineNo", vehicle.getEngineNo());
            addElement(doc, vehicleInfo, "ExteriorColor", vehicle.getExteriorColor());

            // 格式化生产日期
            if (vehicle.getProductionDate() != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                addElement(doc, vehicleInfo, "ProductionDate", vehicle.getProductionDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            } else {
                addElement(doc, vehicleInfo, "ProductionDate", "");
            }

            addElement(doc, vehicleInfo, "ImportSource", vehicle.getImportSource());
            // 状态信息
            Element statusInfo = doc.createElement("StatusInfo");
            root.appendChild(statusInfo);

            String statusText = "0".equals(vehicle.getStatus()) ? "已入库" : "待检验";
            addElement(doc, statusInfo, "Status", statusText);
            addElement(doc, statusInfo, "CreateBy", vehicle.getCreateBy());
            if (vehicle.getCreateTime() != null) {
                addElement(doc, statusInfo, "CreateTime", vehicle.getCreateTime().toString());
            } else {
                addElement(doc, statusInfo, "CreateTime", "");
            }

            addElement(doc, statusInfo, "Remark", vehicle.getRemark());

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

            // 保存文件
            String savePath = "xml" + File.separator;
            File dir = new File(savePath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String newFileName = "vehicle_" + vehicle.getVin() + "_" + System.currentTimeMillis() + ".xml";
            File saveFile = new File(savePath + newFileName);
            Files.write(saveFile.toPath(), xmlContent.getBytes(StandardCharsets.UTF_8));

            // 保存到数据库
            XmlFile xmlFile = new XmlFile();
            xmlFile.setFileName(newFileName);
            xmlFile.setFilePath(File.separator + savePath + newFileName);
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
            version.setFilePath(File.separator + savePath + newFileName);
            version.setChangeType("生成");
            version.setChangeDesc("由车辆VIN: " + vehicle.getVin() + " 生成XML, 版本: " + xmlVersion);
            version.setCreateBy(SecurityUtils.getUsername());
            version.setCreateTime(new Date());
            xmlVersionMapper.insertXmlVersion(version);

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
        if (!(xmlIds.length == restoreRows)) {
            return AjaxResult.error("恢复Xml文件失败");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("jobType", JobType.VEHICLE_CLEAN_EXPIRED.getType());
        params.put("entityIds", Arrays.toString(xmlIds));
        int jobResult = remoteJobService.deleteJobByIdsConditions(params, SecurityConstants.INNER).getCode();
        Map<String, Object> result = new HashMap<>();
        result.put("restoreRows", restoreRows);
        result.put("jobResult", jobResult);
        return AjaxResult.success(result);
    }

    /**
     * 永久删除xml信息
     *
     * @param xmlIds 需要永久删除的xml主键集合
     * @return 结果
     */
    @Override
    public int permanentlyDeleteXmlByIds(Long[] xmlIds) {
        return xmlFileMapper.permanentlyDeleteXmlByIds(xmlIds);
    }
}