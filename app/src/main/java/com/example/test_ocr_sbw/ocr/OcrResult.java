package com.example.test_ocr_sbw.ocr;

public class OcrResult {
    private String text;
    private float confidence;
    private long processingTime; // en ms

    public OcrResult(String text, float confidence, long processingTime) {
        this.text = text;
        this.confidence = confidence;
        this.processingTime = processingTime;
    }

    public String getText() {
        return text;
    }

    public float getConfidence() {
        return confidence;
    }

    public long getProcessingTime() {
        return processingTime;
    }
}
