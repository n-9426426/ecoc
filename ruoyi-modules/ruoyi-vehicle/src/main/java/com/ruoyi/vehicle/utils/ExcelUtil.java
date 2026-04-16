package com.ruoyi.vehicle.utils;

import com.ruoyi.common.core.constant.I18nConstants;
import com.ruoyi.system.api.domain.ExcelColumnConfig;
import com.ruoyi.system.config.I18nConfig;
import com.ruoyi.vehicle.mapper.ExcelColumnConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通用动态多语言 Excel 工具类（基于 POI）
 * 支持任意实体类，列头从数据库读取，语言从请求上下文自动解析
 */
@Slf4j
@Component
public class ExcelUtil {

    @Autowired
    private ExcelColumnConfigMapper columnConfigMapper;

    //==================== 语言解析 ====================

    /**
     * 从当前请求上下文中解析语言参数
     *优先级：URL参数 ?lang=en_US > Header Accept-Language > 默认 zh_CN
     */
    private String resolveCurrentLang() {
        try {
            ServletRequestAttributes attributes =(ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                // 1. 优先从请求参数获取 ?lang=en_US
                String lang = request.getParameter(I18nConstants.PARAM_LANG);
                if (StringUtils.isNotEmpty(lang)) {
                    return I18nConfig.normalizeLangCode(lang);
                }
                // 2. 其次从请求头获取 Accept-Language
                String acceptLang = request.getHeader(I18nConstants.HEADER_LANG);
                if (StringUtils.isNotEmpty(acceptLang)) {
                    return I18nConfig.normalizeLangCode(acceptLang);
                }}
        } catch (Exception ignored) {
            // 忽略异常，返回默认语言
        }
        return I18nConstants.DEFAULT_LANG;
    }

    // ==================== 导出 ====================

