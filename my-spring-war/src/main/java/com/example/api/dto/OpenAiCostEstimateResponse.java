package com.example.api.dto;

public class OpenAiCostEstimateResponse {
    private String model;
    private int selectedStringCount;
    private long inputTokens;
    private long cachedInputTokens;
    private long outputTokens;
    private long totalTokens;
    private String estimatedCostUsd;
    private boolean thresholdExceeded;
    private String warningMessage;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getSelectedStringCount() { return selectedStringCount; }
    public void setSelectedStringCount(int selectedStringCount) { this.selectedStringCount = selectedStringCount; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getCachedInputTokens() { return cachedInputTokens; }
    public void setCachedInputTokens(long cachedInputTokens) { this.cachedInputTokens = cachedInputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public String getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(String estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }

    public boolean isThresholdExceeded() { return thresholdExceeded; }
    public void setThresholdExceeded(boolean thresholdExceeded) { this.thresholdExceeded = thresholdExceeded; }

    public String getWarningMessage() { return warningMessage; }
    public void setWarningMessage(String warningMessage) { this.warningMessage = warningMessage; }
}
