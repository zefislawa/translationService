package com.example.api.dto;

import java.util.List;

public class TranslationSaveRequest {
    private String path;
    private String fileName;
    private List<TranslationRow> rows;

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

    public List<TranslationRow> getRows() {
        return rows;
    }

    public void setRows(List<TranslationRow> rows) {
        this.rows = rows;
    }
}
