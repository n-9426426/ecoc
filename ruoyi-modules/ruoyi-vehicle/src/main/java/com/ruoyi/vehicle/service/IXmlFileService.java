package com.ruoyi.vehicle.service;

import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.vehicle.domain.XmlFile;
import com.ruoyi.vehicle.domain.vo.DiffResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * XML文件Service接口
 */
public interface IXmlFileService {
    /**
     * 查询XML文件列表
     */
    List<XmlFile> selectXmlFileList(XmlFile xmlFile);

    /**
     * 查询XML文件
     */
    XmlFile selectXmlFileById(Long id);

    /**
     * 新增XML文件
     */
    int insertXmlFile(XmlFile xmlFile);

    /**
     * 修改XML文件
     */
    int updateXmlFile(XmlFile xmlFile);

    /**
     * 批量删除XML文件
     */
    AjaxResult deleteXmlFileByIds(Long[] ids);

    /**
     * 上传XML文件
     */
    String uploadXmlFile(MultipartFile file, Long xmlId);

    /**
     * 预览XML文件
     */
    String previewXml(Long id);

    /**
     * 查询文件版本列表
     */
    List<XmlFile> selectXmlFileVersions(Long fileId);

    /**
     * 版本对比
     */
    DiffResultVO compareVersions(Long newVersionId, Long oldVersionId);

    /**
     * 校验XML文件
     */
    boolean validateXml(Long id);

    /**
     * 从数据库生成XML文件
     */
    String generateXmlFromDatabase(Long vehicleId);

    public AjaxResult restoreXmlByIds(Long[] xmlIds);

    public int permanentlyDeleteXmlByIds(Long[] xmlIds);
}