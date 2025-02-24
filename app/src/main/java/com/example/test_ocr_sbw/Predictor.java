package com.example.test_ocr_sbw;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Vector;

public class Predictor {
    private static final String TAG = Predictor.class.getSimpleName();
    public boolean isLoaded = false;
    public int warmupIterNum = 1;
    public int inferIterNum = 1;
    public int cpuThreadNum = 4;
    public String cpuPowerMode = "LITE_POWER_HIGH";
    public String modelPath = "";
    public String modelName = "";
    protected OCRPredictorNative paddlePredictor = null;
    protected float inferenceTime = 0;
    // Sólo para detección de objetos
    protected Vector<String> wordLabels = new Vector<>();
    protected int detLongSize = 960;
    protected float scoreThreshold = 0.1f;
    protected Bitmap inputImage = null;
    // Ya no se utiliza outputImage, pues no se dibuja el resultado
    protected volatile String outputResult = "";
    protected float postprocessTime = 0;

    // Clase que encapsula el resultado de la predicción
    public static class PredictionResult {
        public String ocr;                       // Texto OCR concatenado
        public float inferenceTime;              // Tiempo de inferencia en ms
        public ArrayList<OcrResultModel> details; // Lista de resultados detallados (cada uno con su confianza)
    }

    public Predictor() {
    }

    public boolean init(Context appCtx, String modelPath, String labelPath, int useOpencl, int cpuThreadNum, String cpuPowerMode) {
        isLoaded = loadModel(appCtx, modelPath, useOpencl, cpuThreadNum, cpuPowerMode);
        if (!isLoaded) {
            return false;
        }
        isLoaded = loadLabel(appCtx, labelPath);
        return isLoaded;
    }

    public boolean init(Context appCtx, String modelPath, String labelPath, int useOpencl, int cpuThreadNum, String cpuPowerMode,
                        int detLongSize, float scoreThreshold) {
        boolean isLoaded = init(appCtx, modelPath, labelPath, useOpencl, cpuThreadNum, cpuPowerMode);
        if (!isLoaded) {
            return false;
        }
        this.detLongSize = detLongSize;
        this.scoreThreshold = scoreThreshold;
        return true;
    }

    protected boolean loadModel(Context appCtx, String modelPath, int useOpencl, int cpuThreadNum, String cpuPowerMode) {
        // Liberar modelo si existe
        releaseModel();

        // Cargar modelo
        if (modelPath.isEmpty()) {
            return false;
        }
        String realPath = modelPath;
        if (!modelPath.substring(0, 1).equals("/")) {
            // Se copia el modelo desde assets al directorio cache
            realPath = appCtx.getCacheDir() + "/" + modelPath;
            Utils.copyDirectoryFromAssets(appCtx, modelPath, realPath);
        }
        if (realPath.isEmpty()) {
            return false;
        }

        OCRPredictorNative.Config config = new OCRPredictorNative.Config();
        config.useOpencl = useOpencl;
        config.cpuThreadNum = cpuThreadNum;
        config.cpuPower = cpuPowerMode;
        config.detModelFilename = realPath + File.separator + "det_db.nb";
        config.recModelFilename = realPath + File.separator + "rec_crnn.nb";
        config.clsModelFilename = realPath + File.separator + "cls.nb";
        Log.i("Predictor", "model path: " + config.detModelFilename + " ; " + config.recModelFilename + " ; " + config.clsModelFilename);
        paddlePredictor = new OCRPredictorNative(config);

        this.cpuThreadNum = cpuThreadNum;
        this.cpuPowerMode = cpuPowerMode;
        this.modelPath = realPath;
        this.modelName = realPath.substring(realPath.lastIndexOf("/") + 1);
        return true;
    }

    public void releaseModel() {
        if (paddlePredictor != null) {
            paddlePredictor.destory();
            paddlePredictor = null;
        }
        isLoaded = false;
        cpuThreadNum = 1;
        cpuPowerMode = "LITE_POWER_HIGH";
        modelPath = "";
        modelName = "";
    }

