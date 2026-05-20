package com.example.api.dto;

import java.util.List;

public class TranslationReviewResponse {
    private List<ReviewedTranslationItem> items;
    private Summary summary;

    public List<ReviewedTranslationItem> getItems() { return items; }
    public void setItems(List<ReviewedTranslationItem> items) { this.items = items; }
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public static class Summary {
        private int total;
        private int changed;
        private int unchanged;
        private int failed;
        private long inputTokens;
        private long cachedInputTokens;
        private long outputTokens;
        private long totalTokens;
        private String estimatedCostUsd;

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getChanged() { return changed; }
        public void setChanged(int changed) { this.changed = changed; }
        public int getUnchanged() { return unchanged; }
        public void setUnchanged(int unchanged) { this.unchanged = unchanged; }
        public int getFailed() { return failed; }
        public void setFailed(int failed) { this.failed = failed; }
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
    }
}
