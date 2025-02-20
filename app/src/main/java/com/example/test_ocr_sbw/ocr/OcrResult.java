package com.example.test_ocr_sbw.ocr;

public class OcrResult {
    private String fileName;
    private String recognizedText;
    private long executionTimeMillis;
    private float confidenceAverage;

    public OcrResult(String fileName, String recognizedText, long executionTimeMillis, float confidenceAverage) {
        this.fileName = fileName;
        this.recognizedText = recognizedText;
        this.executionTimeMillis = executionTimeMillis;
        this.confidenceAverage = confidenceAverage;
    }

    public String getFileName() {
        return fileName;
    }

    public String getRecognizedText() {
        return recognizedText;
    }

    public long getExecutionTimeMillis() {
        return executionTimeMillis;
    }

    public float getConfidenceAverage() {
        return confidenceAverage;
    }

    // Si quieres, un método para filtrar caracteres no alfanuméricos:
    public String getAlphanumericText() {
        // Mantiene [a-zA-Z0-9 y espacios]
        return recognizedText.replaceAll("[^a-zA-Z0-9 ]", "");
    }
}
