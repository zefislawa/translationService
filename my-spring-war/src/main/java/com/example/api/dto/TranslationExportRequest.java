package com.example.api.dto;

import java.util.List;

public class TranslationExportRequest {
    private String path;
    private String context;
    private String fileName;
    private String targetLanguage;
    private List<TranslationRow> rows;


    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }

    public List<TranslationRow> getRows() {
        return rows;
    }

    public void setRows(List<TranslationRow> rows) {
        this.rows = rows;
    }
}
