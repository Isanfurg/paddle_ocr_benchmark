package com.example.test_ocr_sbw.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.example.test_ocr_sbw.yolo.BoundingBox;
import com.example.test_ocr_sbw.yolo.MetaData;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.CastOp;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class YoloTFLiteHelper {

    private Context context;
    private String modelPath;
    private String labelPath; // Puede ser null
    private DetectorListener detectorListener;
    private MessageCallback messageCallback;

    private Interpreter interpreter;
    private List<String> labels;

    private int tensorWidth = 0;
    private int tensorHeight = 0;
    private int numChannel = 0;
    private int numElements = 0;

    private ImageProcessor imageProcessor;

    // Constantes para preprocesamiento e inferencia
    private static final float INPUT_MEAN = 0f;
    private static final float INPUT_STANDARD_DEVIATION = 255f;
    private static final DataType INPUT_IMAGE_TYPE = DataType.FLOAT32;
    private static final DataType OUTPUT_IMAGE_TYPE = DataType.FLOAT32;
    private static final float CONFIDENCE_THRESHOLD = 0.3f;
    private static final float IOU_THRESHOLD = 0.5f;

    /**
     * Constructor.
     *
     * @param context          Contexto de la aplicación.
     * @param modelPath        Ruta al modelo TFLite (por ejemplo, "models/yolo/best_int8.tflite").
     * @param labelPath        Ruta al archivo de labels (puede ser null).
     * @param detectorListener Callback para detección (opcional, se puede pasar null si se usa runInference).
     * @param messageCallback  Callback para mensajes (opcional, se puede pasar null).
     */
    public YoloTFLiteHelper(Context context, String modelPath, String labelPath,
                            DetectorListener detectorListener, MessageCallback messageCallback) {
        this.context = context;
        this.modelPath = modelPath;
        this.labelPath = labelPath;
        this.detectorListener = detectorListener;
        this.messageCallback = messageCallback;
        this.labels = new ArrayList<>();

        // Configura el preprocesamiento de la imagen: normalización y casteo
        imageProcessor = new ImageProcessor.Builder()
                .add(new NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
                .add(new CastOp(INPUT_IMAGE_TYPE))
                .build();

        // Configuración del intérprete (usando CPU: 4 hilos)
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, modelPath);
            interpreter = new Interpreter(modelBuffer, options);

            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outputShape = interpreter.getOutputTensor(0).shape();

            // Extrae labels desde la metadata; si falla, usa el archivo o TEMP_CLASSES
            labels.addAll(MetaData.extractNamesFromMetadata(modelBuffer));
            if (labels.isEmpty()) {
                if (labelPath == null) {
                    if (messageCallback != null) {
                        messageCallback.onMessage("El modelo no contiene metadata; proporciona LABELS_PATH.");
                    }
                    labels.addAll(MetaData.TEMP_CLASSES);
                } else {
                    labels.addAll(MetaData.extractNamesFromLabelFile(context, labelPath));
                }
            }

            // Configura las dimensiones de la imagen de entrada
            if (inputShape != null && inputShape.length >= 3) {
                tensorWidth = inputShape[1];
                tensorHeight = inputShape[2];
                // Caso: formato [1, 3, width, height]
                if (inputShape[1] == 3 && inputShape.length >= 4) {
                    tensorWidth = inputShape[2];
                    tensorHeight = inputShape[3];
                }
            }

            // Configura las dimensiones de la salida
            if (outputShape != null && outputShape.length >= 3) {
                numChannel = outputShape[1];
                numElements = outputShape[2];
            }
        } catch (Exception e) {
            if (messageCallback != null) {
                messageCallback.onMessage("Error al cargar el modelo: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    /**
     * Reinicia el intérprete.
     */
    public void restart() {
        if (interpreter != null) {
            interpreter.close();
        }
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        try {
            MappedByteBuffer modelBuffer = FileUtil.loadMappedFile(context, modelPath);
            interpreter = new Interpreter(modelBuffer, options);
        } catch (Exception e) {
            if (messageCallback != null) {
                messageCallback.onMessage("Error al reiniciar el modelo: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    /**
     * Libera los recursos cerrando el intérprete.
     */
    public void close() {
        if (interpreter != null) {
            interpreter.close();
        }
    }

    /**
     * Método de detección que utiliza un callback (si lo deseas usar).
     * Mide y registra los tiempos de preprocesamiento, inferencia y postprocesamiento.
     *
     * @param frame Imagen a procesar.
     */
    public void detect(Bitmap frame) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            return;
        }

        // ---------------------------
        // Preprocesamiento
        // ---------------------------
        long startPreprocess = SystemClock.uptimeMillis();
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false);
        TensorImage tensorImage = new TensorImage(INPUT_IMAGE_TYPE);
        tensorImage.load(resizedBitmap);
        TensorImage processedImage = imageProcessor.process(tensorImage);
        java.nio.ByteBuffer imageBuffer = processedImage.getBuffer();
        long preProcessTime = SystemClock.uptimeMillis() - startPreprocess;

        // ---------------------------
        // Inferencia
        // ---------------------------
        long startInference = SystemClock.uptimeMillis();
        int[] outShape = new int[]{1, numChannel, numElements};
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outShape, OUTPUT_IMAGE_TYPE);
        interpreter.run(imageBuffer, outputBuffer.getBuffer());
        long inferenceTime = SystemClock.uptimeMillis() - startInference;

        // ---------------------------
        // Postprocesamiento
        // ---------------------------
        long startPostprocess = SystemClock.uptimeMillis();
        List<BoundingBox> bestBoxes = bestBox(outputBuffer.getFloatArray());
        long postProcessTime = SystemClock.uptimeMillis() - startPostprocess;

        long totalTime = preProcessTime + inferenceTime + postProcessTime;

        // Registro de los tiempos en Logcat y mediante callback
        Log.d("Timings", "Preprocesamiento: " + preProcessTime + " ms, " +
                "Inferencia: " + inferenceTime + " ms, " +
                "Postprocesamiento: " + postProcessTime + " ms, " +
                "Total: " + totalTime + " ms");

        if (messageCallback != null) {
            messageCallback.onMessage("Pre: " + preProcessTime + " ms, Inf: " + inferenceTime +
                    " ms, Post: " + postProcessTime + " ms, Total: " + totalTime + " ms");
        }

        if (bestBoxes == null) {
            if (detectorListener != null) {
                detectorListener.onEmptyDetect();
            }
            return;
        }
        if (detectorListener != null) {
            detectorListener.onDetect(bestBoxes, totalTime);
        }
    }

    /**
     * Ejecuta la inferencia de forma síncrona y retorna una lista de detecciones en coordenadas de píxeles.
     * Mide y registra los tiempos de preprocesamiento, inferencia y postprocesamiento.
     * Además, las detecciones se ordenan de izquierda a derecha (según la coordenada 'left') y se muestran
     * los nombres de las clases.
     *
     * @param frame          Imagen original a procesar.
     * @param originalWidth  Ancho de la imagen original.
     * @param originalHeight Altura de la imagen original.
     * @return Lista de YoloDetection.
     */
    public List<YoloDetection> runInference(Bitmap frame, int originalWidth, int originalHeight) {
        // ---------------------------
        // Preprocesamiento
        // ---------------------------
        long startPreprocess = SystemClock.uptimeMillis();
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false);
        TensorImage tensorImage = new TensorImage(INPUT_IMAGE_TYPE);
        tensorImage.load(resizedBitmap);
        TensorImage processedImage = imageProcessor.process(tensorImage);
        java.nio.ByteBuffer imageBuffer = processedImage.getBuffer();
        long preProcessTime = SystemClock.uptimeMillis() - startPreprocess;

        // ---------------------------
        // Inferencia
        // ---------------------------
        long startInference = SystemClock.uptimeMillis();
        int[] outShape = new int[]{1, numChannel, numElements};
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outShape, OUTPUT_IMAGE_TYPE);
        interpreter.run(imageBuffer, outputBuffer.getBuffer());
        long inferenceTime = SystemClock.uptimeMillis() - startInference;

        // ---------------------------
        // Postprocesamiento
        // ---------------------------
        long startPostprocess = SystemClock.uptimeMillis();
        List<BoundingBox> boxes = bestBox(outputBuffer.getFloatArray());
        long postProcessTime = SystemClock.uptimeMillis() - startPostprocess;

        long totalTime = preProcessTime + inferenceTime + postProcessTime;

        // Notifica los tiempos mediante el callback (si está definido)
        if (messageCallback != null) {
            messageCallback.onMessage("Pre: " + preProcessTime + " ms, Inf: " + inferenceTime +
                    " ms, Post: " + postProcessTime + " ms, Total: " + totalTime + " ms");
        }

        // Convierte cada BoundingBox (con coordenadas normalizadas) a YoloDetection (con coordenadas en píxeles)
        List<YoloDetection> detections = new ArrayList<>();
        if (boxes != null) {
            for (BoundingBox box : boxes) {
                float left = box.getX1() * originalWidth;
                float top = box.getY1() * originalHeight;
                float right = box.getX2() * originalWidth;
                float bottom = box.getY2() * originalHeight;
                YoloDetection detection = new YoloDetection(left, top, right, bottom, box.getCnf(), box.getCls(), box.getClsName());
                detections.add(detection);
            }
            // Ordena las detecciones de izquierda a derecha (según la coordenada 'left')
            Collections.sort(detections, new Comparator<YoloDetection>() {
                @Override
                public int compare(YoloDetection d1, YoloDetection d2) {
                    return Float.compare(d1.left, d2.left);
                }
            });
        }
        return detections;
    }

    /**
     * Procesa el arreglo de salida para determinar las bounding boxes.
     *
     * @param array Arreglo de salida de la inferencia.
     * @return Lista de BoundingBox o null si no se detectó nada.
     */
    private List<BoundingBox> bestBox(float[] array) {
        List<BoundingBox> boundingBoxes = new ArrayList<>();

        for (int c = 0; c < numElements; c++) {
            float maxConf = CONFIDENCE_THRESHOLD;
            int maxIdx = -1;
            int j = 4;
            int arrayIdx = c + numElements * j;
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx];
                    maxIdx = j - 4;
                }
                j++;
                arrayIdx += numElements;
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                String clsName = (maxIdx >= 0 && maxIdx < labels.size()) ? labels.get(maxIdx) : "Unknown";
                float cx = array[c];
                float cy = array[c + numElements];
                float w = array[c + numElements * 2];
                float h = array[c + numElements * 3];
                float x1 = cx - (w / 2f);
                float y1 = cy - (h / 2f);
                float x2 = cx + (w / 2f);
                float y2 = cy + (h / 2f);
                if (x1 < 0f || x1 > 1f) continue;
                if (y1 < 0f || y1 > 1f) continue;
                if (x2 < 0f || x2 > 1f) continue;
                if (y2 < 0f || y2 > 1f) continue;

                boundingBoxes.add(new BoundingBox(x1, y1, x2, y2, cx, cy, w, h, maxConf, maxIdx, clsName));
            }
        }
        if (boundingBoxes.isEmpty()) {
            return null;
        }
        return applyNMS(boundingBoxes);
    }

    /**
     * Aplica el algoritmo Non-Maximum Suppression (NMS) para filtrar cajas superpuestas.
     *
     * @param boxes Lista de BoundingBox detectadas.
     * @return Lista filtrada de BoundingBox.
     */
    private List<BoundingBox> applyNMS(List<BoundingBox> boxes) {
        List<BoundingBox> sortedBoxes = new ArrayList<>(boxes);
        Collections.sort(sortedBoxes, new Comparator<BoundingBox>() {
            @Override
            public int compare(BoundingBox b1, BoundingBox b2) {
                return Float.compare(b2.getCnf(), b1.getCnf());
            }
        });
        List<BoundingBox> selectedBoxes = new ArrayList<>();
        while (!sortedBoxes.isEmpty()) {
            BoundingBox first = sortedBoxes.get(0);
            selectedBoxes.add(first);
            sortedBoxes.remove(0);

            Iterator<BoundingBox> iterator = sortedBoxes.iterator();
            while (iterator.hasNext()) {
                BoundingBox nextBox = iterator.next();
                float iou = calculateIoU(first, nextBox);
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove();
                }
            }
        }
        return selectedBoxes;
    }

    /**
     * Calcula la Intersección sobre Unión (IoU) entre dos cajas.
     *
     * @param box1 Primera BoundingBox.
     * @param box2 Segunda BoundingBox.
     * @return Valor de IoU.
     */
    private float calculateIoU(BoundingBox box1, BoundingBox box2) {
        float x1 = Math.max(box1.getX1(), box2.getX1());
        float y1 = Math.max(box1.getY1(), box2.getY1());
        float x2 = Math.min(box1.getX2(), box2.getX2());
        float y2 = Math.min(box1.getY2(), box2.getY2());
        float intersectionArea = Math.max(0f, x2 - x1) * Math.max(0f, y2 - y1);
        float box1Area = box1.getW() * box1.getH();
        float box2Area = box2.getW() * box2.getH();
        return intersectionArea / (box1Area + box2Area - intersectionArea);
    }

    // ──────────────────────────────────────────────
    // Clase interna para representar una detección en coordenadas de píxeles.
    // ──────────────────────────────────────────────
    public static class YoloDetection {
        public float left;
        public float top;
        public float right;
        public float bottom;
        public float confidence;
        public int classId; // Se conserva para otros usos, pero en la visualización se usará 'label'
        public String label;

        public YoloDetection(float left, float top, float right, float bottom,
                             float confidence, int classId, String label) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.confidence = confidence;
            this.classId = classId;
            this.label = label;
        }
    }

    // ──────────────────────────────────────────────
    // Interfaces de callback (opcional)
    // ──────────────────────────────────────────────
    public interface DetectorListener {
        void onEmptyDetect();

        void onDetect(List<BoundingBox> boundingBoxes, long inferenceTime);
    }

    public interface MessageCallback {
        void onMessage(String message);
    }
}
