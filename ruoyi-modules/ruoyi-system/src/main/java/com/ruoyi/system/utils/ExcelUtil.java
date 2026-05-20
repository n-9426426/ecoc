package com.ruoyi.system.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * system 模块通用 Excel 导出工具类（基于 POI）
 * 采用固定列配置，不依赖数据库动态列配置
 */
@Slf4j
@Component
public class ExcelUtil {

    /**
     * 通用导出
     *
     * @param response   HttpServletResponse
     * @param dataList   数据列表
     * @param headers    列头名称数组，与 fieldNames 一一对应
     * @param fieldNames 实体字段名数组
     * @param fileName   导出文件名（不含后缀）
     */
    public <T> void exportExcel(HttpServletResponse response,
                                List<T> dataList,
                                String[] headers,
                                String[] fieldNames,
                                String fileName) throws Exception {

        log.info("导出 Excel，fileName={}，数据量={}", fileName, dataList.size());

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(fileName);

            // ===== 列头行 =====
            CellStyle headerStyle = createHeaderStyle(workbook);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 20 * 256);
            }

            // ===== 数据行 =====
            CellStyle dateStyle = createDateStyle(workbook);
            for (int rowIdx = 0; rowIdx < dataList.size(); rowIdx++) {
                Row row = sheet.createRow(rowIdx + 1);
                T entity = dataList.get(rowIdx);
                for (int colIdx = 0; colIdx < fieldNames.length; colIdx++) {
                    Cell cell = row.createCell(colIdx);
                    Object value = getFieldValue(entity, fieldNames[colIdx]);
                    setCellValue(cell, value, dateStyle);
                }
            }

            // ===== 输出流 =====
            response.setContentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + URLEncoder.encode(fileName + ".xlsx", "UTF-8"));
            workbook.write(response.getOutputStream());
        }
    }

    // ==================== 私有工具方法 ====================

    /**
     * 反射获取字段值（支持父类字段）
     */
    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (Exception e) {
            log.warn("获取字段 {} 值失败：{}", fieldName, e.getMessage());
        }
        return null;
    }

    /**
     * 向上遍历父类查找字段，找不到返回 null
     */
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 根据值类型写入单元格
     */
    private void setCellValue(Cell cell, Object value, CellStyle dateStyle) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }
        if (value instanceof Date) {
            cell.setCellValue((Date) value);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value ? "是" : "否");
        } else {
            cell.setCellValue(value.toString());
        }
    }

    /**
     * 列头样式
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    /**
     * 日期样式
     */
    private CellStyle createDateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(
                workbook.getCreationHelper().createDataFormat().getFormat("yyyy-MM-dd HH:mm:ss")
        );
        return style;
    }
}
