package com.example.api.dto;

public class TranslationExportResult {
    private String outputFile;
    private String targetLanguage;
    private int textCount;

    public TranslationExportResult(String outputFile, String targetLanguage, int textCount) {
        this.outputFile = outputFile;
        this.targetLanguage = targetLanguage;
        this.textCount = textCount;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public int getTextCount() {
        return textCount;
    }
}
