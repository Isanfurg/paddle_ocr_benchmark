package com.example.test_ocr_sbw;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    public static final int OPEN_GALLERY_REQUEST_CODE = 0;
    public static final int TAKE_PHOTO_REQUEST_CODE = 1;

    public static final int REQUEST_LOAD_MODEL = 0;
    public static final int REQUEST_RUN_MODEL = 1;
    public static final int RESPONSE_LOAD_MODEL_SUCCESSED = 0;
    public static final int RESPONSE_LOAD_MODEL_FAILED = 1;
    public static final int RESPONSE_RUN_MODEL_SUCCESSED = 2;
    public static final int RESPONSE_RUN_MODEL_FAILED = 3;

    protected ProgressDialog pbLoadModel = null;
    protected ProgressDialog pbRunModel = null;

    protected Handler receiver = null; // Receive messages from worker thread
    protected Handler sender = null; // Send command to worker thread
    protected HandlerThread worker = null; // Worker thread to load&run model

    // UI components of object detection
    protected TextView tvInputSetting;
    protected TextView tvStatus;
    protected ImageView ivInputImage;
    protected TextView tvOutputResult;
    protected TextView tvInferenceTime;
    protected Spinner spRunMode;

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

    protected Predictor predictor = new Predictor();

    private Bitmap cur_predict_image = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Clear all setting items to avoid app crashing due to the incorrect settings
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();

        // Setup the UI components
        tvInputSetting = findViewById(R.id.tv_input_setting);
        tvStatus = findViewById(R.id.tv_model_img_status);
        ivInputImage = findViewById(R.id.iv_input_image);
        tvInferenceTime = findViewById(R.id.tv_inference_time);
        tvOutputResult = findViewById(R.id.tv_output_result);
        spRunMode = findViewById(R.id.sp_run_mode);
        tvInputSetting.setMovementMethod(ScrollingMovementMethod.getInstance());
        tvOutputResult.setMovementMethod(ScrollingMovementMethod.getInstance());

        // Prepare the worker thread for mode loading and inference
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
    protected void onResume() {
        super.onResume();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean settingsChanged = false;
        boolean model_settingsChanged = false;
        String model_path = sharedPreferences.getString(getString(R.string.MODEL_PATH_KEY),
                getString(R.string.MODEL_PATH_DEFAULT));
        String label_path = sharedPreferences.getString(getString(R.string.LABEL_PATH_KEY),
                getString(R.string.LABEL_PATH_DEFAULT));
        String image_path = sharedPreferences.getString(getString(R.string.IMAGE_PATH_KEY),
                getString(R.string.IMAGE_PATH_DEFAULT));
        model_settingsChanged |= !model_path.equalsIgnoreCase(modelPath);
        settingsChanged |= !label_path.equalsIgnoreCase(labelPath);
        settingsChanged |= !image_path.equalsIgnoreCase(imagePath);
        int cpu_thread_num = Integer.parseInt(sharedPreferences.getString(getString(R.string.CPU_THREAD_NUM_KEY),
                getString(R.string.CPU_THREAD_NUM_DEFAULT)));
        model_settingsChanged |= cpu_thread_num != cpuThreadNum;
        String cpu_power_mode =
                sharedPreferences.getString(getString(R.string.CPU_POWER_MODE_KEY),
                        getString(R.string.CPU_POWER_MODE_DEFAULT));
        model_settingsChanged |= !cpu_power_mode.equalsIgnoreCase(cpuPowerMode);

        int det_long_size = Integer.parseInt(sharedPreferences.getString(getString(R.string.DET_LONG_SIZE_KEY),
                getString(R.string.DET_LONG_SIZE_DEFAULT)));
        settingsChanged |= det_long_size != detLongSize;
        float score_threshold =
                Float.parseFloat(sharedPreferences.getString(getString(R.string.SCORE_THRESHOLD_KEY),
                        getString(R.string.SCORE_THRESHOLD_DEFAULT)));
        settingsChanged |= scoreThreshold != score_threshold;
        if (settingsChanged) {
            labelPath = label_path;
            imagePath = image_path;
            detLongSize = det_long_size;
            scoreThreshold = score_threshold;
            set_img();
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
    }

    public void loadModel() {
        pbLoadModel = ProgressDialog.show(this, "", "loading model...", false, false);
        sender.sendEmptyMessage(REQUEST_LOAD_MODEL);
    }

    public void runModel() {
        pbRunModel = ProgressDialog.show(this, "", "running model...", false, false);
        sender.sendEmptyMessage(REQUEST_RUN_MODEL);
    }

    public boolean onLoadModel() {
        if (predictor.isLoaded()) {
            predictor.releaseModel();
        }
        return predictor.init(MainActivity.this, modelPath, labelPath, 0, cpuThreadNum,
                cpuPowerMode,
                detLongSize, scoreThreshold);
    }

    public boolean onRunModel() {
        String run_mode = spRunMode.getSelectedItem().toString();
        int run_det = run_mode.contains("Detection") ? 1 : 0;
        int run_cls = run_mode.contains("Classification") ? 1 : 0;
        int run_rec = run_mode.contains("Recognition") ? 1 : 0;
        return predictor.isLoaded() && predictor.runModel(run_det, run_cls, run_rec);
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
        tvInferenceTime.setText("Inference time: " + predictor.inferenceTime() + " ms");
        Bitmap outputImage = predictor.outputImage();
        if (outputImage != null) {
            ivInputImage.setImageBitmap(outputImage);
        }
        tvOutputResult.setText(predictor.outputResult());
        tvOutputResult.scrollTo(0, 0);
    }

    public void onRunModelFailed() {
        tvStatus.setText("STATUS: run model failed");
    }

    public void set_img() {
        // Load test image from path and run model
        try {
            assetManager = getAssets();
            InputStream in = assetManager.open(imagePath);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            cur_predict_image = bmp;
            ivInputImage.setImageBitmap(bmp);
        } catch (IOException e) {
            Toast.makeText(MainActivity.this, "Load image failed!", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }


    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isLoaded = predictor.isLoaded();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, OPEN_GALLERY_REQUEST_CODE);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case OPEN_GALLERY_REQUEST_CODE:
                    if (data == null) {
                        break;
                    }
                    try {
                        ContentResolver resolver = getContentResolver();
                        Uri uri = data.getData();
                        Bitmap image = MediaStore.Images.Media.getBitmap(resolver, uri);
                        String[] proj = {MediaStore.Images.Media.DATA};
                        Cursor cursor = managedQuery(uri, proj, null, null, null);
                        cursor.moveToFirst();
                        if (image != null) {
                            cur_predict_image = image;
                            ivInputImage.setImageBitmap(image);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, e.toString());
                    }
                    break;
                case TAKE_PHOTO_REQUEST_CODE:
                    if (currentPhotoPath != null) {
                        ExifInterface exif = null;
                        try {
                            exif = new ExifInterface(currentPhotoPath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_UNDEFINED);
                        Log.i(TAG, "rotation " + orientation);
                        Bitmap image = BitmapFactory.decodeFile(currentPhotoPath);
                        image = Utils.rotateBitmap(image, orientation);
                        if (image != null) {
                            cur_predict_image = image;
                            ivInputImage.setImageBitmap(image);
                        }
                    } else {
                        Log.e(TAG, "currentPhotoPath is null");
                    }
                    break;
                default:
                    break;
            }
        }
    }


    public void btn_run_model_click(View view) {
        Bitmap image = ((BitmapDrawable) ivInputImage.getDrawable()).getBitmap();
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

    public void btn_choice_img_click(View view) {
        openGallery();

    }

    @Override
    protected void onDestroy() {
        if (predictor != null) {
            predictor.releaseModel();
        }
        worker.quit();
        super.onDestroy();
    }
}
