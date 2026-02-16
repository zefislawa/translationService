package com.example.api.dto;

public class TranslationRow {
    private String section;
    private String key;
    private String text;
    private String englishReference;

    public TranslationRow() {}

    public TranslationRow(String section, String key, String text) {
        this.section = section;
        this.key = key;
        this.text = text;
    }

    public TranslationRow(String section, String key, String text, String englishReference) {
        this.section = section;
        this.key = key;
        this.text = text;
        this.englishReference = englishReference;
    }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getEnglishReference() { return englishReference; }
    public void setEnglishReference(String englishReference) { this.englishReference = englishReference; }
}
