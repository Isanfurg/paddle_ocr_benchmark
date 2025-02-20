package com.example.test_ocr_sbw.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Ejemplo genérico de una clase que:
 * 1) Inicializa Paddle Lite con los .nb
 * 2) Ofrece un método para ejecutar la inferencia (detección + reconocimiento)
 */
public class PaddleOcrHelper {

    private static final String TAG = "PaddleOcrHelper";
    // Nombres de modelos en assets
    private static final String DET_MODEL = "models/ch_PP-OCRv3_det_infer.nb";
    private static final String REC_MODEL = "models/ch_PP-OCRv3_rec_infer.nb";
    private static final String CLS_MODEL = "models/ch_ppocr_mobile_v2.0_cls_infer.nb";

    // Librería nativa, hipotético
    static {
        System.loadLibrary("paddle_lite_jni");
        // O el .so que necesites
    }

    // Supongamos que existe un wrapper nativo o Java:
    private native boolean initPaddle(Context context, String detPath, String recPath, String clsPath);
    private native OcrResultStruct runOcrNative(Bitmap bitmap);

    private boolean isInited = false;

    /**
     * Inicializa los modelos de PaddleOCR.
     */
    public boolean init(Context context) {
        // Copiar o preparar los archivos nb en la ruta que Paddle Lite espera,
        // o usarlos directamente desde assets (depende de tu wrapper).
        boolean ok = initPaddle(context, DET_MODEL, REC_MODEL, CLS_MODEL);
        isInited = ok;
        Log.d(TAG, "Paddle init: " + ok);
        return ok;
    }

    /**
     * Ejecuta la inferencia (detección + reconocimiento).
     * Devuelve un Future con el resultado final.
     */
    public CompletableFuture<String> runOcr(Bitmap bitmap) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (!isInited) {
            future.completeExceptionally(new IllegalStateException("Paddle no inicializado"));
            return future;
        }

        new Thread(() -> {
            try {
                OcrResultStruct nativeResult = runOcrNative(bitmap);
                // Asume que OcrResultStruct tiene campos como "text", "confidence"...
                if (nativeResult != null) {
                    future.complete(nativeResult.text);
                } else {
                    future.complete("");
                }
            } catch (Exception e) {
                e.printStackTrace();
                future.completeExceptionally(e);
            }
        }).start();
        return future;
    }

    /**
     * Estructura que recibes desde la parte nativa.
     * Ejemplo con solo 'text' y 'confidence'.
     */
    public static class OcrResultStruct {
        public String text;
        public float confidence;
    }
}
