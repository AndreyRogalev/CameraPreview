// --- Полный путь к файлу: /root/CameraPreview/app/src/main/java/com/example/camerapreview/MainActivity.java ---
package com.example.camerapreview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.Group;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.slider.Slider;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;


public class MainActivity extends AppCompatActivity implements View.OnTouchListener, LayerAdapter.OnLayerVisibilityChangedListener {

    private static final String TAG = "CameraPreviewApp";
    private static final String PARAMS_FILE_NAME_INTERNAL = "image_params_internal.json";
    private static final String PARAMS_FILE_NAME_SUGGESTION = "image_params.json";

    private static final String[] PENCIL_HARDNESS = { "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F", "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B" };
    private static final int GRAY_LEVELS = PENCIL_HARDNESS.length;
    private static final float GRAY_RANGE_SIZE = 256.0f / GRAY_LEVELS;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private static final float PAN_STEP = 20f;

    private PreviewView previewView;
    private Slider zoomSlider;
    private Button loadImageButton;
    private ImageView overlayImageView;
    private Slider transparencySlider;
    private SwitchCompat pencilModeSwitch;
    private CheckBox greenModeCheckbox;
    private Button layerSelectButton;
    private Button saveParamsButton;
    private Button loadParamsButton;
    private Group controlsGroup;
    private CheckBox controlsVisibilityCheckbox;
    private CheckBox showLayersWhenControlsHiddenCheckbox;
    private SwitchCompat linkZoomSwitch;

    private Button panUpButton;
    private Button panDownButton;
    private Button panLeftButton;
    private Button panRightButton;
    private Button resetPanButton;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;

    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private int mode = NONE;
    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;
    private float oldAngle = 0f;
    private ScaleGestureDetector scaleGestureDetector;

    private float currentPanX = 0f;
    private float currentPanY = 0f;

    private boolean isPencilMode = false;
    private boolean isGreenMode = false;
    private boolean[] layerVisibility;
    private Bitmap originalBitmap = null;
    private Bitmap processedBitmap = null;
    private Bitmap finalCompositeBitmap = null;

