package com.example.api.dto;

import java.util.List;

public class ReviewedTranslationItem {
    private String key;
    private String finalText;
    private boolean changed;
    private String reason;
    private List<String> issues;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getFinalText() { return finalText; }
    public void setFinalText(String finalText) { this.finalText = finalText; }
    public boolean isChanged() { return changed; }
    public void setChanged(boolean changed) { this.changed = changed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<String> getIssues() { return issues; }
    public void setIssues(List<String> issues) { this.issues = issues; }
}
