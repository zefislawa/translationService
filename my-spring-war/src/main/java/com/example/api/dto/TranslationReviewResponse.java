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

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getChanged() { return changed; }
        public void setChanged(int changed) { this.changed = changed; }
        public int getUnchanged() { return unchanged; }
        public void setUnchanged(int unchanged) { this.unchanged = unchanged; }
        public int getFailed() { return failed; }
        public void setFailed(int failed) { this.failed = failed; }
    }
}