    /**
     * 通用动态导出（自动解析语言，无需外部传入lang）
     *
     * @param response Http Servlet Response
     * @param dataList  数据列表（任意实体类）
     * @param tableName 数据库中配置的 table_name
     * @param fileName  导出文件名（不含后缀）
     */
    public <T> void exportExcel(HttpServletResponse response,
                                List<T> dataList,
                                String tableName,
                                String fileName) throws Exception {
        // 1. 自动解析语言
        String lang = resolveCurrentLang();
        log.info("导出 Excel，tableName={}，lang={}，数据量={}", tableName, lang, dataList.size());

        // 2. 读取列配置
        List<ExcelColumnConfig> configs = getConfigs(tableName);
        if (configs.isEmpty()) {
            throw new RuntimeException("未找到表[" + tableName + "] 的列配置，请检查数据库 excel_column_config");
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(fileName);
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dateStyle   = createDateStyle(workbook);

            // 3. 写列头（根据语言动态切换）
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < configs.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(configs.get(i).getColumnName(lang));
                cell.setCellStyle(headerStyle);sheet.setColumnWidth(i, 20* 256);
            }

            // 4. 写数据行
            for (int rowIdx = 0; rowIdx < dataList.size(); rowIdx++) {
                Row row    = sheet.createRow(rowIdx + 1);
                T entity = dataList.get(rowIdx);
                for (int colIdx = 0; colIdx < configs.size(); colIdx++) {
                    Cell   cell      = row.createCell(colIdx);
                    Object value= getFieldValue(entity, configs.get(colIdx).getFieldName());
                    setCellValue(cell, value, dateStyle);
                }
            }

            // 5. 输出响应
            response.setContentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + URLEncoder.encode(fileName + ".xlsx", "UTF-8"));
            workbook.write(response.getOutputStream());
        }
    }

    // ==================== 导入 ====================

    /**
     * 通用动态导入（自动解析语言，无需外部传入 lang）
     *
     * @param inputStream 上传文件流
     * @param tableName   数据库中配置的 table_name
     * @param clazz       目标实体类 Class
     * @param<T>         实体类型
     * @return 解析后的实体列表
     */
    public <T> List<T> importExcel(InputStream inputStream,
                                   String tableName,
                                   Class<T> clazz) throws Exception {
        // 1. 自动解析语言
        String lang = resolveCurrentLang();
        log.info("导入 Excel，tableName={}，lang={}", tableName, lang);

        // 2. 读取列配置，建立列头名称 -> fieldName 的映射
        List<ExcelColumnConfig> configs = getConfigs(tableName);
        if (configs.isEmpty()) {
            throw new RuntimeException("未找到表 [" + tableName + "] 的列配置，请检查数据库 excel_column_config");
        }

        Map<String, String> headerFieldMap = configs.stream()
                .collect(Collectors.toMap(
                        c -> c.getColumnName(lang),   // 根据语言取对应列头
                        ExcelColumnConfig::getFieldName,
                        (k1, k2) -> k1// 重复列头保留第一个
                ));

        List<T> result = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);

            // 3. 读第一行列头，建立 列索引 -> fieldName 映射
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new RuntimeException("Excel 格式错误：缺少列头行");
            }

            Map<Integer, String> indexFieldMap = new LinkedHashMap<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                if (cell != null) {
                    String headerName = cell.getStringCellValue().trim();
                    String fieldName  = headerFieldMap.get(headerName);
                    if (fieldName != null) {
                        indexFieldMap.put(i, fieldName);
                    } else {
                        log.warn("导入时发现未配置的列头：{}，已跳过", headerName);
                    }
                }
            }

            // 4. 逐行读取数据
            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (isEmptyRow(row)) continue;

                T entity = clazz.getDeclaredConstructor().newInstance();
                for (Map.Entry<Integer, String> entry : indexFieldMap.entrySet()) {
                    Cell cell  = row.getCell(entry.getKey());
                    String value = getCellValue(cell);
                    setFieldValue(entity, entry.getValue(), value);
                }
                result.add(entity);
            }
        }

        log.info("导入完成，共解析 {} 条数据", result.size());
        return result;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 读取并排序列配置
     */
    private List<ExcelColumnConfig> getConfigs(String tableName) {
        return columnConfigMapper.selectByTableName(tableName)
                .stream()
                .sorted(Comparator.comparing(ExcelColumnConfig::getSort))
                .collect(Collectors.toList());
    }

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
     * 反射设置字段值（支持父类字段 + 类型自动转换）
     */
    private void setFieldValue(Object obj, String fieldName, String value) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field == null || value == null || value.trim().isEmpty()) return;
            field.setAccessible(true);
            field.set(obj, convertValue(field.getType(), value.trim()));
        } catch (Exception e) {
            log.warn("设置字段 {} 值失败，原始值={}：{}", fieldName, value, e.getMessage());
        }
    }

    /**
     * 向上查找字段（支持父类继承链）
     */
    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 类型转换（String ->目标类型）
     */
    private Object convertValue(Class<?> type, String value) throws Exception {
        if (type == String.class)return value;
        if (type == Long.class    || type == long.class)     return Long.parseLong(value);
        if (type == Integer.class || type == int.class)      return Integer.parseInt(value);
        if (type == Double.class  || type == double.class)   return Double.parseDouble(value);
        if (type == Float.class   || type == float.class)    return Float.parseFloat(value);
        if (type == Boolean.class || type == boolean.class)  {
            return "是".equals(value)
                    || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value)
                    || "1".equals(value);
        }
        if (type == Date.class) {
            // 支持多种日期格式
            String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd"};
            for (String pattern : patterns) {
                try {
                    return new SimpleDateFormat(pattern).parse(value);
                } catch (Exception ignored) {}
            }
            throw new RuntimeException("无法解析日期：" + value);
        }
        return value;
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
     * 读取单元格值（统一转String）
     */
    private String getCellValue(Cell cell) {
        if (cell == null) return null;

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();

            case NUMERIC:
                // 判断是否为日期格式
                if (DateUtil.isCellDateFormatted(cell)) {
                    return new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                }
                // 避免科学计数法，用DataFormatter 格式化
                return new DataFormatter().formatCellValue(cell).trim();

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }

            default:
                return null;
        }
    }

    /**
     * 判断是否为空行
     */
    private boolean isEmptyRow(Row row) {
        if (row == null) return true;
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellValue(cell);
                if (val != null && !val.trim().isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * 列头单元格样式
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
     * 日期格式单元格样式
     */
    private CellStyle createDateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(
                workbook.getCreationHelper().createDataFormat().getFormat("yyyy-MM-dd")
        );
        return style;
    }
}