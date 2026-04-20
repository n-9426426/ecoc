package com.ruoyi.vehicle.utils;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.commons.CommonsMultipartFile;

import java.io.IOException;
import java.io.OutputStream;

public class FileUtils {

    /**
     * 将 byte[] 转换为 MultipartFile
     *
     * @param content 文件内容
     * @param fileName 文件名
     * @return MultipartFile
     */
    public static MultipartFile createMultipartFile(byte[] content, String fileName) {
        return createMultipartFile(content, fileName, "application/octet-stream");
    }

    /**
     * 将 byte[] 转换为 MultipartFile
     *
     * @param content 文件内容
     * @param fileName 文件名
     * @param contentType MIME 类型
     * @return MultipartFile
     */
    public static MultipartFile createMultipartFile(byte[] content, String fileName, String contentType) {
        DiskFileItemFactory factory = new DiskFileItemFactory();
        // 设置内存阈值（16KB），超过则写入临时文件
        factory.setSizeThreshold(16 * 1024);

        FileItem fileItem = factory.createItem(
                "file",
                contentType,
                false,
                fileName
        );

        try (OutputStream os = fileItem.getOutputStream()) {
            os.write(content);
        } catch (IOException e) {
            throw new RuntimeException("创建 MultipartFile 失败: " + e.getMessage(), e);
        }

        return new CommonsMultipartFile(fileItem);
    }

    /**
     * 将字符串转换为 MultipartFile（UTF-8 编码）
     *
     * @param content 文件内容
     * @param fileName 文件名
     * @param contentType MIME 类型
     * @return MultipartFile
     */
    public static MultipartFile createMultipartFile(String content, String fileName, String contentType) {
        return createMultipartFile(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), fileName, contentType);
    }
}