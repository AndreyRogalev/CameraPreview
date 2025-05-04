// --- Полный путь к файлу: /root/CameraPreview/app/src/main/java/com/example/camerapreview/MainActivity.java ---
package com.example.camerapreview;

// Импорты без изменений...
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements View.OnTouchListener, LayerAdapter.OnLayerVisibilityChangedListener {

    // --- Константы ---
    private static final String TAG = "CameraPreviewApp";
    private static final String[] PENCIL_HARDNESS = { "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F", "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B" };
    private static final int GRAY_LEVELS = PENCIL_HARDNESS.length;
    private static final float GRAY_RANGE_SIZE = 256.0f / GRAY_LEVELS;
    private static final int NONE = 0; private static final int DRAG = 1; private static final int ZOOM = 2;
    private static final float MIN_ZOOM_RATIO_FOR_LINK = 1.01f; // Минимальный зум камеры для расчета пропорции

    // --- UI Элементы ---
    private PreviewView previewView; private Slider zoomSlider; private Button loadImageButton; private ImageView overlayImageView; private Slider transparencySlider; private SwitchCompat pencilModeSwitch; private Button layerSelectButton; private Group controlsGroup; private CheckBox controlsVisibilityCheckbox; private SwitchCompat linkZoomSwitch;

    // --- CameraX ---
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture; private ProcessCameraProvider cameraProvider; private Camera camera; private Preview previewUseCase;

    // --- Обработка жестов ---
    private Matrix matrix = new Matrix(); private Matrix savedMatrix = new Matrix(); private int mode = NONE; private PointF start = new PointF(); private PointF mid = new PointF(); private float oldDist = 1f; private float oldAngle = 0f; private ScaleGestureDetector scaleGestureDetector;

    // --- Карандашный режим ---
    private boolean isPencilMode = false; private boolean[] layerVisibility; private Bitmap originalBitmap = null; private Bitmap grayscaleBitmap = null; private Bitmap finalCompositeBitmap = null;

    // --- Связка зума ---
    private boolean isZoomLinked = false;
    private float initialCameraZoomRatio = 1f; // <<< ИЗМЕНЕНО: Храним реальный Zoom Ratio
    private float initialImageScale = 1f;

    // --- ActivityResult Launchers ---
    private final ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> { /* ... */ boolean allPermissionsGranted = true; for (Boolean granted : result.values()) { if (granted != null) { allPermissionsGranted &= granted; } else { allPermissionsGranted = false; } } if (allPermissionsGranted) { Log.i(TAG, "Camera permission granted."); startCamera(); } else { Log.w(TAG, "Camera permission not granted."); Toast.makeText(this, "Разрешение на камеру не предоставлено", Toast.LENGTH_SHORT).show(); } });
    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null) { Log.i(TAG, "Image URI selected: " + uri); recycleAllBitmaps(); loadOriginalBitmap(uri); } else { Log.i(TAG, "No image selected by user."); } });

    // --- onCreate ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity starting");

        // --- Инициализация UI ---
        previewView = findViewById(R.id.previewView); zoomSlider = findViewById(R.id.zoom_slider); loadImageButton = findViewById(R.id.load_image_button); overlayImageView = findViewById(R.id.overlayImageView); transparencySlider = findViewById(R.id.transparency_slider); pencilModeSwitch = findViewById(R.id.pencilModeSwitch); layerSelectButton = findViewById(R.id.layerSelectButton); controlsGroup = findViewById(R.id.controlsGroup); controlsVisibilityCheckbox = findViewById(R.id.controlsVisibilityCheckbox); linkZoomSwitch = findViewById(R.id.linkZoomSwitch);
        if (previewView == null || zoomSlider == null || loadImageButton == null || overlayImageView == null || transparencySlider == null || pencilModeSwitch == null || layerSelectButton == null || controlsGroup == null || controlsVisibilityCheckbox == null || linkZoomSwitch == null) { Log.e(TAG, "onCreate: One or more views not found!"); Toast.makeText(this, "Критическая ошибка: Не найдены элементы интерфейса", Toast.LENGTH_LONG).show(); finish(); return; } else { Log.d(TAG, "onCreate: All views found"); }

        // --- Инициализация состояния ---
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        overlayImageView.setOnTouchListener(this);
        overlayImageView.setScaleType(ImageView.ScaleType.MATRIX);
        layerVisibility = new boolean[GRAY_LEVELS];
        Arrays.fill(layerVisibility, true);

        // --- Настройка слушателей ---
        setupCameraZoomSliderListener();
        setupLoadImageButtonListener();
        setupTransparencySliderListener();
        setupPencilModeSwitchListener();
        setupLayerSelectButtonListener();
        setupControlsVisibilityListener();
        setupLinkZoomSwitchListener();

        // Скрытие UI
        previewView.post(this::hideSystemUI);

        // Камера и разрешения
        if (allPermissionsGranted()) { startCamera(); } else { requestPermissions(); }

        // Начальное состояние UI
        overlayImageView.setVisibility(View.GONE);
        transparencySlider.setVisibility(View.GONE); transparencySlider.setEnabled(false);
        linkZoomSwitch.setEnabled(false);
        updateLayerButtonVisibility();
        boolean controlsInitiallyVisible = controlsVisibilityCheckbox.isChecked();
        controlsGroup.setVisibility(controlsInitiallyVisible ? View.VISIBLE : View.GONE);
        controlsVisibilityCheckbox.setText(controlsInitiallyVisible ? getString(R.string.controls_label) : "");

        Log.d(TAG, "onCreate: Setup complete");
    }

    // --- Обработка касаний ImageView ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v.getId() != R.id.overlayImageView) return false;

        ImageView view = (ImageView) v;
        scaleGestureDetector.onTouchEvent(event);

        PointF curr = new PointF(event.getX(), event.getY());

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: savedMatrix.set(matrix); start.set(curr); mode = DRAG; break;
            case MotionEvent.ACTION_POINTER_DOWN: oldDist = spacing(event); oldAngle = rotation(event); if (oldDist > 10f) { savedMatrix.set(matrix); midPoint(mid, event); mode = ZOOM; } break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: mode = NONE; break;
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
                        // <<< Если зум связан, обновляем базу после ручного жеста >>>
                        if (isZoomLinked) {
                            updateZoomLinkBaseline();
                            Log.d(TAG, "Manual image zoom updated zoom link baseline.");
                        }
                    }
                }
                break;
        }
        view.setImageMatrix(matrix);
        return true;
    }
    // (Вспомогательные методы жестов spacing, midPoint, rotation, ScaleListener без изменений)
    private float spacing(MotionEvent event) { if (event.getPointerCount() < 2) return 0f; float x = event.getX(0) - event.getX(1); float y = event.getY(0) - event.getY(1); return (float) Math.sqrt(x * x + y * y); }
    private void midPoint(PointF point, MotionEvent event) { if (event.getPointerCount() < 2) return; float x = event.getX(0) + event.getX(1); float y = event.getY(0) + event.getY(1); point.set(x / 2, y / 2); }
    private float rotation(MotionEvent event) { if (event.getPointerCount() < 2) return 0f; double delta_x = (event.getX(0) - event.getX(1)); double delta_y = (event.getY(0) - event.getY(1)); double radians = Math.atan2(delta_y, delta_x); return (float) Math.toDegrees(radians); }
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener { @Override public boolean onScale(ScaleGestureDetector detector) { return true; } }

    // --- Настройка слушателей UI ---
    private void setupLoadImageButtonListener() { /* ... */ loadImageButton.setOnClickListener(v -> { Log.i(TAG, "Load image button pressed."); try { pickImageLauncher.launch("image/*"); } catch (Exception e) { Log.e(TAG, "Error launching image picker", e); Toast.makeText(this, "Не удалось открыть галерею", Toast.LENGTH_SHORT).show(); } }); }
    private void setupTransparencySliderListener() { /* ... */ transparencySlider.addOnChangeListener((slider, value, fromUser) -> { if (overlayImageView.getVisibility() == View.VISIBLE && fromUser) { Log.v(TAG, "Transparency slider changed: " + value); overlayImageView.setAlpha(value); } }); }
    private void setupPencilModeSwitchListener() { /* ... */ pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> { Log.i(TAG, "Pencil Mode Switch changed: " + isChecked); isPencilMode = isChecked; if (isChecked) { if (grayscaleBitmap == null && originalBitmap != null && !originalBitmap.isRecycled()) { createGrayscaleBitmap(); } } else { recycleBitmap(grayscaleBitmap); grayscaleBitmap = null; recycleBitmap(finalCompositeBitmap); finalCompositeBitmap = null; } updateLayerButtonVisibility(); updateImageDisplay(); }); }
    private void setupLayerSelectButtonListener() { /* ... */ layerSelectButton.setOnClickListener(v -> { Log.d(TAG, "Layer select button clicked"); showLayerSelectionDialog(); }); }
    private void setupControlsVisibilityListener() { /* ... */ controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> { Log.d(TAG, "Controls Visibility Checkbox changed: " + isChecked); controlsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE); controlsVisibilityCheckbox.setText(isChecked ? getString(R.string.controls_label) : ""); }); }

    private void setupLinkZoomSwitchListener() {
        linkZoomSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isZoomLinked = isChecked;
            if (isChecked) {
                updateZoomLinkBaseline(); // Захватываем текущие значения при включении
            } else {
                Log.i(TAG, "Zoom Link DISABLED.");
            }
        });
    }

    // <<< ОБНОВЛЕН Метод захвата базовых значений зума >>>
    private void updateZoomLinkBaseline() {
        if (camera == null || overlayImageView.getDrawable() == null || matrix == null) {
            Log.w(TAG, "Cannot update zoom link baseline - camera, image or matrix not ready.");
            // Выключаем связку, если что-то не готово
            if(isZoomLinked && linkZoomSwitch != null) {
                isZoomLinked = false;
                linkZoomSwitch.setChecked(false);
            }
            if(linkZoomSwitch != null) linkZoomSwitch.setEnabled(false);
            return;
        }
        // Получаем текущий ФАКТИЧЕСКИЙ зум камеры
        LiveData<ZoomState> zoomStateLiveData = camera.getCameraInfo().getZoomState();
        ZoomState currentZoomState = zoomStateLiveData.getValue();
        if (currentZoomState != null) {
            initialCameraZoomRatio = currentZoomState.getZoomRatio();
            // Убедимся, что начальный зум не слишком мал для деления
            if (initialCameraZoomRatio < 1.0f) initialCameraZoomRatio = 1.0f; // Используем 1.0x как минимум
        } else {
             initialCameraZoomRatio = 1.0f; // Безопасное значение по умолчанию
             Log.w(TAG,"Could not get current camera zoom state for baseline. Using 1.0f.");
        }
        // Получаем текущий масштаб изображения
        initialImageScale = getMatrixScale(matrix);
        Log.i(TAG, "Zoom Link Baseline UPDATED. Initial Cam Ratio: " + initialCameraZoomRatio + ", Initial Img Scale: " + initialImageScale);

    }

    // Вспомогательный метод получения масштаба из матрицы (без изменений)
    private float getMatrixScale(Matrix matrix) { float[] values = new float[9]; matrix.getValues(values); float scaleX = values[Matrix.MSCALE_X]; float skewY = values[Matrix.MSKEW_Y]; return (float) Math.sqrt(scaleX * scaleX + skewY * skewY); }

    // --- Логика загрузки и обработки изображения ---
    private void loadOriginalBitmap(Uri imageUri) {
        Log.d(TAG, "loadOriginalBitmap started for URI: " + imageUri);
        recycleAllBitmaps();
        try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) throw new IOException("Unable to open input stream");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            originalBitmap = BitmapFactory.decodeStream(inputStream, null, options);

            if (originalBitmap != null) {
                Log.i(TAG, "Original bitmap loaded: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());
                linkZoomSwitch.setEnabled(true); // Активируем переключатель связки
                if (isZoomLinked) { // Если связка была включена, выключаем ее
                    isZoomLinked = false;
                    linkZoomSwitch.setChecked(false);
                }
                if (isPencilMode) { createGrayscaleBitmap(); }
                updateLayerButtonVisibility();
                updateImageDisplay();
                overlayImageView.setVisibility(View.VISIBLE);
                transparencySlider.setVisibility(View.VISIBLE); transparencySlider.setEnabled(true); transparencySlider.setValue(1.0f);
                overlayImageView.setAlpha(1.0f);
                resetImageMatrix(); // Сбрасываем матрицу (это обновит и baseline зума, если связка включена)
                Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();
            } else { throw new IOException("BitmapFactory returned null"); }
        } catch (OutOfMemoryError oom) { Log.e(TAG, "Out of memory loading original bitmap", oom); Toast.makeText(this, "Недостаточно памяти для загрузки", Toast.LENGTH_LONG).show(); clearImageRelatedData(); }
        catch (Exception e) { Log.e(TAG, "Error loading original bitmap", e); Toast.makeText(this, "Ошибка загрузки изображения", Toast.LENGTH_SHORT).show(); clearImageRelatedData(); }
    }

    // (createGrayscaleBitmap, createCompositeGrayscaleBitmap без изменений)
     private void createGrayscaleBitmap() { if (originalBitmap == null || originalBitmap.isRecycled()) { Log.w(TAG, "createGrayscaleBitmap: Original is null or recycled"); return; } Log.d(TAG, "Creating grayscale bitmap..."); recycleBitmap(grayscaleBitmap); try { grayscaleBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(grayscaleBitmap); ColorMatrix cm = new ColorMatrix(); cm.setSaturation(0); Paint grayPaint = new Paint(); grayPaint.setColorFilter(new ColorMatrixColorFilter(cm)); grayPaint.setAntiAlias(true); canvas.drawBitmap(originalBitmap, 0, 0, grayPaint); Log.d(TAG, "Grayscale bitmap created."); } catch (OutOfMemoryError oom) { Log.e(TAG, "Out of memory creating grayscale bitmap", oom); Toast.makeText(this, "Недостаточно памяти для обработки", Toast.LENGTH_SHORT).show(); grayscaleBitmap = null; } catch (Exception e) { Log.e(TAG, "Error creating grayscale bitmap", e); grayscaleBitmap = null; } }
     private Bitmap createCompositeGrayscaleBitmap() { if (grayscaleBitmap == null || grayscaleBitmap.isRecycled()) { Log.w(TAG, "createCompositeGrayscaleBitmap: Grayscale bitmap is not available."); return null; } if (layerVisibility == null) { Log.e(TAG, "createCompositeGrayscaleBitmap: layerVisibility is null."); return null; } Log.d(TAG, "Creating composite grayscale bitmap..."); int width = grayscaleBitmap.getWidth(); int height = grayscaleBitmap.getHeight(); int[] grayPixels = new int[width * height]; int[] finalPixels = new int[width * height]; try { grayscaleBitmap.getPixels(grayPixels, 0, width, 0, 0, width, height); boolean anyLayerVisible = false; for (int j = 0; j < grayPixels.length; j++) { int grayValue = Color.red(grayPixels[j]); int layerIndex = GRAY_LEVELS - 1 - (int) (grayValue / GRAY_RANGE_SIZE); layerIndex = Math.max(0, Math.min(GRAY_LEVELS - 1, layerIndex)); if (layerVisibility[layerIndex]) { finalPixels[j] = grayPixels[j]; anyLayerVisible = true; } else { finalPixels[j] = Color.TRANSPARENT; } } if (!anyLayerVisible) Log.d(TAG, "No layers visible, composite will be transparent."); Bitmap composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); composite.setPixels(finalPixels, 0, width, 0, 0, width, height); Log.d(TAG, "Composite grayscale bitmap created."); return composite; } catch (OutOfMemoryError oom) { Log.e(TAG, "Out of memory during grayscale compositing", oom); Toast.makeText(this, "Недостаточно памяти для композитинга", Toast.LENGTH_SHORT).show(); return null; } catch (Exception e) { Log.e(TAG, "Error during grayscale compositing", e); return null; } }

    // (updateImageDisplay без изменений)
    private void updateImageDisplay() { if (overlayImageView == null) return; if (originalBitmap == null || originalBitmap.isRecycled()) { Log.w(TAG, "updateImageDisplay: Original bitmap unavailable. Hiding overlay."); clearImageRelatedData(); return; } Log.d(TAG, "Updating image display. Pencil Mode: " + isPencilMode); recycleBitmap(finalCompositeBitmap); Bitmap bitmapToShow; if (isPencilMode) { finalCompositeBitmap = createCompositeGrayscaleBitmap(); bitmapToShow = finalCompositeBitmap; if (bitmapToShow == null) { Log.w(TAG,"Composite bitmap creation failed, showing original as fallback."); bitmapToShow = originalBitmap; } } else { bitmapToShow = originalBitmap; } if (bitmapToShow != null && !bitmapToShow.isRecycled()) { overlayImageView.setImageBitmap(bitmapToShow); overlayImageView.setImageMatrix(matrix); overlayImageView.setVisibility(View.VISIBLE); Log.d(TAG, "Bitmap set to overlayImageView."); } else { Log.w(TAG, "bitmapToShow is null or recycled. Clearing overlay & hiding."); clearImageRelatedData(); } }

    // --- Диалог выбора слоев (без изменений) ---
    private void showLayerSelectionDialog() { /* ... */ Log.d(TAG, "Showing layer selection dialog."); if (PENCIL_HARDNESS == null || layerVisibility == null) { Log.e(TAG, "Layer data is null."); return; } AlertDialog.Builder builder = new AlertDialog.Builder(this); LayoutInflater inflater = this.getLayoutInflater(); View dialogView = inflater.inflate(R.layout.dialog_layer_select, null); builder.setView(dialogView); RecyclerView recyclerView = dialogView.findViewById(R.id.layersRecyclerView); if (recyclerView == null) { Log.e(TAG, "RecyclerView not found!"); Toast.makeText(this,"Ошибка диалога слоев", Toast.LENGTH_SHORT).show(); return; } recyclerView.setLayoutManager(new LinearLayoutManager(this)); final LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this); recyclerView.setAdapter(adapter); builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss()); builder.setNeutralButton("Все", (dialog, which) -> { Arrays.fill(layerVisibility, true); if (adapter != null) adapter.notifyDataSetChanged(); updateImageDisplay(); }); builder.setNegativeButton("Ничего", (dialog, which) -> { Arrays.fill(layerVisibility, false); if (adapter != null) adapter.notifyDataSetChanged(); updateImageDisplay(); }); AlertDialog dialog = builder.create(); dialog.show(); }

    // Обратный вызов от Адаптера (без изменений)
    @Override public void onLayerVisibilityChanged(int position, boolean isVisible) { Log.d(TAG, "onLayerVisibilityChanged - Position: " + position + ", Visible: " + isVisible); updateImageDisplay(); }

    // --- Управление памятью и UI состоянием ---
    private void recycleBitmap(Bitmap bitmap) { if (bitmap != null && !bitmap.isRecycled()) { bitmap.recycle(); } }
    private void recycleAllBitmaps() { Log.d(TAG, "Recycling all bitmaps..."); recycleBitmap(originalBitmap); originalBitmap = null; recycleBitmap(grayscaleBitmap); grayscaleBitmap = null; recycleBitmap(finalCompositeBitmap); finalCompositeBitmap = null; Log.d(TAG, "All bitmaps recycled."); }
    private void clearImageRelatedData() { Log.d(TAG, "Clearing image related data and UI"); recycleAllBitmaps(); if(overlayImageView != null) { overlayImageView.setImageBitmap(null); overlayImageView.setVisibility(View.GONE); } if(transparencySlider != null) { transparencySlider.setVisibility(View.GONE); transparencySlider.setEnabled(false); } if (isPencilMode && pencilModeSwitch != null) { isPencilMode = false; pencilModeSwitch.setChecked(false); } if (isZoomLinked && linkZoomSwitch != null) { isZoomLinked = false; linkZoomSwitch.setChecked(false); } if (linkZoomSwitch != null) { linkZoomSwitch.setEnabled(false); } updateLayerButtonVisibility(); }

    // --- Сброс матрицы изображения ---
    private void resetImageMatrix() {
        Log.d(TAG, "Resetting image matrix");
        matrix.reset();
        overlayImageView.post(() -> {
            if (overlayImageView == null || overlayImageView.getDrawable() == null) return;
            int viewWidth = overlayImageView.getWidth(); int viewHeight = overlayImageView.getHeight();
            int drawableWidth = overlayImageView.getDrawable().getIntrinsicWidth(); int drawableHeight = overlayImageView.getDrawable().getIntrinsicHeight();
            if (viewWidth <= 0 || viewHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) { Log.w(TAG, "Cannot reset matrix, invalid dimensions."); return; }
            matrix.reset(); float scale; float dx = 0, dy = 0;
            if (drawableWidth * viewHeight > viewWidth * drawableHeight) { scale = (float) viewWidth / (float) drawableWidth; dy = (viewHeight - drawableHeight * scale) * 0.5f; }
            else { scale = (float) viewHeight / (float) drawableHeight; dx = (viewWidth - drawableWidth * scale) * 0.5f; }
            matrix.postScale(scale, scale); matrix.postTranslate(dx, dy);
            overlayImageView.setImageMatrix(matrix);
            savedMatrix.set(matrix);
            // <<< Обновляем базу после сброса >>>
            if(isZoomLinked) {
                 updateZoomLinkBaseline();
                 Log.d(TAG, "Image matrix reset. Zoom link baseline updated.");
            } else {
                 Log.d(TAG, "Image matrix reset.");
            }
        });
    }

    // Метод обновления видимости кнопки "Слои" (без изменений)
    private void updateLayerButtonVisibility() { boolean shouldBeVisible = isPencilMode && (originalBitmap != null && !originalBitmap.isRecycled()); if (layerSelectButton != null) { layerSelectButton.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE); Log.d(TAG, "Layer Button Visibility updated to: " + (shouldBeVisible ? "VISIBLE" : "GONE")); } }

    // --- Остальной код (CameraX, UI, Lifecycle) ---
    @Override protected void onDestroy() { super.onDestroy(); Log.d(TAG, "onDestroy called"); recycleAllBitmaps(); if (cameraProvider != null) { cameraProvider.unbindAll(); } }
    private void hideSystemUI() { /* ... */ Log.v(TAG, "Attempting to hide system UI"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { final WindowInsetsController controller = getWindow().getInsetsController(); if (controller != null) { controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()); controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); } else { Log.w(TAG, "WindowInsetsController is null"); hideSystemUIOldApi(); } } else { hideSystemUIOldApi(); } }
    private void hideSystemUIOldApi() { /* ... */ Log.v(TAG, "Using old API to hide system UI"); View decorView = getWindow().getDecorView(); if (decorView != null) { decorView.setSystemUiVisibility( View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN); } else { Log.w(TAG, "DecorView is null"); } }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) { hideSystemUI(); } }
    private boolean allPermissionsGranted() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private void requestPermissions() { requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA}); }
    private void startCamera() { /* ... */ Log.i(TAG, "startCamera called"); cameraProviderFuture = ProcessCameraProvider.getInstance(this); cameraProviderFuture.addListener(() -> { try { cameraProvider = cameraProviderFuture.get(); Log.i(TAG, "CameraProvider obtained successfully."); bindPreviewUseCase(); } catch (ExecutionException | InterruptedException e) { Log.e(TAG, "Error getting CameraProvider: ", e); Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show(); } }, ContextCompat.getMainExecutor(this)); }
    private void bindPreviewUseCase() { /* ... */ Log.d(TAG, "bindPreviewUseCase called"); if (cameraProvider == null) { Log.e(TAG, "CameraProvider not initialized."); return; } try { cameraProvider.unbindAll(); Log.d(TAG, "Previous use cases unbound."); CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build(); previewUseCase = new Preview.Builder().build(); previewView.post(() -> { Log.d(TAG, "Setting SurfaceProvider for Preview"); previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider()); }); Log.d(TAG, "Binding lifecycle..."); camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase); if (camera != null) { Log.i(TAG, "Camera bound to lifecycle successfully."); setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl()); } else { Log.e(TAG, "Failed to get Camera instance after binding."); zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); linkZoomSwitch.setEnabled(false); } } catch (Exception e) { Log.e(TAG, "Error binding preview use case: ", e); Toast.makeText(this, "Не удалось привязать камеру", Toast.LENGTH_SHORT).show(); zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); linkZoomSwitch.setEnabled(false); } }

    // <<< ОБНОВЛЕН Метод настройки зума камеры >>>
    private void setupCameraZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        Log.d(TAG, "setupCameraZoomSliderState called");
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        zoomStateLiveData.observe(this, zoomState -> { // <<< Используем Observer для реакции на изменения
            if (zoomState == null) {
                Log.w(TAG, "ZoomState is null. Disabling camera zoom slider and link switch.");
                zoomSlider.setValue(0f); zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE);
                linkZoomSwitch.setEnabled(false);
                if(isZoomLinked) { isZoomLinked = false; linkZoomSwitch.setChecked(false); }
            } else {
                Log.i(TAG, "ZoomState updated. Ratio: " + zoomState.getZoomRatio() + ", Linear: " + zoomState.getLinearZoom());
                // Обновляем только если пользователь НЕ двигает слайдер (чтобы избежать конфликта)
                 if (!zoomSlider.isPressed()) {
                    zoomSlider.setValue(zoomState.getLinearZoom());
                 }
                 zoomSlider.setEnabled(true); zoomSlider.setVisibility(View.VISIBLE);
                 linkZoomSwitch.setEnabled(originalBitmap != null && !originalBitmap.isRecycled());

                 // <<< Если зум связан, применяем ИЗМЕНЕНИЕ зума камеры к картинке >>>
                 // Это происходит ЗДЕСЬ, т.к. здесь мы гарантированно имеем АКТУАЛЬНЫЙ zoomState
                 if (isZoomLinked && overlayImageView.getVisibility() == View.VISIBLE) {
                    applyLinkedZoom(zoomState.getZoomRatio());
                 }
            }
        });
        // Первоначальная настройка
        ZoomState currentZoomState = zoomStateLiveData.getValue();
        if (currentZoomState == null) {
            zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); linkZoomSwitch.setEnabled(false);
        } else {
            zoomSlider.setValueFrom(0f); zoomSlider.setValueTo(1f); zoomSlider.setStepSize(0.01f);
            zoomSlider.setValue(currentZoomState.getLinearZoom());
            zoomSlider.setEnabled(true); zoomSlider.setVisibility(View.VISIBLE);
            linkZoomSwitch.setEnabled(originalBitmap != null && !originalBitmap.isRecycled());
        }
    }

     // <<< ОБНОВЛЕН Слушатель слайдера камеры >>>
    private void setupCameraZoomSliderListener() {
        zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (camera != null && fromUser) {
                Log.v(TAG, "Camera zoom slider CHANGED by user: " + value);
                // Просто устанавливаем линейный зум камеры.
                // Обновление картинки произойдет в Observer'е ZoomState.
                camera.getCameraControl().setLinearZoom(value);
            }
        });
        // Добавим слушатель окончания касания слайдера, чтобы обновить базу, если нужно
         zoomSlider.addOnSliderTouchStopListener(slider -> {
             if (isZoomLinked) {
                 updateZoomLinkBaseline(); // Обновить базу после того, как пользователь отпустил слайдер
                 Log.d(TAG,"Zoom link baseline updated after slider touch stop.");
             }
         });
    }

    // <<< НОВЫЙ Метод применения связанного зума >>>
    private void applyLinkedZoom(float currentCameraZoomRatio) {
         if (initialCameraZoomRatio < 0.01f) { // Избегаем деления на ноль
             Log.w(TAG, "Cannot apply linked zoom, initial camera zoom ratio is too small.");
             return;
         }
         if (overlayImageView.getDrawable() == null) {
             Log.w(TAG, "Cannot apply linked zoom, overlay image drawable is null.");
             return;
         }

        // Рассчитываем изменение зума камеры
        float cameraZoomFactor = currentCameraZoomRatio / initialCameraZoomRatio;
        // Целевой масштаб картинки
        float targetImageScale = initialImageScale * cameraZoomFactor;
        // Текущий масштаб картинки
        float currentImageScale = getMatrixScale(matrix);

        // Коэффициент для postScale
        float postScaleFactor = (currentImageScale > 0.001f) ? targetImageScale / currentImageScale : 1.0f;

        // Масштабируем относительно центра View
        float pivotX = overlayImageView.getWidth() / 2f;
        float pivotY = overlayImageView.getHeight() / 2f;

        matrix.postScale(postScaleFactor, postScaleFactor, pivotX, pivotY);
        overlayImageView.setImageMatrix(matrix);
        Log.v(TAG, "Applied linked zoom: CamRatioChange=" + cameraZoomFactor + ", TargetImgScale=" + targetImageScale);
    }

}
// --- Конец кода файла ---
