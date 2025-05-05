// --- Полный путь к файлу: /root/CameraPreview/app/src/main/java/com/example/camerapreview/MainActivity.java ---
package com.example.camerapreview;

// Импорты...
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
import androidx.annotation.NonNull; // <<< Убедимся, что импорт есть
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

// import com.google.android.material.slider.BaseOnSliderTouchListener; // <<< УДАЛЕН ИМПОРТ
import com.google.android.material.slider.Slider; // <<< Убедимся, что импорт есть
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements View.OnTouchListener, LayerAdapter.OnLayerVisibilityChangedListener {

    // --- Константы и переменные ---
    private static final String TAG = "CameraPreviewApp";
    private static final String[] PENCIL_HARDNESS = { "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F", "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B" };
    private static final int GRAY_LEVELS = PENCIL_HARDNESS.length;
    private static final float GRAY_RANGE_SIZE = 256.0f / GRAY_LEVELS;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private static final float MIN_ZOOM_RATIO_FOR_LINK = 1.01f;

    private PreviewView previewView;
    private Slider zoomSlider;
    private Button loadImageButton;
    private ImageView overlayImageView;
    private Slider transparencySlider;
    private SwitchCompat pencilModeSwitch;
    private Button layerSelectButton;
    private Group controlsGroup;
    private CheckBox controlsVisibilityCheckbox;
    private CheckBox showLayersWhenControlsHiddenCheckbox; // <<< НОВЫЙ ЧЕКБОКС
    private SwitchCompat linkZoomSwitch;

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

    private boolean isPencilMode = false;
    private boolean[] layerVisibility;
    private Bitmap originalBitmap = null;
    private Bitmap grayscaleBitmap = null;
    private Bitmap finalCompositeBitmap = null;

    private boolean isZoomLinked = false;
    private float initialCameraZoomRatio = 1f;
    private float initialImageScale = 1f;

    // --- ActivityResultLaunchers ---
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted != null) {
                        allPermissionsGranted &= granted;
                    } else {
                        allPermissionsGranted = false; // Treat null as not granted
                    }
                }
                if (allPermissionsGranted) {
                    Log.i(TAG, "Camera permission granted.");
                    startCamera();
                } else {
                    Log.w(TAG, "Camera permission not granted.");
                    Toast.makeText(this, "Разрешение на камеру не предоставлено", Toast.LENGTH_SHORT).show();
                    // Consider disabling camera related features or closing the app
                }
            });

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Log.i(TAG, "Image URI selected: " + uri);
                    recycleAllBitmaps(); // Recycle previous bitmaps before loading new one
                    loadOriginalBitmap(uri);
                } else {
                    Log.i(TAG, "No image selected by user.");
                }
            });


    // --- onCreate ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity starting");

        // --- Find Views ---
        previewView = findViewById(R.id.previewView);
        zoomSlider = findViewById(R.id.zoom_slider);
        loadImageButton = findViewById(R.id.load_image_button);
        overlayImageView = findViewById(R.id.overlayImageView);
        transparencySlider = findViewById(R.id.transparency_slider);
        pencilModeSwitch = findViewById(R.id.pencilModeSwitch);
        layerSelectButton = findViewById(R.id.layerSelectButton);
        controlsGroup = findViewById(R.id.controlsGroup);
        controlsVisibilityCheckbox = findViewById(R.id.controlsVisibilityCheckbox);
        showLayersWhenControlsHiddenCheckbox = findViewById(R.id.showLayersWhenControlsHiddenCheckbox); // <<< НАЙТИ НОВЫЙ ЧЕКБОКС
        linkZoomSwitch = findViewById(R.id.linkZoomSwitch);

        // --- Null Check ---
        if (previewView == null || zoomSlider == null || loadImageButton == null ||
                overlayImageView == null || transparencySlider == null || pencilModeSwitch == null ||
                layerSelectButton == null || controlsGroup == null || controlsVisibilityCheckbox == null ||
                showLayersWhenControlsHiddenCheckbox == null || // <<< ДОБАВИТЬ В ПРОВЕРКУ
                linkZoomSwitch == null) {
            Log.e(TAG, "onCreate: One or more views not found! Check layout file IDs.");
            Toast.makeText(this, "Критическая ошибка: Не найдены элементы интерфейса", Toast.LENGTH_LONG).show();
            finish(); // Exit if UI is broken
            return;
        } else {
            Log.d(TAG, "onCreate: All views found");
        }

        // --- Initial Setup ---
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        overlayImageView.setOnTouchListener(this);
        overlayImageView.setScaleType(ImageView.ScaleType.MATRIX); // Important for matrix transforms

        layerVisibility = new boolean[GRAY_LEVELS];
        Arrays.fill(layerVisibility, true); // All layers visible initially

        // --- Setup Listeners ---
        setupCameraZoomSliderListener();
        setupLoadImageButtonListener();
        setupTransparencySliderListener();
        setupPencilModeSwitchListener();
        setupLayerSelectButtonListener();
        setupControlsVisibilityListener(); // Этот слушатель обновлен
        setupShowLayersWhenControlsHiddenCheckboxListener();
        setupLinkZoomSwitchListener();

        // --- UI Initial State ---
        previewView.post(this::hideSystemUI); // Hide system bars after layout
        overlayImageView.setVisibility(View.GONE);
        transparencySlider.setVisibility(View.GONE);
        transparencySlider.setEnabled(false);
        linkZoomSwitch.setEnabled(false); // Disabled until image loaded
        showLayersWhenControlsHiddenCheckbox.setVisibility(View.GONE); // Hide new checkbox initially

        // Set initial visibility of controls group based on checkbox
        boolean controlsInitiallyVisible = controlsVisibilityCheckbox.isChecked();
        controlsGroup.setVisibility(controlsInitiallyVisible ? View.VISIBLE : View.GONE);
        controlsVisibilityCheckbox.setText(controlsInitiallyVisible ? getString(R.string.controls_label) : "");
        showLayersWhenControlsHiddenCheckbox.setVisibility(controlsInitiallyVisible ? View.GONE : View.VISIBLE); // Show/hide based on main checkbox

        updateLayerButtonVisibility(); // Set initial state of the layers button

        // --- Permissions and Camera Start ---
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        Log.d(TAG, "onCreate: Setup complete");
    }

    // --- Touch Handling for ImageView ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v.getId() != R.id.overlayImageView) return false; // Only handle touches on the overlay

        ImageView view = (ImageView) v;
        scaleGestureDetector.onTouchEvent(event); // Pass event to ScaleGestureDetector first

        PointF curr = new PointF(event.getX(), event.getY());

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                savedMatrix.set(matrix); // Save current matrix state
                start.set(curr); // Record starting touch point
                mode = DRAG;
                Log.v(TAG, "onTouch: DOWN, mode=DRAG");
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                oldDist = spacing(event); // Calculate initial distance between pointers
                oldAngle = rotation(event); // Calculate initial angle
                Log.v(TAG, "onTouch: POINTER_DOWN, oldDist=" + oldDist);
                if (oldDist > 10f) { // Threshold to avoid noise
                    savedMatrix.set(matrix); // Save matrix state before starting zoom/rotate
                    midPoint(mid, event); // Calculate midpoint between pointers
                    mode = ZOOM;
                    Log.v(TAG, "onTouch: mode=ZOOM");
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                Log.v(TAG, "onTouch: UP/POINTER_UP, mode=NONE");
                mode = NONE;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG) {
                    matrix.set(savedMatrix); // Restore saved state
                    matrix.postTranslate(curr.x - start.x, curr.y - start.y); // Apply translation
                } else if (mode == ZOOM && event.getPointerCount() >= 2) {
                    float newDist = spacing(event);
                    float newAngle = rotation(event);
                    Log.v(TAG, "onTouch: MOVE, mode=ZOOM, newDist=" + newDist);
                    if (newDist > 10f) { // Threshold
                        matrix.set(savedMatrix); // Restore saved state
                        float scale = newDist / oldDist; // Calculate scale factor
                        matrix.postScale(scale, scale, mid.x, mid.y); // Apply scaling around midpoint

                        // <<< ВРАЩЕНИЕ ВОССТАНОВЛЕНО >>>
                        float deltaAngle = newAngle - oldAngle;
                        matrix.postRotate(deltaAngle, mid.x, mid.y); // Apply rotation around midpoint

                        // If zoom is linked, update baseline on manual image scale
                        if (isZoomLinked) {
                            updateZoomLinkBaseline();
                            Log.d(TAG, "Manual image zoom updated zoom link baseline.");
                        }
                    }
                }
                break;
        }

        view.setImageMatrix(matrix); // Apply the calculated matrix to the ImageView
        return true; // Indicate touch event was handled
    }

    // Helper: Calculate distance between two fingers
    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    // Helper: Calculate midpoint between two fingers
    private void midPoint(PointF point, MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }

    // Helper: Calculate rotation angle between two fingers
    private float rotation(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        double delta_x = (event.getX(0) - event.getX(1));
        double delta_y = (event.getY(0) - event.getY(1));
        double radians = Math.atan2(delta_y, delta_x);
        return (float) Math.toDegrees(radians);
    }

    // ScaleGestureDetector listener (required, but main scaling logic is in onTouch)
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Scale is handled in ACTION_MOVE with matrix.postScale
            return true; // Indicate event was handled
        }
    }


    // --- UI Listeners Setup ---

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
                overlayImageView.setAlpha(value); // Set ImageView transparency
            }
        });
    }

    private void setupPencilModeSwitchListener() {
        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "Pencil Mode Switch changed: " + isChecked);
            isPencilMode = isChecked;
            if (isChecked) {
                // Only create grayscale if needed and original exists
                if (grayscaleBitmap == null && originalBitmap != null && !originalBitmap.isRecycled()) {
                    createGrayscaleBitmap();
                }
            } else {
                // Clean up grayscale resources when switching off
                recycleBitmap(grayscaleBitmap);
                grayscaleBitmap = null;
                recycleBitmap(finalCompositeBitmap); // Also recycle composite if it exists
                finalCompositeBitmap = null;
            }
            updateLayerButtonVisibility();
            updateImageDisplay();
        });
    }

    private void setupLayerSelectButtonListener() {
        layerSelectButton.setOnClickListener(v -> {
            Log.d(TAG, "Layer select button clicked");
            showLayerSelectionDialog();
        });
    }

    private void setupControlsVisibilityListener() {
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "Controls Visibility Checkbox changed: " + isChecked);
            controlsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            controlsVisibilityCheckbox.setText(isChecked ? getString(R.string.controls_label) : "");
            showLayersWhenControlsHiddenCheckbox.setVisibility(isChecked ? View.GONE : View.VISIBLE);
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

    // --- Zoom Linking Logic ---

    private void updateZoomLinkBaseline() {
        if (camera == null || overlayImageView.getDrawable() == null || matrix == null) {
            Log.w(TAG, "Cannot update zoom link baseline - camera, image or matrix not ready.");
            if(isZoomLinked && linkZoomSwitch != null) {
                isZoomLinked = false;
                linkZoomSwitch.setChecked(false);
            }
            if(linkZoomSwitch != null) linkZoomSwitch.setEnabled(false);
            return;
        }

        LiveData<ZoomState> zoomStateLiveData = camera.getCameraInfo().getZoomState();
        ZoomState currentZoomState = zoomStateLiveData.getValue();

        if (currentZoomState != null) {
            initialCameraZoomRatio = currentZoomState.getZoomRatio();
            if (initialCameraZoomRatio < 1.0f) initialCameraZoomRatio = 1.0f;
        } else {
            initialCameraZoomRatio = 1.0f;
            Log.w(TAG,"Could not get current camera zoom state for baseline. Using 1.0f.");
        }

        initialImageScale = getMatrixScale(matrix);

        Log.i(TAG, "Zoom Link Baseline UPDATED. Initial Cam Ratio: " + initialCameraZoomRatio + ", Initial Img Scale: " + initialImageScale);
    }

    private float getMatrixScale(Matrix matrix) {
        float[] values = new float[9];
        matrix.getValues(values);
        float scaleX = values[Matrix.MSCALE_X];
        float skewY = values[Matrix.MSKEW_Y];
        return (float) Math.sqrt(scaleX * scaleX + skewY * skewY);
    }

    // --- Image Loading and Processing ---

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
                if (isPencilMode) {
                    createGrayscaleBitmap();
                }
                updateLayerButtonVisibility();
                updateImageDisplay();
                overlayImageView.setVisibility(View.VISIBLE);
                transparencySlider.setVisibility(View.VISIBLE);
                transparencySlider.setEnabled(true);
                transparencySlider.setValue(1.0f);
                overlayImageView.setAlpha(1.0f);
                resetImageMatrix();
                Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();
            } else {
                throw new IOException("BitmapFactory.decodeStream returned null for URI: " + imageUri);
            }
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error loading original bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти для загрузки изображения", Toast.LENGTH_LONG).show();
            clearImageRelatedData();
        } catch (Exception e) {
            Log.e(TAG, "Error loading original bitmap from URI: " + imageUri, e);
            Toast.makeText(this, "Ошибка загрузки изображения", Toast.LENGTH_SHORT).show();
            clearImageRelatedData();
        }
    }

    private void createGrayscaleBitmap() {
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "createGrayscaleBitmap: Original bitmap is null or recycled. Cannot create grayscale.");
            return;
        }
        Log.d(TAG, "Creating grayscale bitmap...");
        recycleBitmap(grayscaleBitmap);

        try {
            grayscaleBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grayscaleBitmap);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            Paint grayPaint = new Paint();
            grayPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            grayPaint.setAntiAlias(true);
            canvas.drawBitmap(originalBitmap, 0, 0, grayPaint);
            Log.d(TAG, "Grayscale bitmap created successfully.");
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error creating grayscale bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти для обработки изображения", Toast.LENGTH_SHORT).show();
            grayscaleBitmap = null;
        } catch (Exception e) {
            Log.e(TAG, "Error creating grayscale bitmap", e);
            grayscaleBitmap = null;
        }
    }

    private Bitmap createCompositeGrayscaleBitmap() {
        if (grayscaleBitmap == null || grayscaleBitmap.isRecycled()) {
            Log.w(TAG, "createCompositeGrayscaleBitmap: Grayscale bitmap is not available or recycled.");
            return null;
        }
        if (layerVisibility == null) {
            Log.e(TAG, "createCompositeGrayscaleBitmap: layerVisibility array is null.");
            return null;
        }
        Log.d(TAG, "Creating composite grayscale bitmap based on layer visibility...");
        int width = grayscaleBitmap.getWidth();
        int height = grayscaleBitmap.getHeight();
        int[] grayPixels = new int[width * height];
        int[] finalPixels = new int[width * height];

        try {
            grayscaleBitmap.getPixels(grayPixels, 0, width, 0, 0, width, height);
            boolean anyLayerVisible = false;
            for (int j = 0; j < grayPixels.length; j++) {
                int grayValue = Color.red(grayPixels[j]);
                int layerIndex = GRAY_LEVELS - 1 - (int) (grayValue / GRAY_RANGE_SIZE);
                layerIndex = Math.max(0, Math.min(GRAY_LEVELS - 1, layerIndex));
                if (layerVisibility[layerIndex]) {
                    finalPixels[j] = grayPixels[j];
                    anyLayerVisible = true;
                } else {
                    finalPixels[j] = Color.TRANSPARENT;
                }
            }
            if (!anyLayerVisible) {
                Log.d(TAG, "No layers are visible, composite bitmap will be fully transparent.");
            }
            Bitmap composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            composite.setPixels(finalPixels, 0, width, 0, 0, width, height);
            Log.d(TAG, "Composite grayscale bitmap created successfully.");
            return composite;
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error during grayscale compositing", oom);
            Toast.makeText(this, "Недостаточно памяти для композитинга слоев", Toast.LENGTH_SHORT).show();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error during grayscale compositing", e);
            return null;
        }
    }

    private void updateImageDisplay() {
        if (overlayImageView == null) return;
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "updateImageDisplay: Original bitmap unavailable. Hiding overlay.");
            clearImageRelatedData();
            return;
        }
        Log.d(TAG, "Updating image display. Pencil Mode: " + isPencilMode);
        recycleBitmap(finalCompositeBitmap);
        finalCompositeBitmap = null;
        Bitmap bitmapToShow;
        if (isPencilMode) {
            finalCompositeBitmap = createCompositeGrayscaleBitmap();
            bitmapToShow = finalCompositeBitmap;
            if (bitmapToShow == null) {
                Log.w(TAG,"Composite bitmap creation failed or returned null, showing original bitmap as fallback.");
                bitmapToShow = originalBitmap;
            }
        } else {
            bitmapToShow = originalBitmap;
        }
        if (bitmapToShow != null && !bitmapToShow.isRecycled()) {
            overlayImageView.setImageBitmap(bitmapToShow);
            overlayImageView.setImageMatrix(matrix);
            overlayImageView.setVisibility(View.VISIBLE);
            Log.d(TAG, "Bitmap set to overlayImageView.");
        } else {
            Log.w(TAG, "updateImageDisplay: bitmapToShow is null or recycled unexpectedly. Clearing overlay.");
            clearImageRelatedData();
        }
    }


    // --- Layer Selection Dialog ---

    private void showLayerSelectionDialog() {
        Log.d(TAG, "Showing layer selection dialog.");
        if (PENCIL_HARDNESS == null || layerVisibility == null || PENCIL_HARDNESS.length != layerVisibility.length) {
            Log.e(TAG, "Layer data (PENCIL_HARDNESS or layerVisibility) is invalid or mismatched.");
            Toast.makeText(this, "Ошибка данных слоев", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_layer_select, null);
        builder.setView(dialogView);
        RecyclerView recyclerView = dialogView.findViewById(R.id.layersRecyclerView);
        if (recyclerView == null) {
            Log.e(TAG, "RecyclerView with ID 'layersRecyclerView' not found in dialog_layer_select.xml!");
            Toast.makeText(this,"Ошибка интерфейса диалога слоев", Toast.LENGTH_SHORT).show();
            return;
        }
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        final LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);
        builder.setPositiveButton("OK", (dialog, which) -> {
            dialog.dismiss();
        });
        builder.setNeutralButton("Все", (dialog, which) -> {
            Arrays.fill(layerVisibility, true);
            adapter.notifyDataSetChanged();
            updateImageDisplay();
        });
        builder.setNegativeButton("Ничего", (dialog, which) -> {
            Arrays.fill(layerVisibility, false);
            adapter.notifyDataSetChanged();
            updateImageDisplay();
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        Log.d(TAG, "onLayerVisibilityChanged callback - Position: " + position + ", Visible: " + isVisible);
        updateImageDisplay();
    }


    // --- Memory Management and UI State Cleanup ---

    private void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            Log.v(TAG, "Recycling bitmap: " + bitmap);
            bitmap.recycle();
        }
    }

    private void recycleAllBitmaps() {
        Log.d(TAG, "Recycling all bitmaps...");
        recycleBitmap(originalBitmap);
        originalBitmap = null;
        recycleBitmap(grayscaleBitmap);
        grayscaleBitmap = null;
        recycleBitmap(finalCompositeBitmap);
        finalCompositeBitmap = null;
        Log.d(TAG, "All bitmaps recycled and references set to null.");
    }

    private void clearImageRelatedData() {
        Log.d(TAG, "Clearing image related data and resetting UI elements");
        recycleAllBitmaps();
        if(overlayImageView != null) {
            overlayImageView.setImageBitmap(null);
            overlayImageView.setVisibility(View.GONE);
        }
        if(transparencySlider != null) {
            transparencySlider.setValue(1.0f);
            transparencySlider.setVisibility(View.GONE);
            transparencySlider.setEnabled(false);
        }
        if (isPencilMode && pencilModeSwitch != null) {
            isPencilMode = false;
            pencilModeSwitch.setChecked(false);
        }
        if (isZoomLinked && linkZoomSwitch != null) {
            isZoomLinked = false;
            linkZoomSwitch.setChecked(false);
        }
        if (linkZoomSwitch != null) {
            linkZoomSwitch.setEnabled(false);
        }
        updateLayerButtonVisibility();
    }


    // --- Image Matrix Reset ---

    private void resetImageMatrix() {
        Log.d(TAG, "Resetting image matrix to fit view");
        if (overlayImageView == null) return;
        matrix.reset();
        overlayImageView.post(() -> {
            if (overlayImageView == null || overlayImageView.getDrawable() == null) {
                Log.w(TAG, "Cannot reset matrix: ImageView or Drawable is null in post().");
                return;
            }
            int viewWidth = overlayImageView.getWidth();
            int viewHeight = overlayImageView.getHeight();
            int drawableWidth = overlayImageView.getDrawable().getIntrinsicWidth();
            int drawableHeight = overlayImageView.getDrawable().getIntrinsicHeight();
            if (viewWidth <= 0 || viewHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) {
                Log.w(TAG, "Cannot reset matrix, invalid dimensions: View(" + viewWidth + "x" + viewHeight +
                        "), Drawable(" + drawableWidth + "x" + drawableHeight + ")");
                return;
            }
            matrix.reset();
            float scale;
            float dx = 0, dy = 0;
            if (drawableWidth * viewHeight > viewWidth * drawableHeight) {
                scale = (float) viewWidth / (float) drawableWidth;
                dy = (viewHeight - drawableHeight * scale) * 0.5f;
            } else {
                scale = (float) viewHeight / (float) drawableHeight;
                dx = (viewWidth - drawableWidth * scale) * 0.5f;
            }
            matrix.postScale(scale, scale);
            matrix.postTranslate(dx, dy);
            overlayImageView.setImageMatrix(matrix);
            savedMatrix.set(matrix);
            if(isZoomLinked) {
                updateZoomLinkBaseline();
                Log.d(TAG, "Image matrix reset. Zoom link baseline updated.");
            } else {
                Log.d(TAG, "Image matrix reset complete.");
            }
        });
    }


    // --- Layer Button Visibility Logic ---

    private void updateLayerButtonVisibility() {
        if (layerSelectButton == null || controlsVisibilityCheckbox == null || showLayersWhenControlsHiddenCheckbox == null) {
            Log.e(TAG, "Cannot update layer button visibility - one or more required views are null.");
            return;
        }
        boolean canShowLayersBaseCondition = isPencilMode && (originalBitmap != null && !originalBitmap.isRecycled());
        boolean shouldBeVisible;
        if (controlsVisibilityCheckbox.isChecked()) {
            shouldBeVisible = canShowLayersBaseCondition;
            Log.d(TAG, "updateLayerButtonVisibility (Controls VISIBLE): BaseCondition=" + canShowLayersBaseCondition + " -> shouldBeVisible=" + shouldBeVisible);
        } else {
            shouldBeVisible = canShowLayersBaseCondition && showLayersWhenControlsHiddenCheckbox.isChecked();
            Log.d(TAG, "updateLayerButtonVisibility (Controls HIDDEN): BaseCondition=" + canShowLayersBaseCondition
                    + ", ShowHiddenCheckbox=" + showLayersWhenControlsHiddenCheckbox.isChecked()
                    + " -> shouldBeVisible=" + shouldBeVisible);
        }
        layerSelectButton.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE);
    }


    // --- Android Lifecycle and System UI ---

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


    // --- CameraX Setup and Control ---

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
            Log.e(TAG, "CameraProvider not initialized. Cannot bind use case.");
            Toast.makeText(this, "Провайдер камеры не готов", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            cameraProvider.unbindAll();
            Log.d(TAG, "Previous use cases unbound.");
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();
            previewUseCase = new Preview.Builder().build();
            previewView.post(() -> {
                Log.d(TAG, "Setting SurfaceProvider for Preview use case");
                if (previewUseCase != null && previewView != null) {
                    previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
                } else {
                    Log.w(TAG,"PreviewUseCase or PreviewView is null when trying to set SurfaceProvider");
                }
            });
            Log.d(TAG, "Binding camera to lifecycle...");
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);
            if (camera != null) {
                Log.i(TAG, "Camera bound to lifecycle successfully.");
                setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl());
            } else {
                Log.e(TAG, "Failed to get Camera instance after binding. Camera is null.");
                Toast.makeText(this, "Не удалось получить экземпляр камеры", Toast.LENGTH_SHORT).show();
                disableCameraUI();
            }
        } catch (IllegalArgumentException e) {
             Log.e(TAG, "Error binding preview use case: Invalid argument (e.g., no back camera?)", e);
             Toast.makeText(this, "Ошибка конфигурации камеры", Toast.LENGTH_SHORT).show();
             disableCameraUI();
        } catch (Exception e) {
            Log.e(TAG, "Error binding preview use case", e);
            Toast.makeText(this, "Не удалось привязать камеру к интерфейсу", Toast.LENGTH_SHORT).show();
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
             Log.e(TAG, "CameraInfo.getZoomState() returned null LiveData. Cannot observe zoom.");
             disableCameraUI();
             return;
        }
        zoomStateLiveData.observe(this, zoomState -> {
            if (zoomState == null) {
                Log.w(TAG, "Observed ZoomState is null. Disabling camera zoom slider and link switch.");
                disableCameraUI();
            } else {
                 Log.v(TAG, "ZoomState updated via LiveData. Ratio: " + zoomState.getZoomRatio() + ", Linear: " + zoomState.getLinearZoom());
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
            Log.w(TAG, "Initial ZoomState value is null. Disabling controls.");
            disableCameraUI();
        } else {
             Log.d(TAG, "Setting initial slider values from current ZoomState: Linear=" + currentZoomState.getLinearZoom());
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
                Log.v(TAG, "Camera zoom slider CHANGED by user: linear value=" + value);
                camera.getCameraControl().setLinearZoom(value);
            }
        });
        zoomSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @SuppressLint("RestrictedApi")
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                 Log.v(TAG, "Slider touch STARTED");
            }
            @SuppressLint("RestrictedApi")
            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                 Log.v(TAG, "Slider touch STOPPED");
                if (isZoomLinked) {
                    updateZoomLinkBaseline();
                    Log.d(TAG,"Zoom link baseline updated after camera slider touch stop.");
                }
            }
        });
    }

    private void applyLinkedZoom(float currentCameraZoomRatio) {
        if (initialCameraZoomRatio < 0.01f) {
            Log.w(TAG, "Cannot apply linked zoom, initial camera zoom ratio is too small or zero.");
            return;
        }
        if (overlayImageView == null || overlayImageView.getDrawable() == null || matrix == null) {
            Log.w(TAG, "Cannot apply linked zoom, overlay image/drawable/matrix is null.");
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
// --- Конец кода файла ---