    private boolean isZoomLinked = false;
    private float initialCameraZoomRatio = 1f;
    private float initialImageScale = 1f;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted != null) {
                        allPermissionsGranted &= granted;
                    } else {
                        allPermissionsGranted = false;
                    }
                }
                if (allPermissionsGranted) {
                    Log.i(TAG, "Camera permission granted.");
                    startCamera();
                } else {
                    Log.w(TAG, "Camera permission not granted.");
                    Toast.makeText(this, "Разрешение на камеру не предоставлено", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Log.i(TAG, "Image URI selected: " + uri);
                    recycleAllBitmaps();
                    loadOriginalBitmap(uri);
                } else {
                    Log.i(TAG, "No image selected by user.");
                }
            });

    private final ActivityResultLauncher<String[]> openParamsFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    Log.i(TAG, "Params file URI selected for loading: " + uri);
                    loadParametersFromFile(uri);
                } else {
                    Log.i(TAG, "No params file selected by user for loading.");
                }
            });

    private final ActivityResultLauncher<String> createParamsFileLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null) {
                    Log.i(TAG, "Params file URI selected for saving: " + uri);
                    saveParametersToFile(uri);
                } else {
                    Log.i(TAG, "No params file URI selected by user for saving.");
                }
            });


    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity starting");

        previewView = findViewById(R.id.previewView);
        zoomSlider = findViewById(R.id.zoom_slider);
        loadImageButton = findViewById(R.id.load_image_button);
        overlayImageView = findViewById(R.id.overlayImageView);
        transparencySlider = findViewById(R.id.transparency_slider);
        pencilModeSwitch = findViewById(R.id.pencilModeSwitch);
        greenModeCheckbox = findViewById(R.id.greenModeCheckbox);
        layerSelectButton = findViewById(R.id.layerSelectButton);
        saveParamsButton = findViewById(R.id.saveParamsButton);
        loadParamsButton = findViewById(R.id.loadParamsButton);
        controlsGroup = findViewById(R.id.controlsGroup);
        controlsVisibilityCheckbox = findViewById(R.id.controlsVisibilityCheckbox);
        showLayersWhenControlsHiddenCheckbox = findViewById(R.id.showLayersWhenControlsHiddenCheckbox);
        linkZoomSwitch = findViewById(R.id.linkZoomSwitch);
        panUpButton = findViewById(R.id.panUpButton);
        panDownButton = findViewById(R.id.panDownButton);
        panLeftButton = findViewById(R.id.panLeftButton);
        panRightButton = findViewById(R.id.panRightButton);
        resetPanButton = findViewById(R.id.resetPanButton);


        if (previewView == null || zoomSlider == null || loadImageButton == null ||
                overlayImageView == null || transparencySlider == null || pencilModeSwitch == null ||
                greenModeCheckbox == null || layerSelectButton == null ||
                saveParamsButton == null || loadParamsButton == null ||
                panUpButton == null || panDownButton == null || panLeftButton == null ||
                panRightButton == null || resetPanButton == null ||
                controlsGroup == null || controlsVisibilityCheckbox == null ||
                showLayersWhenControlsHiddenCheckbox == null ||
                linkZoomSwitch == null) {
            Log.e(TAG, "onCreate: One or more views not found! Check layout file IDs.");
            Toast.makeText(this, "Критическая ошибка: Не найдены элементы интерфейса", Toast.LENGTH_LONG).show();
            finish();
            return;
        } else {
            Log.d(TAG, "onCreate: All views found");
        }

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        overlayImageView.setOnTouchListener(this);
        overlayImageView.setScaleType(ImageView.ScaleType.MATRIX);

        layerVisibility = new boolean[GRAY_LEVELS];
        Arrays.fill(layerVisibility, true);

        setupCameraZoomSliderListener();
        setupLoadImageButtonListener();
        setupTransparencySliderListener();
        setupPencilModeSwitchListener();
        setupGreenModeCheckboxListener();
        setupLayerSelectButtonListener();
        setupSaveParamsButtonListener();
        setupLoadParamsButtonListener();
        setupPanButtonsListeners();
        setupControlsVisibilityListener();
        setupShowLayersWhenControlsHiddenCheckboxListener();
        setupLinkZoomSwitchListener();

        previewView.post(this::hideSystemUI);
        overlayImageView.setVisibility(View.GONE);
        transparencySlider.setVisibility(View.GONE);
        transparencySlider.setEnabled(false);
        linkZoomSwitch.setEnabled(false);
        greenModeCheckbox.setVisibility(View.GONE);

        boolean controlsInitiallyVisible = controlsVisibilityCheckbox.isChecked();
        controlsGroup.setVisibility(controlsInitiallyVisible ? View.VISIBLE : View.GONE);
        controlsVisibilityCheckbox.setText(controlsInitiallyVisible ? getString(R.string.controls_label) : "");

        updateSaveLoadParamsButtonsVisibility();
        updateShowLayersCheckboxVisibility();
        updateLayerButtonVisibility();
        updateGreenModeCheckboxVisibility();
        resetPreviewPan();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }
        Log.d(TAG, "onCreate: Setup complete");
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v.getId() != R.id.overlayImageView) return false;
        ImageView view = (ImageView) v;
        scaleGestureDetector.onTouchEvent(event);
        PointF curr = new PointF(event.getX(), event.getY());
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                savedMatrix.set(matrix);
                start.set(curr);
                mode = DRAG;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                oldDist = spacing(event);
                oldAngle = rotation(event);
                if (oldDist > 10f) {
                    savedMatrix.set(matrix);
                    midPoint(mid, event);
                    mode = ZOOM;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
                if (isZoomLinked) {
                    updateZoomLinkBaseline();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG) {
                    matrix.set(savedMatrix);
                    matrix.postTranslate(curr.x - start.x, curr.y - start.y);
                } else if (mode == ZOOM && event.getPointerCount() >= 2) {
                    float newDist = spacing(event);
                    float newAngle = rotation(event);
                    if (newDist > 10f) {
                        matrix.set(savedMatrix);
                        float scale = newDist / oldDist;
                        matrix.postScale(scale, scale, mid.x, mid.y);
                        float deltaAngle = newAngle - oldAngle;
                        matrix.postRotate(deltaAngle, mid.x, mid.y);
                    }
                }
                break;
        }
        view.setImageMatrix(matrix);
        return true;
    }

    private float spacing(MotionEvent event) { if (event.getPointerCount() < 2) return 0f; float x = event.getX(0) - event.getX(1); float y = event.getY(0) - event.getY(1); return (float) Math.sqrt(x * x + y * y); }
    private void midPoint(PointF point, MotionEvent event) { if (event.getPointerCount() < 2) return; float x = event.getX(0) + event.getX(1); float y = event.getY(0) + event.getY(1); point.set(x / 2, y / 2); }
    private float rotation(MotionEvent event) { if (event.getPointerCount() < 2) return 0f; double delta_x = (event.getX(0) - event.getX(1)); double delta_y = (event.getY(0) - event.getY(1)); double radians = Math.atan2(delta_y, delta_x); return (float) Math.toDegrees(radians); }
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener { @Override public boolean onScale(ScaleGestureDetector detector) { return true; } }

    private void setupLoadImageButtonListener() {
        loadImageButton.setOnClickListener(v -> {
            Log.i(TAG, "Load image button pressed.");
            try {
                pickImageLauncher.launch("image/*");
            } catch (Exception e) {
                Log.e(TAG, "Error launching image picker", e);
                Toast.makeText(this, "Не удалось открыть галерею", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupTransparencySliderListener() {
        transparencySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (overlayImageView.getVisibility() == View.VISIBLE && fromUser) {
                Log.v(TAG, "Transparency slider changed: " + value);
                overlayImageView.setAlpha(value);
            }
        });
    }

    private void setupPencilModeSwitchListener() {
        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "Pencil Mode Switch changed: " + isChecked);
            isPencilMode = isChecked;
            updateGreenModeCheckboxVisibility();
            if (isChecked) {
                if (processedBitmap == null && originalBitmap != null && !originalBitmap.isRecycled()) {
                    createProcessedBitmap();
                }
            } else {
                recycleBitmap(processedBitmap);
                processedBitmap = null;
                recycleBitmap(finalCompositeBitmap);
                finalCompositeBitmap = null;
                 if (greenModeCheckbox.isChecked()) {
                    isGreenMode = false;
                    greenModeCheckbox.setChecked(false);
                }
            }
            updateShowLayersCheckboxVisibility();
            updateLayerButtonVisibility();
            updateImageDisplay();
        });
    }

    private void setupGreenModeCheckboxListener() {
        greenModeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "Green Mode Checkbox changed: " + isChecked);
            isGreenMode = isChecked;
            if (isPencilMode) {
                createProcessedBitmap();
                updateImageDisplay();
            }
        });
    }

    private void setupLayerSelectButtonListener() {
        layerSelectButton.setOnClickListener(v -> {
            Log.d(TAG, "Layer select button clicked");
            showLayerSelectionDialog();
        });
    }

    private void setupSaveParamsButtonListener() {
        saveParamsButton.setOnClickListener(v -> {
            Log.d(TAG, "Save Parameters button clicked");
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    createParamsFileLauncher.launch(PARAMS_FILE_NAME_SUGGESTION);
                } else {
                    File internalFile = new File(getFilesDir(), PARAMS_FILE_NAME_INTERNAL);
                    saveParametersToFile(internalFile);
                }
            } else {
                Toast.makeText(this, "Сначала загрузите изображение", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupLoadParamsButtonListener() {
        loadParamsButton.setOnClickListener(v -> {
            Log.d(TAG, "Load Parameters button clicked");
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    openParamsFileLauncher.launch(new String[]{"application/json"});
                } else {
                    File internalFile = new File(getFilesDir(), PARAMS_FILE_NAME_INTERNAL);
                    if (internalFile.exists()) {
                        loadParametersFromFile(internalFile);
                    } else {
                        Toast.makeText(this, "Файл параметров не найден: " + PARAMS_FILE_NAME_INTERNAL, Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                Toast.makeText(this, "Сначала загрузите изображение", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupPanButtonsListeners() {
        panUpButton.setOnClickListener(v -> {
            currentPanY -= PAN_STEP;
            applyPreviewPan();
        });
        panDownButton.setOnClickListener(v -> {
            currentPanY += PAN_STEP;
            applyPreviewPan();
        });
        panLeftButton.setOnClickListener(v -> {
            currentPanX -= PAN_STEP;
            applyPreviewPan();
        });
        panRightButton.setOnClickListener(v -> {
            currentPanX += PAN_STEP;
            applyPreviewPan();
        });
        resetPanButton.setOnClickListener(v -> {
            resetPreviewPan();
        });
    }

    private void setupControlsVisibilityListener() {
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "Controls Visibility Checkbox changed: " + isChecked);
            controlsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            controlsVisibilityCheckbox.setText(isChecked ? getString(R.string.controls_label) : "");
            updateShowLayersCheckboxVisibility();
            updateLayerButtonVisibility();
        });
    }

    private void setupShowLayersWhenControlsHiddenCheckboxListener() {
        showLayersWhenControlsHiddenCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "Show Layers When Controls Hidden Checkbox changed: " + isChecked);
            updateLayerButtonVisibility();
        });
    }

    private void setupLinkZoomSwitchListener() {
        linkZoomSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isZoomLinked = isChecked;
            if (isChecked) {
                updateZoomLinkBaseline();
            } else {
                Log.i(TAG, "Zoom Link DISABLED.");
            }
        });
    }

    private void applyPreviewPan() {
        if (previewView != null) {
            previewView.setTranslationX(currentPanX);
            previewView.setTranslationY(currentPanY);
            Log.d(TAG, "Applied pan: X=" + currentPanX + ", Y=" + currentPanY);
        }
    }

    private void resetPreviewPan() {
        currentPanX = 0f;
        currentPanY = 0f;
        applyPreviewPan();
        Log.d(TAG, "Preview pan reset.");
    }

    private void loadOriginalBitmap(Uri imageUri) {
        Log.d(TAG, "loadOriginalBitmap started for URI: " + imageUri);
        try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) throw new IOException("Unable to open input stream for URI: " + imageUri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            originalBitmap = BitmapFactory.decodeStream(inputStream, null, options);
            if (originalBitmap != null) {
                Log.i(TAG, "Original bitmap loaded: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());
                linkZoomSwitch.setEnabled(true);
                if (isZoomLinked) {
                    isZoomLinked = false;
                    linkZoomSwitch.setChecked(false);
                }
                if (isPencilMode) createProcessedBitmap();
                updateSaveLoadParamsButtonsVisibility();
                updateGreenModeCheckboxVisibility();
                updateShowLayersCheckboxVisibility();
                updateLayerButtonVisibility();
                updateImageDisplay();
                overlayImageView.setVisibility(View.VISIBLE);
                transparencySlider.setVisibility(View.VISIBLE);
                transparencySlider.setEnabled(true);
                transparencySlider.setValue(1.0f);
                overlayImageView.setAlpha(1.0f);
                resetImageMatrix();
                resetPreviewPan();
                Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();
            } else {
                throw new IOException("BitmapFactory.decodeStream returned null for URI: " + imageUri);
            }
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error loading original bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти", Toast.LENGTH_LONG).show();
            clearImageRelatedData();
        } catch (Exception e) {
            Log.e(TAG, "Error loading original bitmap", e);
            Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show();
            clearImageRelatedData();
        }
    }

    private void clearImageRelatedData() {
        Log.d(TAG, "Clearing image related data");
        recycleAllBitmaps();
        if (overlayImageView != null) {
            overlayImageView.setImageBitmap(null);
            overlayImageView.setVisibility(View.GONE);
        }
        if (transparencySlider != null) {
            transparencySlider.setValue(1.0f);
            transparencySlider.setVisibility(View.GONE);
            transparencySlider.setEnabled(false);
        }
        if (isPencilMode && pencilModeSwitch != null) {
            isPencilMode = false;
            pencilModeSwitch.setChecked(false);
        }
        if (isGreenMode && greenModeCheckbox != null && !isPencilMode) {
            isGreenMode = false;
            greenModeCheckbox.setChecked(false);
        }
        if (isZoomLinked && linkZoomSwitch != null) {
            isZoomLinked = false;
            linkZoomSwitch.setChecked(false);
        }
        if (linkZoomSwitch != null) linkZoomSwitch.setEnabled(false);
        updateSaveLoadParamsButtonsVisibility();
        updateGreenModeCheckboxVisibility();
        updateShowLayersCheckboxVisibility();
        updateLayerButtonVisibility();
        resetPreviewPan();
    }

    private void updateSaveLoadParamsButtonsVisibility() {
        boolean imageLoaded = (originalBitmap != null && !originalBitmap.isRecycled());
        if (saveParamsButton != null) saveParamsButton.setVisibility(imageLoaded ? View.VISIBLE : View.GONE);
        if (loadParamsButton != null) loadParamsButton.setVisibility(imageLoaded ? View.VISIBLE : View.GONE);
    }

    private void saveParametersToFile(Uri uri) { /* ... */ }
    private void saveParametersToFile(File file) { /* ... */ }
    
    private JSONObject createParamsJson() {
        JSONObject paramsJson = new JSONObject();
        try {
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            JSONArray matrixJsonArray = new JSONArray();
            for (float value : matrixValues) {
                matrixJsonArray.put(value);
            }
            paramsJson.put("matrix_values", matrixJsonArray);
            paramsJson.put("alpha", overlayImageView.getAlpha());
            paramsJson.put("zoom_linked", isZoomLinked);
            if (isZoomLinked) {
                paramsJson.put("initial_camera_zoom_ratio_for_link", initialCameraZoomRatio);
                paramsJson.put("initial_image_scale_for_link", initialImageScale);
            }
            paramsJson.put("is_green_mode", isGreenMode);
            paramsJson.put("preview_pan_x", currentPanX);
            paramsJson.put("preview_pan_y", currentPanY);
            return paramsJson;
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON for parameters", e);
            return null;
        }
    }

    private void loadParametersFromFile(Uri uri) { /* ... */ }
    private void loadParametersFromFile(File file) { /* ... */ }

    private void applyParamsFromJson(String jsonString) {
        try {
            JSONObject paramsJson = new JSONObject(jsonString);

            JSONArray matrixJsonArray = paramsJson.getJSONArray("matrix_values");
            if (matrixJsonArray.length() == 9) {
                float[] matrixValues = new float[9];
                for (int i = 0; i < 9; i++) {
                    matrixValues[i] = (float) matrixJsonArray.getDouble(i);
                }
                matrix.setValues(matrixValues);
                overlayImageView.setImageMatrix(matrix);
                savedMatrix.set(matrix);
            }

            float alpha = (float) paramsJson.getDouble("alpha");
            overlayImageView.setAlpha(alpha);
            transparencySlider.setValue(alpha);

            boolean zoomLinkedFromFile = paramsJson.getBoolean("zoom_linked");
            isZoomLinked = zoomLinkedFromFile;
            linkZoomSwitch.setChecked(zoomLinkedFromFile);

            if (zoomLinkedFromFile) {
                if (paramsJson.has("initial_camera_zoom_ratio_for_link") && paramsJson.has("initial_image_scale_for_link")) {
                    initialCameraZoomRatio = (float) paramsJson.getDouble("initial_camera_zoom_ratio_for_link");
                    initialImageScale = (float) paramsJson.getDouble("initial_image_scale_for_link");
                } else {
                    updateZoomLinkBaseline();
                }
            }
            
            if (paramsJson.has("is_green_mode")) {
                isGreenMode = paramsJson.getBoolean("is_green_mode");
                greenModeCheckbox.setChecked(isGreenMode);
            } else {
                isGreenMode = false;
                greenModeCheckbox.setChecked(false);
            }

            if (paramsJson.has("preview_pan_x") && paramsJson.has("preview_pan_y")) {
                currentPanX = (float) paramsJson.getDouble("preview_pan_x");
                currentPanY = (float) paramsJson.getDouble("preview_pan_y");
                applyPreviewPan();
            } else {
                resetPreviewPan();
            }

            if (isPencilMode) {
                createProcessedBitmap();
                updateImageDisplay();
            }

            Toast.makeText(this, "Параметры загружены", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Parameters loaded and applied successfully.");

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON from parameters file", e);
            Toast.makeText(this, "Ошибка разбора JSON", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateZoomLinkBaseline() { /* ... */ }
    private float getMatrixScale(Matrix matrix) { /* ... */ }
    private void createProcessedBitmap() { /* ... */ }
    private Bitmap createCompositeGrayscaleBitmap() { /* ... */ }
    private void updateImageDisplay() { /* ... */ }
    private void showLayerSelectionDialog() { /* ... */ }
    @Override public void onLayerVisibilityChanged(int position, boolean isVisible) { /* ... */ }
    private void recycleBitmap(Bitmap bitmap) { /* ... */ }
    private void recycleAllBitmaps() { /* ... */ }
    private void resetImageMatrix() { /* ... */ }
    private void updateGreenModeCheckboxVisibility() { /* ... */ }
    private void updateShowLayersCheckboxVisibility() { /* ... */ }
    private void updateLayerButtonVisibility() { /* ... */ }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
        recycleAllBitmaps();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void hideSystemUI() {
        Log.v(TAG, "Attempting to hide system UI");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                Log.w(TAG, "WindowInsetsController is null, falling back to old API");
                hideSystemUIOldApi();
            }
        } else {
            hideSystemUIOldApi();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUIOldApi() {
        Log.v(TAG, "Using old API (SYSTEM_UI_FLAGs) to hide system UI");
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        } else {
            Log.w(TAG, "DecorView is null (old API)");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        Log.i(TAG, "Requesting camera permissions...");
        requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
    }

    private void startCamera() {
        Log.i(TAG, "startCamera called");
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Log.i(TAG, "CameraProvider obtained successfully.");
                bindPreviewUseCase();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error getting CameraProvider instance", e);
                Toast.makeText(this, "Не удалось инициализировать камеру", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                 Log.e(TAG, "Unexpected error during CameraProvider initialization", e);
                 Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("NullAnnotationGroup")
    private void bindPreviewUseCase() {
        Log.d(TAG, "bindPreviewUseCase called");
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider not initialized.");
            return;
        }
        try {
            cameraProvider.unbindAll();
            Log.d(TAG, "Previous use cases unbound.");
            CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
            previewUseCase = new Preview.Builder().build();
            previewView.post(() -> {
                Log.d(TAG, "Setting SurfaceProvider for Preview");
                if (previewUseCase != null && previewView != null) {
                    previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
                }
            });
            Log.d(TAG, "Binding lifecycle...");
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);
            if (camera != null) {
                Log.i(TAG, "Camera bound to lifecycle successfully.");
                setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl());
            } else {
                Log.e(TAG, "Failed to get Camera instance after binding.");
                disableCameraUI();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error binding preview use case: ", e);
            Toast.makeText(this, "Не удалось привязать камеру", Toast.LENGTH_SHORT).show();
            disableCameraUI();
        }
    }

    private void disableCameraUI() {
        if (zoomSlider != null) {
            zoomSlider.setEnabled(false);
            zoomSlider.setVisibility(View.INVISIBLE);
        }
        if (linkZoomSwitch != null) {
            linkZoomSwitch.setEnabled(false);
            if (isZoomLinked) {
                isZoomLinked = false;
                linkZoomSwitch.setChecked(false);
            }
        }
    }

    private void setupCameraZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        Log.d(TAG, "setupCameraZoomSliderState called");
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        if (zoomStateLiveData == null) {
             Log.e(TAG, "CameraInfo.getZoomState() returned null LiveData.");
             disableCameraUI();
             return;
        }
        zoomStateLiveData.observe(this, zoomState -> {
            if (zoomState == null) {
                Log.w(TAG, "ZoomState is null.");
                disableCameraUI();
            } else {
                Log.v(TAG, "ZoomState updated. Ratio: " + zoomState.getZoomRatio() + ", Linear: " + zoomState.getLinearZoom());
                if (zoomSlider != null && !zoomSlider.isPressed()) {
                    zoomSlider.setValue(zoomState.getLinearZoom());
                }
                if (zoomSlider != null) {
                    zoomSlider.setEnabled(true);
                    zoomSlider.setVisibility(View.VISIBLE);
                }
                if (linkZoomSwitch != null) {
                    linkZoomSwitch.setEnabled(originalBitmap != null && !originalBitmap.isRecycled());
                }
                if (isZoomLinked && overlayImageView.getVisibility() == View.VISIBLE) {
                    applyLinkedZoom(zoomState.getZoomRatio());
                }
            }
        });
        ZoomState currentZoomState = zoomStateLiveData.getValue();
        if (currentZoomState == null) {
            disableCameraUI();
        } else {
            if (zoomSlider != null) {
                zoomSlider.setValueFrom(0f);
                zoomSlider.setValueTo(1f);
                zoomSlider.setStepSize(0.01f);
                zoomSlider.setValue(currentZoomState.getLinearZoom());
                zoomSlider.setEnabled(true);
                zoomSlider.setVisibility(View.VISIBLE);
            }
            if (linkZoomSwitch != null) {
                linkZoomSwitch.setEnabled(originalBitmap != null && !originalBitmap.isRecycled());
            }
        }
    }

    private void setupCameraZoomSliderListener() {
        if (zoomSlider == null) return;
        zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (camera != null && fromUser) {
                Log.v(TAG, "Camera zoom slider CHANGED by user: " + value);
                camera.getCameraControl().setLinearZoom(value);
            }
        });
        zoomSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {}
            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                if (isZoomLinked) {
                    updateZoomLinkBaseline();
                    Log.d(TAG,"Zoom link baseline updated after slider touch stop.");
                }
            }
        });
    }

    private void applyLinkedZoom(float currentCameraZoomRatio) {
        if (initialCameraZoomRatio < 0.01f) {
            Log.w(TAG, "Cannot apply linked zoom, initial camera zoom ratio is too small.");
            return;
        }
        if (overlayImageView == null || overlayImageView.getDrawable() == null || matrix == null) {
            Log.w(TAG, "Cannot apply linked zoom, overlay image drawable or matrix is null.");
            return;
        }
        float cameraZoomFactor = currentCameraZoomRatio / initialCameraZoomRatio;
        float targetImageScale = initialImageScale * cameraZoomFactor;
        float currentImageScale = getMatrixScale(matrix);
        float postScaleFactor = (currentImageScale > 0.001f) ? targetImageScale / currentImageScale : 1.0f;
        float pivotX = overlayImageView.getWidth() / 2f;
        float pivotY = overlayImageView.getHeight() / 2f;
        matrix.postScale(postScaleFactor, postScaleFactor, pivotX, pivotY);
        overlayImageView.setImageMatrix(matrix);
        Log.v(TAG, "Applied linked zoom: CamRatioChangeFactor=" + String.format("%.2f", cameraZoomFactor) +
                   ", TargetImgScale=" + String.format("%.2f", targetImageScale) +
                   ", AppliedPostScale=" + String.format("%.2f", postScaleFactor));
    }
}
