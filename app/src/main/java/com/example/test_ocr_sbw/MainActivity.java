package com.example.test_ocr_sbw;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.test_ocr_sbw.ocr.MlKitOcrHelper;
import com.example.test_ocr_sbw.ocr.YoloTFLiteHelper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // Request codes
    private static final int REQUEST_CODE_SELECT_IMAGE_YOLO = 101;
    private static final int REQUEST_CODE_SELECT_IMAGE1 = 102;
    private static final int REQUEST_CODE_SELECT_IMAGE2 = 103;

    // UI elementos
    private Spinner spinnerEngine;
    private Button btnSelectYoloModel;
    private Button btnSelectImageYolo; // Para modos YOLO, Paddle, MLKit
    private LinearLayout layoutCollage; // Contenedor para botones de collage
    private Button btnSelectImage1;
    private Button btnSelectImage2;
    private Button btnRunInference;
    private ImageView imageView;
    private TextView tvResults;

    // Variables para imágenes y modelo
    private Bitmap selectedBitmapYolo = null; // Para modos YOLO, Paddle y MLKit
    private Bitmap selectedBitmap1 = null;      // Para modo Collage
    private Bitmap selectedBitmap2 = null;      // Para modo Collage
    private String chosenYoloModel = null;      // Modelo YOLO seleccionado

    // Helpers
    private MlKitOcrHelper mlKitHelper;  // Suponemos que está implementado
    // YoloTFLiteHelper se instanciará en runSelectedEngine según sea necesario

    /**
     * paddle
     **/
    protected ProgressDialog pbLoadModel = null;
    protected ProgressDialog pbRunModel = null;

    public static final int REQUEST_LOAD_MODEL = 0;
    public static final int REQUEST_RUN_MODEL = 1;
    public static final int RESPONSE_LOAD_MODEL_SUCCESSED = 0;
    public static final int RESPONSE_LOAD_MODEL_FAILED = 1;
    public static final int RESPONSE_RUN_MODEL_SUCCESSED = 2;
    public static final int RESPONSE_RUN_MODEL_FAILED = 3;

    protected Handler receiver = null; // Receive messages from worker thread
    protected Handler sender = null; // Send command to worker thread
    protected HandlerThread worker = null; // Worker thread to load&run model
    protected Predictor predictor = new Predictor();
    private Bitmap cur_predict_image = null;

    protected TextView tvStatus;
    protected TextView tvInputSetting;
    protected Spinner spRunMode;
    protected LinearLayout operatingLayout;

    // Model settings of ocr
    protected String modelPath = "";
    protected String labelPath = "";
    protected String imagePath = "";
    protected int cpuThreadNum = 1;
    protected String cpuPowerMode = "";
    protected int detLongSize = 960;
    protected float scoreThreshold = 0.1f;
    private String currentPhotoPath;
    private AssetManager assetManager = null;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Referenciar elementos del layout
        spinnerEngine = findViewById(R.id.spinner_engine);
        btnSelectYoloModel = findViewById(R.id.btn_select_yolo_model);
        btnSelectImageYolo = findViewById(R.id.btn_select_image_yolo);
        layoutCollage = findViewById(R.id.layout_collage);
        btnSelectImage1 = findViewById(R.id.btn_select_image1);
        btnSelectImage2 = findViewById(R.id.btn_select_image2);
        btnRunInference = findViewById(R.id.btn_run_inference);
        imageView = findViewById(R.id.imageView);
        tvResults = findViewById(R.id.tv_results);
        tvStatus = findViewById(R.id.tv_model_img_status);
        tvInputSetting = findViewById(R.id.tv_input_setting);
        spRunMode = findViewById(R.id.sp_run_mode);
        operatingLayout = findViewById(R.id.run_mode_layout);

        // Inicializar helper MLKit (suponiendo que está implementado)
        mlKitHelper = new MlKitOcrHelper();

        // Estado inicial: deshabilitar botón de ejecución
        btnRunInference.setEnabled(false);
        // Ocultar controles específicos inicialmente
        btnSelectYoloModel.setVisibility(GONE);
        btnSelectImageYolo.setVisibility(GONE);
        layoutCollage.setVisibility(GONE);

        // Configurar el spinner usando el array definido en strings.xml
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.engine_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEngine.setAdapter(adapter);

        // Listener del spinner para ajustar controles según la opción seleccionada
        spinnerEngine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String engine = parent.getItemAtPosition(position).toString();
                if (engine.equalsIgnoreCase("YOLO")) {
                    btnSelectYoloModel.setVisibility(VISIBLE);
                    btnSelectImageYolo.setVisibility(VISIBLE);
                    layoutCollage.setVisibility(GONE);
                    btnRunInference.setEnabled(selectedBitmapYolo != null && chosenYoloModel != null);
                    operatingLayout.setVisibility(GONE);
                    tvInputSetting.setVisibility(GONE);
                    tvStatus.setVisibility(GONE);
                } else if (engine.equalsIgnoreCase("Collage")) {
                    layoutCollage.setVisibility(VISIBLE);
                    btnSelectYoloModel.setVisibility(VISIBLE); // Se requiere modelo YOLO para inferir sobre collage
                    btnSelectImageYolo.setVisibility(GONE);
                    btnRunInference.setEnabled(selectedBitmap1 != null && selectedBitmap2 != null && chosenYoloModel != null);
                    operatingLayout.setVisibility(GONE);
                    tvInputSetting.setVisibility(GONE);
                    tvStatus.setVisibility(GONE);
                } else if (engine.equalsIgnoreCase("MLKit")) {
                    btnSelectYoloModel.setVisibility(GONE);
                    btnSelectImageYolo.setVisibility(VISIBLE);
                    layoutCollage.setVisibility(GONE);
                    btnRunInference.setEnabled(selectedBitmapYolo != null);
                    operatingLayout.setVisibility(GONE);
                    tvInputSetting.setVisibility(GONE);
                    tvStatus.setVisibility(GONE);
                } else if(engine.equalsIgnoreCase("paddle")) {
                    btnSelectYoloModel.setVisibility(GONE);
                    btnSelectImageYolo.setVisibility(VISIBLE);
                    layoutCollage.setVisibility(GONE);
                    btnRunInference.setEnabled(selectedBitmapYolo != null);
                    boolean settingsChanged = false;
                    boolean model_settingsChanged = false;
                    String model_path = getString(R.string.MODEL_PATH_DEFAULT);
                    String label_path = getString(R.string.LABEL_PATH_DEFAULT);
                    String image_path = getString(R.string.IMAGE_PATH_DEFAULT);
                    model_settingsChanged |= !model_path.equalsIgnoreCase(modelPath);
                    settingsChanged |= !label_path.equalsIgnoreCase(labelPath);
                    settingsChanged |= !image_path.equalsIgnoreCase(imagePath);
                    int cpu_thread_num = Integer.parseInt(getString(R.string.CPU_THREAD_NUM_DEFAULT));
                    model_settingsChanged |= cpu_thread_num != cpuThreadNum;
                    String cpu_power_mode =
                            getString(R.string.CPU_POWER_MODE_DEFAULT);
                    model_settingsChanged |= !cpu_power_mode.equalsIgnoreCase(cpuPowerMode);

                    int det_long_size = Integer.parseInt(getString(R.string.DET_LONG_SIZE_DEFAULT));
                    settingsChanged |= det_long_size != detLongSize;
                    float score_threshold =
                            Float.parseFloat(getString(R.string.SCORE_THRESHOLD_DEFAULT));
                    settingsChanged |= scoreThreshold != score_threshold;
                    if (settingsChanged) {
                        labelPath = label_path;
                        imagePath = image_path;
                        detLongSize = det_long_size;
                        scoreThreshold = score_threshold;
                    }
                    if (model_settingsChanged) {
                        modelPath = model_path;
                        cpuThreadNum = cpu_thread_num;
                        cpuPowerMode = cpu_power_mode;
                        // Update UI
                        tvInputSetting.setText("Model: " + modelPath.substring(modelPath.lastIndexOf("/") + 1) + "\nCPU Thread Num: " + cpuThreadNum + "\nCPU Power Mode: " + cpuPowerMode);
                        tvInputSetting.scrollTo(0, 0);
                        // Reload model if configure has been changed
                        loadModel();
                    }
                    operatingLayout.setVisibility(VISIBLE);
                    tvInputSetting.setVisibility(VISIBLE);
                    tvStatus.setVisibility(VISIBLE);
                }else{

                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Botón para seleccionar modelo YOLO (visible en modos YOLO y Collage)
        btnSelectYoloModel.setOnClickListener(v -> showYoloModelSelectionDialog());

        // Botón para seleccionar imagen en modos YOLO, Paddle, MLKit
        btnSelectImageYolo.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_SELECT_IMAGE_YOLO);
        });

        // Botones para seleccionar imágenes en modo Collage
        btnSelectImage1.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_SELECT_IMAGE1);
        });
        btnSelectImage2.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_SELECT_IMAGE2);
        });

        // Botón de ejecución
        btnRunInference.setOnClickListener(v -> {
            String engine = spinnerEngine.getSelectedItem().toString();
            runSelectedEngine(engine);
        });

        //paddle
        receiver = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case RESPONSE_LOAD_MODEL_SUCCESSED:
                        if (pbLoadModel != null && pbLoadModel.isShowing()) {
                            pbLoadModel.dismiss();
                        }
                        onLoadModelSuccessed();
                        break;
                    case RESPONSE_LOAD_MODEL_FAILED:
                        if (pbLoadModel != null && pbLoadModel.isShowing()) {
                            pbLoadModel.dismiss();
                        }
                        Toast.makeText(MainActivity.this, "Load model failed!", Toast.LENGTH_SHORT).show();
                        onLoadModelFailed();
                        break;
                    case RESPONSE_RUN_MODEL_SUCCESSED:
                        if (pbRunModel != null && pbRunModel.isShowing()) {
                            pbRunModel.dismiss();
                        }
                        onRunModelSuccessed();
                        break;
                    case RESPONSE_RUN_MODEL_FAILED:
                        if (pbRunModel != null && pbRunModel.isShowing()) {
                            pbRunModel.dismiss();
                        }
                        Toast.makeText(MainActivity.this, "Run model failed!", Toast.LENGTH_SHORT).show();
                        onRunModelFailed();
                        break;
                    default:
                        break;
                }
            }
        };

        worker = new HandlerThread("Predictor Worker");
        worker.start();
        sender = new Handler(worker.getLooper()) {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case REQUEST_LOAD_MODEL:
                        // Load model and reload test image
                        if (onLoadModel()) {
                            receiver.sendEmptyMessage(RESPONSE_LOAD_MODEL_SUCCESSED);
                        } else {
                            receiver.sendEmptyMessage(RESPONSE_LOAD_MODEL_FAILED);
                        }
                        break;
                    case REQUEST_RUN_MODEL:
                        // Run model if model is loaded
                        if (onRunModel()) {
                            receiver.sendEmptyMessage(RESPONSE_RUN_MODEL_SUCCESSED);
                        } else {
                            receiver.sendEmptyMessage(RESPONSE_RUN_MODEL_FAILED);
                        }
                        break;
                    default:
                        break;
                }
            }
        };
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                Bitmap bmp = loadBitmapFromUri(uri);
                String engine = spinnerEngine.getSelectedItem().toString();
                if (requestCode == REQUEST_CODE_SELECT_IMAGE_YOLO) {
                    selectedBitmapYolo = bmp;
                    imageView.setImageBitmap(bmp);
                    tvResults.setText("Imagen seleccionada (" + engine + ")");
                    btnRunInference.setEnabled(chosenYoloModel != null && selectedBitmapYolo != null);
                } else if (requestCode == REQUEST_CODE_SELECT_IMAGE1) {
                    selectedBitmap1 = bmp;
                    tvResults.setText("Imagen 1 seleccionada (Collage)");
                    btnRunInference.setEnabled(selectedBitmap1 != null && selectedBitmap2 != null && chosenYoloModel != null);
                } else if (requestCode == REQUEST_CODE_SELECT_IMAGE2) {
                    selectedBitmap2 = bmp;
                    tvResults.setText("Imagen 2 seleccionada (Collage)");
                    btnRunInference.setEnabled(selectedBitmap1 != null && selectedBitmap2 != null && chosenYoloModel != null);
                }
                if (engine.equalsIgnoreCase("paddle")) {
                    btnRunInference.setEnabled(true);
                }
            } catch (IOException e) {
                e.printStackTrace();
                tvResults.setText("Error al cargar imagen: " + e.getMessage());
            }
        }
    }

    private Bitmap loadBitmapFromUri(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        Bitmap bitmap = BitmapFactory.decodeStream(is);
        if (is != null) is.close();
        return bitmap;
    }

    /**
     * Ejecuta la acción según el motor seleccionado:
     * - MLKit: Ejecuta OCR sobre la imagen seleccionada y muestra el texto detectado y el tiempo de inferencia.
     * - YOLO: Ejecuta inferencia sobre la imagen seleccionada y muestra la concatenación de class names (sin separador)
     * y el promedio de confianza.
     * - Collage: Crea un collage en forma de cuadrícula 9x9 (128x128) con las imágenes seleccionadas, luego
     * ejecuta inferencia YOLO sobre el collage y muestra el resultado.
     * - Paddle: Muestra mensaje "lógica no implementada".
     */
    private void runSelectedEngine(String engine) {
        long start = System.currentTimeMillis();
        if (engine.equalsIgnoreCase("MLKit")) {
            tvResults.setText("Ejecutando OCR con MLKit...");
            // Aseguramos un tamaño mínimo de imagen para MLKit
            Bitmap resizedBitmap = ensureMinimumSize(selectedBitmapYolo, 32, 32);
            mlKitHelper.runOcr(resizedBitmap, new MlKitOcrHelper.OcrCallback() {
                @Override
                public void onSuccess(String recognizedText, float confidence) {
                    long end = System.currentTimeMillis();
                    long elapsed = end - start;
                    runOnUiThread(() -> tvResults.setText("Texto detectado:\n" + recognizedText +
                            "\nTiempo de inferencia: " + elapsed + " ms"));
                }

                @Override
                public void onError(Exception e) {
                    long end = System.currentTimeMillis();
                    long elapsed = end - start;
                    runOnUiThread(() -> tvResults.setText("Error en OCR: " + e.getMessage() +
                            "\nTiempo de inferencia: " + elapsed + " ms"));
                }
            });
        } else if (engine.equalsIgnoreCase("YOLO")) {
            if (chosenYoloModel == null) {
                tvResults.setText("Seleccione un modelo YOLO.");
                return;
            }
            if (selectedBitmapYolo == null) {
                tvResults.setText("Seleccione una imagen.");
                return;
            }
            YoloTFLiteHelper helper = new YoloTFLiteHelper(
                    this,
                    "models/yolo/" + chosenYoloModel,
                    null, // labelPath
                    null,
                    message -> tvResults.setText(message)
            );
            List<YoloTFLiteHelper.YoloDetection> detections = helper.runInference(
                    selectedBitmapYolo,
                    selectedBitmapYolo.getWidth(),
                    selectedBitmapYolo.getHeight()
            );
            StringBuilder classesConcatenated = new StringBuilder();
            float sumConfidence = 0f;
            for (YoloTFLiteHelper.YoloDetection det : detections) {
                classesConcatenated.append(det.label);
                sumConfidence += det.confidence;
            }
            float avgConfidence = detections.isEmpty() ? 0 : sumConfidence / detections.size();
            Bitmap annotated = selectedBitmapYolo.copy(Bitmap.Config.ARGB_8888, true);
            drawDetections(annotated, detections);
            imageView.setImageBitmap(annotated);
            tvResults.setText("YOLO detectó: " + classesConcatenated.toString() +
                    "\nPromedio de confianza: " + String.format("%.2f", avgConfidence));
        } else if (engine.equalsIgnoreCase("Collage")) {
            if (selectedBitmap1 == null || selectedBitmap2 == null || chosenYoloModel == null) {
                tvResults.setText("Seleccione ambas imágenes para collage y un modelo YOLO.");
                return;
            }
            // Crear un collage en forma de cuadrícula 9x9 con dimensiones 128x128
            List<Bitmap> imagesForCollage = new ArrayList<>();
            // En este ejemplo, agregamos dos imágenes; el helper las colocará en orden y las celdas faltantes se quedarán en negro.
            imagesForCollage.add(selectedBitmap1);
            imagesForCollage.add(selectedBitmap2);
            Bitmap collage = com.example.test_ocr_sbw.utils.CollageHelper.createGridCollage(imagesForCollage, 128, 128);
            // Ejecutar inferencia YOLO sobre el collage
            YoloTFLiteHelper helper = new YoloTFLiteHelper(
                    this,
                    "models/yolo/" + chosenYoloModel,
                    null,
                    null,
                    message -> tvResults.setText(message)
            );
            List<YoloTFLiteHelper.YoloDetection> detections = helper.runInference(
                    collage,
                    collage.getWidth(),
                    collage.getHeight()
            );
            StringBuilder classesConcatenated = new StringBuilder();
            float sumConfidence = 0f;
            for (YoloTFLiteHelper.YoloDetection det : detections) {
                classesConcatenated.append(det.label);
                sumConfidence += det.confidence;
            }
            float avgConfidence = detections.isEmpty() ? 0 : sumConfidence / detections.size();
            Bitmap annotated = collage.copy(Bitmap.Config.ARGB_8888, true);
            drawDetections(annotated, detections);
            imageView.setImageBitmap(annotated);
            tvResults.setText("Collage YOLO detectó: " + classesConcatenated.toString() +
                    "\nPromedio de confianza: " + String.format("%.2f", avgConfidence));
        } else if (engine.equalsIgnoreCase("Paddle")) {
//            tvResults.setText("Paddle seleccionado (lógica no implementada).");
            runPaddleModel();
            return;
        } else {
            tvResults.setText("Motor desconocido.");
        }
        long end = System.currentTimeMillis();
        tvResults.append("\nTiempo(ms) total: " + (end - start));
    }

    private void drawDetections(Bitmap bitmap, List<YoloTFLiteHelper.YoloDetection> detections) {
        Canvas canvas = new Canvas(bitmap);
        Paint boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);  // Texto en blanco
        textPaint.setTextSize(30f);

        for (YoloTFLiteHelper.YoloDetection det : detections) {
            canvas.drawRect(det.left, det.top, det.right, det.bottom, boxPaint);
            String label = "Clase: " + det.label + ", Conf: " + String.format("%.2f", det.confidence);
            canvas.drawText(label, det.left, det.top - 10, textPaint);
            Log.d("Detection", "Clase: " + det.label + ", Confianza: " + det.confidence);
            Log.d("Detection", "Bounding Box: [" + det.left + ", " + det.top + ", " + det.right + ", " + det.bottom + "]");
        }
    }

    private Bitmap ensureMinimumSize(Bitmap bitmap, int minWidth, int minHeight) {
        if (bitmap.getWidth() >= minWidth && bitmap.getHeight() >= minHeight) {
            return bitmap;
        }
        float scaleFactor = Math.max((float) minWidth / bitmap.getWidth(), (float) minHeight / bitmap.getHeight());
        int newWidth = Math.round(bitmap.getWidth() * scaleFactor);
        int newHeight = Math.round(bitmap.getHeight() * scaleFactor);
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    /**
     * Muestra un diálogo para seleccionar un modelo YOLO desde assets/models/yolo/.
     */
    private void showYoloModelSelectionDialog() {
        try {
            AssetManager am = getAssets();
            String[] allFiles = am.list("models/yolo");
            if (allFiles == null || allFiles.length == 0) {
                tvResults.setText("No hay archivos en assets/models/yolo/");
                return;
            }
            List<String> tfliteFiles = new ArrayList<>();
            for (String f : allFiles) {
                if (f.endsWith(".tflite")) {
                    tfliteFiles.add(f);
                }
            }
            if (tfliteFiles.isEmpty()) {
                tvResults.setText("No hay modelos .tflite en assets/models/yolo/");
                return;
            }
            String[] items = tfliteFiles.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("Seleccionar modelo YOLO")
                    .setItems(items, (dialog, which) -> {
                        chosenYoloModel = items[which];
                        tvResults.setText("YOLO model: " + chosenYoloModel);
                        String engine = spinnerEngine.getSelectedItem().toString();
                        if (engine.equalsIgnoreCase("YOLO")) {
                            btnRunInference.setEnabled(selectedBitmapYolo != null && chosenYoloModel != null);
                        } else if (engine.equalsIgnoreCase("Collage")) {
                            btnRunInference.setEnabled(selectedBitmap1 != null && selectedBitmap2 != null && chosenYoloModel != null);
                        }
                    })
                    .show();
        } catch (IOException e) {
            e.printStackTrace();
            tvResults.setText("Error listando assets: " + e.getMessage());
        }
    }

    /**
     * paddle
     **/

    public void loadModel() {
        pbLoadModel = ProgressDialog.show(this, "", "loading model...", false, false);
        sender.sendEmptyMessage(REQUEST_LOAD_MODEL);
    }

    public boolean onLoadModel() {
        if (predictor.isLoaded()) {
            predictor.releaseModel();
        }
        return predictor.init(MainActivity.this, modelPath, labelPath, 0, cpuThreadNum,
                cpuPowerMode,
                detLongSize, scoreThreshold);
    }

    public void onLoadModelSuccessed() {
        // Load test image from path and run model
        tvInputSetting.setText("Model: " + modelPath.substring(modelPath.lastIndexOf("/") + 1) + "\nCPU Thread Num: " + cpuThreadNum + "\nCPU Power Mode: " + cpuPowerMode);
        tvInputSetting.scrollTo(0, 0);
        tvStatus.setText("STATUS: load model successed");

    }

    public void onLoadModelFailed() {
        tvStatus.setText("STATUS: load model failed");
    }

    public void onRunModelSuccessed() {
        tvStatus.setText("STATUS: run model successed");
        // Obtain results and update UI
        Bitmap outputImage = predictor.outputImage();
        if (outputImage != null) {
            imageView.setImageBitmap(outputImage);
        }
        tvResults.setText(predictor.outputResult());
        tvResults.scrollTo(0, 0);
    }

    public void onRunModelFailed() {
        tvStatus.setText("STATUS: run model failed");
    }

    public void runModel() {
        pbRunModel = ProgressDialog.show(this, "", "running model...", false, false);
        sender.sendEmptyMessage(REQUEST_RUN_MODEL);
    }

    public boolean onRunModel() {
        String run_mode = spRunMode.getSelectedItem().toString();
        int run_det = run_mode.contains("Detection") ? 1 : 0;
        int run_cls = run_mode.contains("Classification") ? 1 : 0;
        int run_rec = run_mode.contains("Recognition") ? 1 : 0;
        return predictor.isLoaded() && predictor.runModel(run_det, run_cls, run_rec);
    }

    public void runPaddleModel() {
        Bitmap image = ((BitmapDrawable) imageView.getDrawable()).getBitmap();
        if (image == null) {
            tvStatus.setText("STATUS: image is not exists");
        } else if (!predictor.isLoaded()) {
            tvStatus.setText("STATUS: model is not loaded");
        } else {
            tvStatus.setText("STATUS: run model ...... ");
            predictor.setInputImage(image);
            runModel();
        }
    }
}
