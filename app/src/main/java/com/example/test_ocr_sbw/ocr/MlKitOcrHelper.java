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

public class MlKitOcrHelper {

    public interface OcrCallback {
        void onSuccess(String recognizedText, float confidence);
        void onError(Exception e);
    }

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
                        long elapsed = System.currentTimeMillis() - startTime;
                        String recognizedText = visionText.getText();
                        recognizedText = recognizedText.replaceAll("[^A-Za-z0-9]", "");
                        if (callback != null) {
                            callback.onSuccess(recognizedText, -1.0f);
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

    public OcrResult runOcrSync(Bitmap bitmap) {
        final AtomicReference<OcrResult> resultRef = new AtomicReference<>(new OcrResult("", -1.0f, 0));
        final CountDownLatch latch = new CountDownLatch(1);
        long startTime = System.currentTimeMillis();
        runOcr(bitmap, new OcrCallback() {
            @Override
            public void onSuccess(String recognizedText, float confidence) {
                long elapsed = System.currentTimeMillis() - startTime;
                resultRef.set(new OcrResult(recognizedText, confidence, elapsed));
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
        return resultRef.get();
    }
}
