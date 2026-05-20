package com.example.api.dto;

import java.util.List;

public class TranslationCompareTranslateImportRequest {

    private String path;
    private String context;
    private String sourceFileName;
    private String targetFileName;
    private String mode;
    private Boolean postProcessWithOpenAi;
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

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getTargetFileName() {
        return targetFileName;
    }

    public void setTargetFileName(String targetFileName) {
        this.targetFileName = targetFileName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Boolean getPostProcessWithOpenAi() {
        return postProcessWithOpenAi;
    }

    public void setPostProcessWithOpenAi(Boolean postProcessWithOpenAi) {
        this.postProcessWithOpenAi = postProcessWithOpenAi;
    }

    public List<TranslationRow> getRows() {
        return rows;
    }

    public void setRows(List<TranslationRow> rows) {
        this.rows = rows;
    }
}
