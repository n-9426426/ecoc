package com.ruoyi.system.api.enums;

public enum FileTypeEnum {

    // 文档类型
    EXCEL("excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new String[]{"xlsx", "xls"}),
    WORD("word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new String[]{"docx", "doc"}),
    PDF("pdf", "application/pdf", new String[]{"pdf"}),
    TXT("txt", "text/plain", new String[]{"txt"}),
    CSV("csv", "text/csv", new String[]{"csv"}),
    PPT("ppt", "application/vnd.openxmlformats-officedocument.presentationml.presentation", new String[]{"pptx", "ppt"}),

    // 图片类型
    IMAGE("image", "image/jpeg", new String[]{"jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff"}),

    // 视频类型
    VIDEO("video", "video/mp4", new String[]{"mp4", "avi", "mov", "wmv", "flv", "mkv", "webm", "m4v", "3gp"}),

    // 音频类型
    AUDIO("audio", "audio/mpeg", new String[]{"mp3", "wav", "flac", "aac", "ogg", "wma", "m4a"}),

    // 压缩文件
    ARCHIVE("archive", "application/zip", new String[]{"zip", "rar", "7z", "tar", "gz", "bz2", "xz"}),

    // 代码文件
    CODE("code", "text/plain", new String[]{"java", "py", "js", "ts", "html", "css", "xml", "json", "yaml", "yml", "sql", "sh", "bat", "c", "cpp", "h", "go", "php", "rb", "swift", "kt", "rs"}),

    // 其他
    UNKNOWN("unknown", "application/octet-stream", new String[]{});

    private final String type;
    private final String contentType;
    private final String[] extensions;

    FileTypeEnum(String type, String contentType, String[] extensions) {
        this.type = type;
        this.contentType = contentType;
        this.extensions = extensions;
    }

    public String getType() {
        return type;
    }

    public String getContentType() {
        return contentType;
    }

    public String[] getExtensions() {
        return extensions;
    }

    public static FileTypeEnum getByExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return UNKNOWN;
        }

        extension = extension.toLowerCase();
        for (FileTypeEnum fileType : values()) {
            for (String ext : fileType.extensions) {
                if (ext.equalsIgnoreCase(extension)) {
                    return fileType;
                }
            }
        }
        return UNKNOWN;
    }

    public boolean isImage() {
        return this == IMAGE;
    }

    public boolean isDocument() {
        return this == EXCEL || this == WORD || this == PDF || this == TXT || this == CSV || this == PPT;
    }

    public boolean isMedia() {
        return this == VIDEO || this == AUDIO;
    }
}
