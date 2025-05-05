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
        setupLoadImageButtonListener(); // <-- Исправленный вызов
        setupTransparencySliderListener();
        setupPencilModeSwitchListener();
        setupLayerSelectButtonListener();
        setupControlsVisibilityListener(); // Этот слушатель обновлен
        setupShowLayersWhenControlsHiddenCheckboxListener(); // <<< ДОБАВИТЬ ВЫЗОВ НОВОГО СЛУШАТЕЛЯ
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

                        // Apply rotation if needed (optional, uncomment if rotation is desired)
                        // float deltaAngle = newAngle - oldAngle;
                        // matrix.postRotate(deltaAngle, mid.x, mid.y); // Apply rotation around midpoint

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

    // <<< ИСПРАВЛЕННЫЙ МЕТОД >>>
    private void setupLoadImageButtonListener() {
        loadImageButton.setOnClickListener(v -> {
            Log.i(TAG, "Load image button pressed.");
            try {
                pickImageLauncher.launch("image/\\*"); // Launch image picker
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
            updateLayerButtonVisibility(); // <<< ВАЖНО: Обновить видимость кнопки
            updateImageDisplay(); // Update the displayed image (original or composite)
        });
    }

    private void setupLayerSelectButtonListener() {
        layerSelectButton.setOnClickListener(v -> {
            Log.d(TAG, "Layer select button clicked");
            showLayerSelectionDialog(); // Show the layer selection popup
        });
    }

    // <<< ИЗМЕНЕННЫЙ СЛУШАТЕЛЬ >>>
    private void setupControlsVisibilityListener() {
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "Controls Visibility Checkbox changed: " + isChecked);
            controlsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            controlsVisibilityCheckbox.setText(isChecked ? getString(R.string.controls_label) : "");

            // Показываем или скрываем НОВЫЙ чекбокс в зависимости от основного
            showLayersWhenControlsHiddenCheckbox.setVisibility(isChecked ? View.GONE : View.VISIBLE);

            // ВСЕГДА обновляем видимость кнопки "Слои" после изменения видимости контролов
            updateLayerButtonVisibility();
        });
    }

    // <<< НОВЫЙ СЛУШАТЕЛЬ >>>
    private void setupShowLayersWhenControlsHiddenCheckboxListener() {
        showLayersWhenControlsHiddenCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "Show Layers When Controls Hidden Checkbox changed: " + isChecked);
            // Просто обновляем видимость кнопки "Слои" при изменении этого чекбокса
            updateLayerButtonVisibility();
        });
    }

    private void setupLinkZoomSwitchListener() {
        linkZoomSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isZoomLinked = isChecked;
            if (isChecked) {
                // Set baseline when linking is enabled
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
            // If baseline cannot be set, disable linking
            if(isZoomLinked && linkZoomSwitch != null) {
                isZoomLinked = false;
                linkZoomSwitch.setChecked(false);
            }
            // Also disable the switch itself if prerequisites aren't met
            if(linkZoomSwitch != null) linkZoomSwitch.setEnabled(false);
            return;
        }

        LiveData<ZoomState> zoomStateLiveData = camera.getCameraInfo().getZoomState();
        ZoomState currentZoomState = zoomStateLiveData.getValue(); // Get current state

        if (currentZoomState != null) {
            initialCameraZoomRatio = currentZoomState.getZoomRatio();
            // Ensure baseline isn't less than 1.0 (camera might report slightly less)
            if (initialCameraZoomRatio < 1.0f) initialCameraZoomRatio = 1.0f;
        } else {
            initialCameraZoomRatio = 1.0f; // Default if state is unavailable
            Log.w(TAG,"Could not get current camera zoom state for baseline. Using 1.0f.");
        }

        initialImageScale = getMatrixScale(matrix); // Get current scale from image matrix

        Log.i(TAG, "Zoom Link Baseline UPDATED. Initial Cam Ratio: " + initialCameraZoomRatio + ", Initial Img Scale: " + initialImageScale);
    }

    // Helper: Get the effective scale factor from a Matrix
    private float getMatrixScale(Matrix matrix) {
        float[] values = new float[9];
        matrix.getValues(values);
        // Use Pythagorean theorem for scale X and skew Y to handle rotation
        float scaleX = values[Matrix.MSCALE_X];
        float skewY = values[Matrix.MSKEW_Y];
        return (float) Math.sqrt(scaleX * scaleX + skewY * skewY);
    }

    // --- Image Loading and Processing ---

    private void loadOriginalBitmap(Uri imageUri) {
        Log.d(TAG, "loadOriginalBitmap started for URI: " + imageUri);
        // recycleAllBitmaps(); // Already called by the launcher callback

        try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) throw new IOException("Unable to open input stream for URI: " + imageUri);

            // Basic options - consider using inSampleSize for large images if memory is an issue
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Use high quality config

            originalBitmap = BitmapFactory.decodeStream(inputStream, null, options);

            if (originalBitmap != null) {
                Log.i(TAG, "Original bitmap loaded: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());

                // Image loaded successfully, update UI
                linkZoomSwitch.setEnabled(true); // Enable zoom linking
                // Reset zoom link state if it was previously on
                if (isZoomLinked) {
                    isZoomLinked = false;
                    linkZoomSwitch.setChecked(false);
                }

                if (isPencilMode) {
                    createGrayscaleBitmap(); // Create grayscale if pencil mode is already active
                }
                updateLayerButtonVisibility(); // <<< ОБНОВИТЬ видимость кнопки
                updateImageDisplay(); // Show the loaded image

                overlayImageView.setVisibility(View.VISIBLE);
                transparencySlider.setVisibility(View.VISIBLE);
                transparencySlider.setEnabled(true);
                transparencySlider.setValue(1.0f); // Reset transparency
                overlayImageView.setAlpha(1.0f); // Ensure full opacity

                resetImageMatrix(); // Reset position and scale

                Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();

            } else {
                throw new IOException("BitmapFactory.decodeStream returned null for URI: " + imageUri);
            }
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error loading original bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти для загрузки изображения", Toast.LENGTH_LONG).show();
            clearImageRelatedData(); // Clean up on error
        } catch (Exception e) {
            Log.e(TAG, "Error loading original bitmap from URI: " + imageUri, e);
            Toast.makeText(this, "Ошибка загрузки изображения", Toast.LENGTH_SHORT).show();
            clearImageRelatedData(); // Clean up on error
        }
    }

    private void createGrayscaleBitmap() {
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "createGrayscaleBitmap: Original bitmap is null or recycled. Cannot create grayscale.");
            return;
        }
        Log.d(TAG, "Creating grayscale bitmap...");
        recycleBitmap(grayscaleBitmap); // Recycle previous grayscale if exists

        try {
            // Create a mutable bitmap with the same dimensions as the original
            grayscaleBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grayscaleBitmap);

            // Create a paint object with a ColorMatrix to convert to grayscale
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0); // Set saturation to 0 for grayscale
            Paint grayPaint = new Paint();
            grayPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            grayPaint.setAntiAlias(true); // Optional: smoother rendering

            // Draw the original bitmap onto the new canvas using the grayscale paint
            canvas.drawBitmap(originalBitmap, 0, 0, grayPaint);

            Log.d(TAG, "Grayscale bitmap created successfully.");
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error creating grayscale bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти для обработки изображения", Toast.LENGTH_SHORT).show();
            grayscaleBitmap = null; // Ensure reference is null on error
        } catch (Exception e) {
            Log.e(TAG, "Error creating grayscale bitmap", e);
            grayscaleBitmap = null; // Ensure reference is null on error
        }
    }

    private Bitmap createCompositeGrayscaleBitmap() {
        if (grayscaleBitmap == null || grayscaleBitmap.isRecycled()) {
            Log.w(TAG, "createCompositeGrayscaleBitmap: Grayscale bitmap is not available or recycled.");
            return null;
        }
        if (layerVisibility == null) {
            Log.e(TAG, "createCompositeGrayscaleBitmap: layerVisibility array is null.");
            return null; // Cannot proceed without visibility info
        }

        Log.d(TAG, "Creating composite grayscale bitmap based on layer visibility...");
        int width = grayscaleBitmap.getWidth();
        int height = grayscaleBitmap.getHeight();
        int[] grayPixels = new int[width * height];
        int[] finalPixels = new int[width * height]; // Pixels for the new composite bitmap

        try {
            // Get pixels from the full grayscale image
            grayscaleBitmap.getPixels(grayPixels, 0, width, 0, 0, width, height);

            boolean anyLayerVisible = false; // Track if any pixel will be visible

            // Process each pixel
            for (int j = 0; j < grayPixels.length; j++) {
                int grayValue = Color.red(grayPixels[j]); // Get grayscale value (R, G, B are equal)

                // Determine which "hardness layer" this pixel belongs to
                // Invert index because PENCIL_HARDNESS goes from hard (light) to soft (dark)
                int layerIndex = GRAY_LEVELS - 1 - (int) (grayValue / GRAY_RANGE_SIZE);
                layerIndex = Math.max(0, Math.min(GRAY_LEVELS - 1, layerIndex)); // Clamp index to valid range

                // Check if the layer for this pixel is visible
                if (layerVisibility[layerIndex]) {
                    finalPixels[j] = grayPixels[j]; // Keep the original grayscale pixel
                    anyLayerVisible = true;
                } else {
                    finalPixels[j] = Color.TRANSPARENT; // Make the pixel transparent if layer is hidden
                }
            }

            if (!anyLayerVisible) {
                Log.d(TAG, "No layers are visible, composite bitmap will be fully transparent.");
            }

            // Create the new bitmap and set its pixels
            Bitmap composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            composite.setPixels(finalPixels, 0, width, 0, 0, width, height);

            Log.d(TAG, "Composite grayscale bitmap created successfully.");
            return composite;

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of Memory Error during grayscale compositing", oom);
            Toast.makeText(this, "Недостаточно памяти для композитинга слоев", Toast.LENGTH_SHORT).show();
            return null; // Return null on error
        } catch (Exception e) {
            Log.e(TAG, "Error during grayscale compositing", e);
            return null; // Return null on error
        }
    }

    private void updateImageDisplay() {
        if (overlayImageView == null) return; // View might not be ready

        // If no original image, clear everything
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "updateImageDisplay: Original bitmap unavailable. Hiding overlay.");
            clearImageRelatedData(); // Ensure UI is clean
            return;
        }

        Log.d(TAG, "Updating image display. Pencil Mode: " + isPencilMode);

        // Recycle the previous composite bitmap before creating a new one
        recycleBitmap(finalCompositeBitmap);
        finalCompositeBitmap = null; // Clear reference

        Bitmap bitmapToShow;

        if (isPencilMode) {
            finalCompositeBitmap = createCompositeGrayscaleBitmap(); // Generate composite based on current visibility
            bitmapToShow = finalCompositeBitmap;
            // Fallback to original if composite creation failed or resulted in null
            if (bitmapToShow == null) {
                Log.w(TAG,"Composite bitmap creation failed or returned null, showing original bitmap as fallback.");
                bitmapToShow = originalBitmap;
            }
        } else {
            // If not in pencil mode, show the original color bitmap
            bitmapToShow = originalBitmap;
        }

        // Set the chosen bitmap to the ImageView
        if (bitmapToShow != null && !bitmapToShow.isRecycled()) {
            overlayImageView.setImageBitmap(bitmapToShow);
            overlayImageView.setImageMatrix(matrix); // Re-apply current transform matrix
            overlayImageView.setVisibility(View.VISIBLE); // Make sure it's visible
            Log.d(TAG, "Bitmap set to overlayImageView.");
        } else {
            // Should not happen if originalBitmap exists, but as a safeguard
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
        View dialogView = inflater.inflate(R.layout.dialog_layer_select, null); // Inflate custom layout
        builder.setView(dialogView);

        RecyclerView recyclerView = dialogView.findViewById(R.id.layersRecyclerView);
        if (recyclerView == null) {
            Log.e(TAG, "RecyclerView with ID 'layersRecyclerView' not found in dialog_layer_select.xml!");
            Toast.makeText(this,"Ошибка интерфейса диалога слоев", Toast.LENGTH_SHORT).show();
            return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // Use a linear layout
        // Create and set the adapter, passing layer names, visibility array, and the listener (this Activity)
        final LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);

        // Dialog Buttons
        builder.setPositiveButton("OK", (dialog, which) -> {
            // No action needed here, changes are applied via the adapter's listener
            dialog.dismiss();
        });

        builder.setNeutralButton("Все", (dialog, which) -> {
            Arrays.fill(layerVisibility, true); // Set all visibility flags to true
            adapter.notifyDataSetChanged(); // Update the RecyclerView display
            updateImageDisplay(); // Re-composite the image with all layers visible
            // No need to dismiss, allow further changes or OK
        });

        builder.setNegativeButton("Ничего", (dialog, which) -> {
            Arrays.fill(layerVisibility, false); // Set all visibility flags to false
            adapter.notifyDataSetChanged(); // Update the RecyclerView display
            updateImageDisplay(); // Re-composite the image (will likely be transparent)
            // No need to dismiss
        });

        AlertDialog dialog = builder.create();
        dialog.show(); // Display the dialog
    }

    // Implementation of LayerAdapter.OnLayerVisibilityChangedListener
    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        // This method is called by the LayerAdapter when a checkbox is changed
        Log.d(TAG, "onLayerVisibilityChanged callback - Position: " + position + ", Visible: " + isVisible);
        // No need to update layerVisibility array here, adapter does that internally now
        updateImageDisplay(); // Re-composite the image with the updated visibility
    }


    // --- Memory Management and UI State Cleanup ---

    // Helper to safely recycle a bitmap
    private void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            Log.v(TAG, "Recycling bitmap: " + bitmap);
            bitmap.recycle();
        }
    }

    // Recycle all potentially loaded bitmaps
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

    // Reset UI elements related to the image overlay
    private void clearImageRelatedData() {
        Log.d(TAG, "Clearing image related data and resetting UI elements");
        recycleAllBitmaps(); // Recycle bitmaps first

        if(overlayImageView != null) {
            overlayImageView.setImageBitmap(null); // Remove bitmap from view
            overlayImageView.setVisibility(View.GONE); // Hide the view
        }
        if(transparencySlider != null) {
            transparencySlider.setValue(1.0f); // Reset slider value
            transparencySlider.setVisibility(View.GONE); // Hide slider
            transparencySlider.setEnabled(false); // Disable slider
        }
        // Reset modes if they were active
        if (isPencilMode && pencilModeSwitch != null) {
            isPencilMode = false;
            pencilModeSwitch.setChecked(false);
            // updateLayerButtonVisibility will be called by the switch listener
        }
        if (isZoomLinked && linkZoomSwitch != null) {
            isZoomLinked = false;
            linkZoomSwitch.setChecked(false);
        }
        if (linkZoomSwitch != null) {
            linkZoomSwitch.setEnabled(false); // Disable linking switch as there's no image
        }

        updateLayerButtonVisibility(); // <<< ОБНОВИТЬ видимость кнопки слоев
        // No need to call updateImageDisplay here, as recycleAllBitmaps clears the necessary refs
    }


    // --- Image Matrix Reset ---

    // Reset the overlay image matrix to fit the view
    private void resetImageMatrix() {
        Log.d(TAG, "Resetting image matrix to fit view");
        if (overlayImageView == null) return;

        matrix.reset(); // Reset the matrix first

        // Post to the view's message queue to ensure dimensions are available
        overlayImageView.post(() -> {
            if (overlayImageView == null || overlayImageView.getDrawable() == null) {
                Log.w(TAG, "Cannot reset matrix: ImageView or Drawable is null in post().");
                return;
            }

            int viewWidth = overlayImageView.getWidth();
            int viewHeight = overlayImageView.getHeight();
            int drawableWidth = overlayImageView.getDrawable().getIntrinsicWidth();
            int drawableHeight = overlayImageView.getDrawable().getIntrinsicHeight();

            // Check for valid dimensions
            if (viewWidth <= 0 || viewHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) {
                Log.w(TAG, "Cannot reset matrix, invalid dimensions: View(" + viewWidth + "x" + viewHeight +
                        "), Drawable(" + drawableWidth + "x" + drawableHeight + ")");
                return;
            }

            matrix.reset(); // Reset again inside post just in case
            float scale;
            float dx = 0, dy = 0; // Translation values

            // Calculate scale factor to fit_center
            if (drawableWidth * viewHeight > viewWidth * drawableHeight) {
                // Fit width
                scale = (float) viewWidth / (float) drawableWidth;
                dy = (viewHeight - drawableHeight * scale) * 0.5f; // Center vertically
            } else {
                // Fit height
                scale = (float) viewHeight / (float) drawableHeight;
                dx = (viewWidth - drawableWidth * scale) * 0.5f; // Center horizontally
            }

            matrix.postScale(scale, scale); // Apply scaling
            matrix.postTranslate(dx, dy); // Apply translation

            overlayImageView.setImageMatrix(matrix); // Set the calculated matrix
            savedMatrix.set(matrix); // Save this initial state

            // If zoom linking was active, update baseline with the new scale
            if(isZoomLinked) {
                updateZoomLinkBaseline();
                Log.d(TAG, "Image matrix reset. Zoom link baseline updated.");
            } else {
                Log.d(TAG, "Image matrix reset complete.");
            }
        });
    }


    // --- Layer Button Visibility Logic ---

    // <<< ПЕРЕПИСАННЫЙ МЕТОД >>>
    private void updateLayerButtonVisibility() {
        if (layerSelectButton == null || controlsVisibilityCheckbox == null || showLayersWhenControlsHiddenCheckbox == null) {
            Log.e(TAG, "Cannot update layer button visibility - one or more required views are null.");
            return;
        }

        // Базовое условие: режим карандаша включен И изображение загружено и не переработано
        boolean canShowLayersBaseCondition = isPencilMode && (originalBitmap != null && !originalBitmap.isRecycled());

        boolean shouldBeVisible;

        // Проверяем, видимы ли основные контролы
        if (controlsVisibilityCheckbox.isChecked()) {
            // Контролы ВИДИМЫ: Кнопка "Слои" видна ТОЛЬКО если базовое условие выполнено
            shouldBeVisible = canShowLayersBaseCondition;
            Log.d(TAG, "updateLayerButtonVisibility (Controls VISIBLE): BaseCondition=" + canShowLayersBaseCondition + " -> shouldBeVisible=" + shouldBeVisible);

        } else {
            // Контролы СКРЫТЫ: Кнопка "Слои" видна ТОЛЬКО если базовое условие выполнено
            // И при этом отмечен НОВЫЙ чекбокс showLayersWhenControlsHiddenCheckbox
            shouldBeVisible = canShowLayersBaseCondition && showLayersWhenControlsHiddenCheckbox.isChecked();
            Log.d(TAG, "updateLayerButtonVisibility (Controls HIDDEN): BaseCondition=" + canShowLayersBaseCondition
                    + ", ShowHiddenCheckbox=" + showLayersWhenControlsHiddenCheckbox.isChecked()
                    + " -> shouldBeVisible=" + shouldBeVisible);
        }

        // Применяем вычисленную видимость к кнопке
        layerSelectButton.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE);
    }


    // --- Android Lifecycle and System UI ---

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
        recycleAllBitmaps(); // Clean up bitmaps
        if (cameraProvider != null) {
            cameraProvider.unbindAll(); // Unbind camera use cases
        }
    }

    // Hide status bar and navigation bar for immersive experience
    private void hideSystemUI() {
        Log.v(TAG, "Attempting to hide system UI");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                // Hide status and navigation bars
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                // Keep them hidden even after user interaction
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                Log.w(TAG, "WindowInsetsController is null, falling back to old API");
                hideSystemUIOldApi(); // Fallback for devices where controller might be null
            }
        } else {
            // Use deprecated flags for older Android versions
            hideSystemUIOldApi();
        }
    }

    @SuppressWarnings("deprecation") // Suppress warnings for deprecated flags
    private void hideSystemUIOldApi() {
        Log.v(TAG, "Using old API (SYSTEM_UI_FLAGs) to hide system UI");
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // Hide nav bar
                            | View.SYSTEM_UI_FLAG_FULLSCREEN); // Hide status bar
        } else {
            Log.w(TAG, "DecorView is null (old API)");
        }
    }

    // Re-hide UI when window gains focus (e.g., after returning from another app)
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }


    // --- CameraX Setup and Control ---

    // Check if camera permission is granted
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // Request camera permission
    private void requestPermissions() {
        Log.i(TAG, "Requesting camera permissions...");
        requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
    }

    // Initialize CameraX Provider
    private void startCamera() {
        Log.i(TAG, "startCamera called");
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get(); // Get the provider instance
                Log.i(TAG, "CameraProvider obtained successfully.");
                bindPreviewUseCase(); // Bind the preview use case to the lifecycle
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error getting CameraProvider instance", e);
                Toast.makeText(this, "Не удалось инициализировать камеру", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                // Catch potential CameraX initialization errors
                 Log.e(TAG, "Unexpected error during CameraProvider initialization", e);
                 Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this)); // Run on the main thread
    }

    // Bind Preview Use Case to Camera
    @SuppressLint("NullAnnotationGroup") // Suppress warnings on CameraX API nullability
    private void bindPreviewUseCase() {
        Log.d(TAG, "bindPreviewUseCase called");
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider not initialized. Cannot bind use case.");
            Toast.makeText(this, "Провайдер камеры не готов", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            cameraProvider.unbindAll(); // Unbind previous use cases before binding new ones
            Log.d(TAG, "Previous use cases unbound.");

            // Select the back camera
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            // Build the Preview use case
            previewUseCase = new Preview.Builder().build();

            // Set the surface provider from PreviewView (needs to be done on the main thread or posted)
            // Posting ensures the view is ready
            previewView.post(() -> {
                Log.d(TAG, "Setting SurfaceProvider for Preview use case");
                if (previewUseCase != null && previewView != null) {
                    previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
                } else {
                    Log.w(TAG,"PreviewUseCase or PreviewView is null when trying to set SurfaceProvider");
                }
            });


            Log.d(TAG, "Binding camera to lifecycle...");
            // Bind the use case to the activity's lifecycle
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);

            if (camera != null) {
                Log.i(TAG, "Camera bound to lifecycle successfully.");
                // Setup zoom controls now that the camera is available
                setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl());
            } else {
                // This case might indicate an issue with the device/CameraX compatibility
                Log.e(TAG, "Failed to get Camera instance after binding. Camera is null.");
                Toast.makeText(this, "Не удалось получить экземпляр камеры", Toast.LENGTH_SHORT).show();
                // Disable zoom controls if camera binding failed
                zoomSlider.setEnabled(false);
                zoomSlider.setVisibility(View.INVISIBLE); // Hide instead of GONE to maintain layout space if needed
                linkZoomSwitch.setEnabled(false);
            }

        } catch (IllegalArgumentException e) {
             Log.e(TAG, "Error binding preview use case: Invalid argument (e.g., no back camera?)", e);
             Toast.makeText(this, "Ошибка конфигурации камеры", Toast.LENGTH_SHORT).show();
             disableCameraUI();
        } catch (Exception e) {
            // Catch other potential exceptions during binding (e.g., CameraUnavailableException)
            Log.e(TAG, "Error binding preview use case", e);
            Toast.makeText(this, "Не удалось привязать камеру к интерфейсу", Toast.LENGTH_SHORT).show();
            disableCameraUI();
        }
    }

     // Helper to disable camera UI elements on failure
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

    // Observe Camera Zoom State and Update Slider
    private void setupCameraZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        Log.d(TAG, "setupCameraZoomSliderState called");
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();

        if (zoomStateLiveData == null) {
             Log.e(TAG, "CameraInfo.getZoomState() returned null LiveData. Cannot observe zoom.");
             disableCameraUI();
             return;
        }

        // Observe the LiveData for changes in zoom state
        zoomStateLiveData.observe(this, zoomState -> {
            if (zoomState == null) {
                Log.w(TAG, "Observed ZoomState is null. Disabling camera zoom slider and link switch.");
                disableCameraUI(); // Disable controls if state becomes null
            } else {
                 Log.v(TAG, "ZoomState updated via LiveData. Ratio: " + zoomState.getZoomRatio() + ", Linear: " + zoomState.getLinearZoom());
                 // Update slider position only if user is not actively dragging it
                 if (zoomSlider != null && !zoomSlider.isPressed()) {
                    zoomSlider.setValue(zoomState.getLinearZoom());
                 }
                 // Ensure sliders are enabled/visible now that we have state
                 if (zoomSlider != null) {
                    zoomSlider.setEnabled(true);
                    zoomSlider.setVisibility(View.VISIBLE);
                 }
                 if (linkZoomSwitch != null) {
                     // Enable link switch only if an image is also loaded
                    linkZoomSwitch.setEnabled(originalBitmap != null && !originalBitmap.isRecycled());
                 }

                 // Apply linked zoom if enabled and image is visible
                 if (isZoomLinked && overlayImageView.getVisibility() == View.VISIBLE) {
                    applyLinkedZoom(zoomState.getZoomRatio());
                 }
            }
        });

        // Set initial slider properties based on current state (if available)
        ZoomState currentZoomState = zoomStateLiveData.getValue(); // Get the current value immediately
        if (currentZoomState == null) {
            Log.w(TAG, "Initial ZoomState value is null. Disabling controls.");
            disableCameraUI();
        } else {
             Log.d(TAG, "Setting initial slider values from current ZoomState: Linear=" + currentZoomState.getLinearZoom());
             if (zoomSlider != null) {
                 zoomSlider.setValueFrom(0f); // Min linear zoom
                 zoomSlider.setValueTo(1f);   // Max linear zoom
                 zoomSlider.setStepSize(0.01f); // Granularity
                 zoomSlider.setValue(currentZoomState.getLinearZoom()); // Set current value
                 zoomSlider.setEnabled(true);
                 zoomSlider.setVisibility(View.VISIBLE);
             }
            if (linkZoomSwitch != null) {
                 linkZoomSwitch.setEnabled(originalBitmap != null && !originalBitmap.isRecycled());
            }
        }
    }

    // Listener for Camera Zoom Slider Interaction
    private void setupCameraZoomSliderListener() {
        if (zoomSlider == null) return;

        // Listener for value changes (when user drags)
        zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (camera != null && fromUser) {
                Log.v(TAG, "Camera zoom slider CHANGED by user: linear value=" + value);
                camera.getCameraControl().setLinearZoom(value); // Set camera zoom based on slider
            }
        });

        // Listener for touch events (start/stop dragging)
        zoomSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @SuppressLint("RestrictedApi") // For CameraControl method if needed, but setLinearZoom is public
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                // Optional: Could add visual feedback or log start
                 Log.v(TAG, "Slider touch STARTED");
            }

            @SuppressLint("RestrictedApi") // For CameraControl method if needed
            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                 Log.v(TAG, "Slider touch STOPPED");
                // Update the zoom link baseline when the user finishes adjusting the camera zoom
                if (isZoomLinked) {
                    updateZoomLinkBaseline();
                    Log.d(TAG,"Zoom link baseline updated after camera slider touch stop.");
                }
            }
        });
    }

    // Apply Linked Zoom to Overlay Image
    private void applyLinkedZoom(float currentCameraZoomRatio) {
        // Check prerequisites for applying linked zoom
        if (initialCameraZoomRatio < 0.01f) { // Avoid division by zero or near-zero
            Log.w(TAG, "Cannot apply linked zoom, initial camera zoom ratio is too small or zero.");
            return;
        }
        if (overlayImageView == null || overlayImageView.getDrawable() == null || matrix == null) {
            Log.w(TAG, "Cannot apply linked zoom, overlay image/drawable/matrix is null.");
            return;
        }

        // Calculate how much the camera zoom has changed relative to the baseline
        float cameraZoomFactor = currentCameraZoomRatio / initialCameraZoomRatio;

        // Calculate the target scale for the image based on its initial scale and the camera zoom factor
        float targetImageScale = initialImageScale * cameraZoomFactor;

        // Get the current scale of the image matrix
        float currentImageScale = getMatrixScale(matrix);

        // Calculate the scaling factor needed to reach the target scale from the current scale
        // Avoid division by zero if current scale is somehow zero
        float postScaleFactor = (currentImageScale > 0.001f) ? targetImageScale / currentImageScale : 1.0f;

        // Define the pivot point for scaling (center of the ImageView)
        float pivotX = overlayImageView.getWidth() / 2f;
        float pivotY = overlayImageView.getHeight() / 2f;

        // Apply the calculated scale factor to the matrix, pivoting around the center
        matrix.postScale(postScaleFactor, postScaleFactor, pivotX, pivotY);
        overlayImageView.setImageMatrix(matrix); // Update the ImageView

        Log.v(TAG, "Applied linked zoom: CamRatioChangeFactor=" + String.format("%.2f", cameraZoomFactor) +
                   ", TargetImgScale=" + String.format("%.2f", targetImageScale) +
                   ", AppliedPostScale=" + String.format("%.2f", postScaleFactor));
    }
}
// --- Конец кода файла ---