    protected boolean loadLabel(Context appCtx, String labelPath) {
        wordLabels.clear();
        wordLabels.add("black");
        try {
            InputStream assetsInputStream = appCtx.getAssets().open(labelPath);
            int available = assetsInputStream.available();
            byte[] lines = new byte[available];
            assetsInputStream.read(lines);
            assetsInputStream.close();
            String words = new String(lines);
            String[] contents = words.split("\n");
            for (String content : contents) {
                wordLabels.add(content);
            }
            wordLabels.add(" ");
            Log.i(TAG, "Word label size: " + wordLabels.size());
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Ejecuta el modelo de forma sincrónicpa y retorna un objeto PredictionResult.
     * Se utilizan por defecto detección (run_det=1) y reconocimiento (run_rec=1),
     * mientras que la clasificación se omite (run_cls=0).
     *
     * @return PredictionResult con:
     *         - Texto OCR concatenado.
     *         - Tiempo de inferencia en ms.
     *         - Detalles de cada resultado (con confianza).
     * @throws Exception si la imagen de entrada es nula o el modelo no está cargado.
     */
    public PredictionResult runModelSync() throws Exception {
        // Valores por defecto: detección = 1, clasificación = 0, reconocimiento = 1.
        int run_det = 1;
        int run_cls = 0;
        int run_rec = 1;
        if (inputImage == null || !isLoaded()) {
            throw new Exception("Modelo no cargado o imagen de entrada nula");
        }

        // Warm up
        for (int i = 0; i < warmupIterNum; i++) {
            paddlePredictor.runImage(inputImage, detLongSize, run_det, run_cls, run_rec);
        }
        warmupIterNum = 0;

        // Ejecutar inferencia
        Date start = new Date();
        ArrayList<OcrResultModel> results = paddlePredictor.runImage(inputImage, detLongSize, run_det, run_cls, run_rec);
        Date end = new Date();
        inferenceTime = (end.getTime() - start.getTime()) / (float) inferIterNum;

        // Postprocesamiento
        results = postprocess(results);
        // Ya no se llama a drawResults ya que no se quiere dibujar el resultado

        // Concatenar el texto OCR de todos los resultados
        StringBuilder ocrSb = new StringBuilder();
        for (OcrResultModel result : results) {
            if (result.getLabel() != null && !result.getLabel().isEmpty()) {
                ocrSb.append(result.getLabel()).append(" ");
            }
        }

        PredictionResult predictionResult = new PredictionResult();
        predictionResult.ocr = ocrSb.toString().trim();
        predictionResult.inferenceTime = inferenceTime;
        predictionResult.details = results;
        return predictionResult;
    }

    public boolean isLoaded() {
        return paddlePredictor != null && isLoaded;
    }

    public String modelPath() {
        return modelPath;
    }

    public String modelName() {
        return modelName;
    }

    public int cpuThreadNum() {
        return cpuThreadNum;
    }

    public String cpuPowerMode() {
        return cpuPowerMode;
    }

    public float inferenceTime() {
        return inferenceTime;
    }

    public Bitmap inputImage() {
        return inputImage;
    }

    public String outputResult() {
        return outputResult;
    }

    public float postprocessTime() {
        return postprocessTime;
    }

    public void setInputImage(Bitmap image) {
        if (image == null) {
            return;
        }
        this.inputImage = image.copy(Bitmap.Config.ARGB_8888, true);
    }

    private ArrayList<OcrResultModel> postprocess(ArrayList<OcrResultModel> results) {
        for (OcrResultModel r : results) {
            StringBuffer word = new StringBuffer();
            for (int index : r.getWordIndex()) {
                if (index >= 0 && index < wordLabels.size()) {
                    word.append(wordLabels.get(index));
                } else {
                    Log.e(TAG, "Word index is not in label list:" + index);
                    word.append("×");
                }
            }
            r.setLabel(word.toString());
            r.setClsLabel(r.getClsIdx() == 1 ? "180" : "0");
        }
        return results;
    }
}
