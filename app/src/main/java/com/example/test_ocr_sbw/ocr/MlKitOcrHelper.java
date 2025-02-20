package com.example.test_ocr_sbw.ocr;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper para MLKit OCR: versión asíncrona y bloqueante.
 */
public class MlKitOcrHelper {

    public interface OcrCallback {
        void onSuccess(String recognizedText, float confidence);
        void onError(Exception e);
    }

    /**
     * Versión asíncrona.
     */
    public void runOcr(Bitmap bitmap, OcrCallback callback) {
        if (bitmap == null) {
            if (callback != null) {
                callback.onError(new IllegalArgumentException("Bitmap is null"));
            }
            return;
        }
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        long startTime = System.currentTimeMillis();
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
                        long endTime = System.currentTimeMillis();
                        Log.d("MlKitOcrHelper", "MLKit OCR took: " + (endTime - startTime) + " ms");

                        String recognizedText = visionText.getText();
                        float confidence = -1; // MLKit no provee la confianza
                        if (callback != null) {
                            callback.onSuccess(recognizedText, confidence);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        if (callback != null) {
                            callback.onError(e);
                        }
                    }
                });
    }

    /**
     * Versión bloqueante usando CountDownLatch.
     */
    public String runBlockingOcr(Bitmap bitmap) {
        final AtomicReference<String> textRef = new AtomicReference<>("");
        final CountDownLatch latch = new CountDownLatch(1);

        runOcr(bitmap, new OcrCallback() {
            @Override
            public void onSuccess(String recognizedText, float confidence) {
                textRef.set(recognizedText);
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return textRef.get();
    }
}