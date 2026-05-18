package com.example.api.dto;

public class TranslationReviewItem {
    private String key;
    private String sourceText;
    private String translatedText;
    private String context;
    private Integer maxLength;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }
    public String getTranslatedText() { return translatedText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
}
