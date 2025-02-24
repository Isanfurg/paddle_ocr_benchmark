package com.example.test_ocr_sbw;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.test_ocr_sbw.ocr.MlKitOcrHelper;
import com.example.test_ocr_sbw.ocr.OcrResult;
import com.example.test_ocr_sbw.ocr.YoloTFLiteHelper;
import com.example.test_ocr_sbw.utils.CsvUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SELECT_FOLDER = 300;
    private Button btnSelectFolder;
    private Button btnProcessFolder;
    private TextView tvFolderOutput;
    private ProgressBar progressBar;

    private Uri selectedFolderUri;
    private MlKitOcrHelper mlKitOcrHelper;
    // Para YOLO, se asume que tienes un modelo de OCR basado en YOLO; por ejemplo:
    private String chosenYoloModel = "best_int8.tflite";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder);

        btnSelectFolder = findViewById(R.id.btn_select_folder);
        btnProcessFolder = findViewById(R.id.btn_process_folder);
        tvFolderOutput = findViewById(R.id.tv_folder_output);
        progressBar = findViewById(R.id.progress_bar);

        mlKitOcrHelper = new MlKitOcrHelper();

        btnSelectFolder.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_CODE_SELECT_FOLDER);
        });

        btnProcessFolder.setOnClickListener(v -> {
            if (selectedFolderUri != null) {
                new ProcessFolderTask().execute(selectedFolderUri);
            } else {
                tvFolderOutput.setText("Seleccione una carpeta primero.");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SELECT_FOLDER && resultCode == RESULT_OK && data != null) {
            selectedFolderUri = data.getData();
            tvFolderOutput.setText("Carpeta seleccionada:\n" + selectedFolderUri.toString());
        }
    }

    /**
     * Clase para encapsular el progreso del procesamiento.
     */
    private static class ProgressData {
        int processed;
        int total;
        long estimatedRemainingTime; // en ms

        public ProgressData(int processed, int total, long estimatedRemainingTime) {
            this.processed = processed;
            this.total = total;
            this.estimatedRemainingTime = estimatedRemainingTime;
        }
    }

    /**
     * AsyncTask para procesar la carpeta sin bloquear la UI.
     * Para cada imagen se ejecuta OCR con MLKit, YOLO y Paddle, y se estima el tiempo restante.
     */
    private class ProcessFolderTask extends AsyncTask<Uri, ProgressData, String> {

        @Override
        protected void onPreExecute() {
            tvFolderOutput.setText("Procesando carpeta...");
            progressBar.setVisibility(ProgressBar.VISIBLE);
            progressBar.setProgress(0);
        }

        @Override
        protected String doInBackground(Uri... uris) {
            Uri folderUri = uris[0];
            DocumentFile directory = DocumentFile.fromTreeUri(MainActivity.this, folderUri);
            if (directory == null || !directory.isDirectory()) {
                return "La carpeta seleccionada no es válida.";
            }

            DocumentFile[] files = directory.listFiles();
            List<String> csvLines = new ArrayList<>();
            // Agregamos columnas para MLKit, YOLO y Paddle OCR
            csvLines.add("FileName,MLKitText,MLKitConfidence,MLKitTime(ms),YOLOText,YOLOAvgConfidence,YOLOTime(ms),PaddleText,PaddleAvgConfidence,PaddleTime(ms)");

            int totalFiles = files.length;
            long totalProcessingTime = 0; // suma del tiempo de procesamiento de cada imagen

            // Regex para extraer formato de patente chilena (ejemplo: 3 letras seguidas de 3 dígitos)
            Pattern patentePattern = Pattern.compile("^([A-Z]{4}[0-9]{2}|[A-Z]{2}[0-9]{4})$");

            // Inicializamos Paddle OCR (una única vez para todas las imágenes)
            Predictor paddleOcr = new Predictor();
            // Asumimos que los modelos y etiquetas están en la ruta "models/paddle" y "models/paddle/labels.txt" respectivamente.
            boolean paddleLoaded = paddleOcr.init(MainActivity.this, "models/paddle", "models/paddle/labels.txt", 0, 4, "LITE_POWER_HIGH");
            if (!paddleLoaded) {
                return "Error al cargar el modelo Paddle OCR.";
            }

            for (int i = 0; i < totalFiles; i++) {
                DocumentFile file = files[i];
                if (file.isFile() && file.getName() != null &&
                        (file.getName().toLowerCase().endsWith(".jpg") || file.getName().toLowerCase().endsWith(".png"))) {
                    try {
                        InputStream is = getContentResolver().openInputStream(file.getUri());
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (is != null) is.close();

                        // Redimensionar la imagen si alguna dimensión es menor a 128 píxeles
                        bmp = resizeIfNeeded(bmp, 256);

                        // MLKit OCR
                        long startMLKit = System.currentTimeMillis();
                        OcrResult mlkitResult = mlKitOcrHelper.runOcrSync(bmp);
                        long mlkitTime = System.currentTimeMillis() - startMLKit;

                        // Filtrar el texto obtenido para conservar sólo el formato de patente (alfanumérico)
                        String mlkitText = mlkitResult.getText();
                        Matcher matcher = patentePattern.matcher(mlkitText);
                        String filteredMlkitText = "";
                        if (matcher.find()) {
                            filteredMlkitText = matcher.group(0);
                        }
                        filteredMlkitText = filteredMlkitText.replace(",", " ");

                        // YOLO OCR
                        long startYOLO = System.currentTimeMillis();
                        YoloTFLiteHelper yoloHelper = new YoloTFLiteHelper(
                                MainActivity.this,
                                "models/yolo/" + chosenYoloModel,
                                null,
                                null,
                                message -> { }
                        );
                        // Ejecuta inferencia sobre la imagen completa
                        List<YoloTFLiteHelper.YoloDetection> detections = yoloHelper.runInference(
                                bmp,
                                bmp.getWidth(),
                                bmp.getHeight()
                        );
                        long yoloTime = System.currentTimeMillis() - startYOLO;

                        // Ordenar detecciones de izquierda a derecha
                        Collections.sort(detections, new Comparator<YoloTFLiteHelper.YoloDetection>() {
                            @Override
                            public int compare(YoloTFLiteHelper.YoloDetection d1, YoloTFLiteHelper.YoloDetection d2) {
                                return Float.compare(d1.left, d2.left);
                            }
                        });

                        StringBuilder yoloTextBuilder = new StringBuilder();
                        float sumConfidence = 0f;
                        for (YoloTFLiteHelper.YoloDetection det : detections) {
                            yoloTextBuilder.append(det.label);
                            sumConfidence += det.confidence;
                        }
                        float avgYoloConfidence = detections.isEmpty() ? 0 : sumConfidence / detections.size();
                        String yoloText = yoloTextBuilder.toString().replace(",", " ");

                        // Paddle OCR
                        long startPaddle = System.currentTimeMillis();
                        paddleOcr.setInputImage(bmp);
                        // Se asume que se ejecutan detección, clasificación y reconocimiento (banderas = 1)
                        Predictor.PredictionResult paddleResult = paddleOcr.runModelSync();
                        long paddleTime = System.currentTimeMillis() - startPaddle;
                        // Calcular promedio de confianza de Paddle (si hay detalles)
                        float sumPaddleConfidence = 0f;
                        int count = 0;
                        if (paddleResult.details != null) {
                            for (OcrResultModel det : paddleResult.details) {
                                sumPaddleConfidence += det.getConfidence();
                                count++;
                            }
                        }
                        float avgPaddleConfidence = count > 0 ? sumPaddleConfidence / count : 0;
                        String paddleText = paddleResult.ocr.replace(",", " ");

                        long totalTimeForFile = mlkitTime + yoloTime + paddleTime;
                        totalProcessingTime += totalTimeForFile;

                        String line = file.getName() + "," +
                                filteredMlkitText + "," + mlkitResult.getConfidence() + "," + mlkitTime +
                                "," + yoloText + "," + avgYoloConfidence + "," + yoloTime +
                                "," + paddleText + "," + avgPaddleConfidence + "," + paddleTime;
                        csvLines.add(line);
                    } catch (IOException e) {
                        Log.e("FolderProcess", "Error procesando " + file.getName(), e);
                    } catch (Exception ex) {
                        Log.e("FolderProcess", "Error con Paddle OCR en " + file.getName(), ex);
                    }
                }
                // Publica progreso: calcula tiempo restante estimado
                int processed = i + 1;
                long avgTimePerFile = processed > 0 ? totalProcessingTime / processed : 0;
                long estimatedRemainingTime = avgTimePerFile * (totalFiles - processed);
                publishProgress(new ProgressData(processed, totalFiles, estimatedRemainingTime));
            }
            String csvData = String.join("\n", csvLines);
            String savedPath = CsvUtils.saveCsv(MainActivity.this, csvData, "ocr_results.csv");
            if (savedPath != null) {
                return "CSV guardado en:\n" + savedPath;
            } else {
                return "Error al guardar CSV.";
            }
        }

        @Override
        protected void onProgressUpdate(ProgressData... values) {
            ProgressData progress = values[0];
            progressBar.setProgress(progress.processed);
            // Actualiza el TextView mostrando archivos procesados y tiempo restante estimado
            tvFolderOutput.setText("Procesados: " + progress.processed + " de " + progress.total +
                    "\nTiempo restante estimado: " + progress.estimatedRemainingTime + " ms");
        }

        @Override
        protected void onPostExecute(String result) {
            progressBar.setVisibility(ProgressBar.GONE);
            tvFolderOutput.append("\n" + result);
        }

        /**
         * Redimensiona la imagen para que su ancho o alto sea al menos minDimension.
         * Se mantiene la relación de aspecto.
         */
        private Bitmap resizeIfNeeded(Bitmap bmp, int minDimension) {
            int width = bmp.getWidth();
            int height = bmp.getHeight();
            if (width >= minDimension && height >= minDimension) {
                return bmp;
            }
            float scale = (float) minDimension / Math.min(width, height);
            int newWidth = Math.round(width * scale);
            int newHeight = Math.round(height * scale);
            return Bitmap.createScaledBitmap(bmp, newWidth, newHeight, true);
        }
    }
}
