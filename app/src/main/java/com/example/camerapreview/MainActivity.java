// --- Полный путь к файлу: /root/CameraPreview/app/src/main/java/com/example/camerapreview/MainActivity.java ---
package com.example.camerapreview;

// Стандартные Android и AppCompat
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
import android.widget.ImageView;
import android.widget.Toast;

// AndroidX и Material Components
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

// Другие библиотеки
import com.google.android.material.slider.Slider;
import com.google.common.util.concurrent.ListenableFuture; // Убедитесь, что добавлена зависимость guava

// Стандартные Java
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

// Реализуем интерфейсы для касаний и обратной связи от адаптера
public class MainActivity extends AppCompatActivity implements View.OnTouchListener, LayerAdapter.OnLayerVisibilityChangedListener {

    // --- Константы ---
    private static final String TAG = "CameraPreviewApp";
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };
    // Состояния для жестов
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;

    // --- UI Элементы ---
    private PreviewView previewView;
    private Slider zoomSlider;
    private Button loadImageButton;
    private ImageView overlayImageView;
    private Slider transparencySlider;
    private SwitchCompat pencilModeSwitch;
    private Button layerSelectButton;
    private Group controlsGroup;

    // --- CameraX ---
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;

    // --- Обработка жестов ---
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private int mode = NONE;
    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;
    private float oldAngle = 0f;
    private ScaleGestureDetector scaleGestureDetector;

    // --- Карандашный режим ---
    private boolean isPencilMode = false;
    private boolean[] layerVisibility;
    private Bitmap originalBitmap = null;
    private Bitmap grayscaleBitmap = null;
    private List<Bitmap> pencilLayerBitmaps = new ArrayList<>();
    private Bitmap finalCompositeBitmap = null;
    private Paint pencilPaint;

    // --- ActivityResult Launchers ---
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted != null) {
                        allPermissionsGranted &= granted;
                    } else {
                        allPermissionsGranted = false; // Считаем не предоставленным, если результат null
                    }
                }
                if (allPermissionsGranted) {
                    Log.i(TAG, "Camera permission granted.");
                    startCamera();
                } else {
                    Log.w(TAG, "Camera permission not granted.");
                    Toast.makeText(this, "Разрешение на камеру не предоставлено", Toast.LENGTH_SHORT).show();
                    // Возможно, здесь стоит закрыть приложение или отключить функционал
                    // finish();
                }
            });

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Log.i(TAG, "Image URI selected: " + uri);
                    recycleAllBitmaps(); // Освобождаем память перед загрузкой нового
                    loadOriginalBitmap(uri); // Загружаем оригинал
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

        // --- Инициализация UI ---
        previewView = findViewById(R.id.previewView);
        zoomSlider = findViewById(R.id.zoom_slider);
        loadImageButton = findViewById(R.id.load_image_button);
        overlayImageView = findViewById(R.id.overlayImageView);
        transparencySlider = findViewById(R.id.transparency_slider);
        pencilModeSwitch = findViewById(R.id.pencilModeSwitch);
        layerSelectButton = findViewById(R.id.layerSelectButton);
        controlsGroup = findViewById(R.id.controlsGroup);

        // Проверка View
        if (previewView == null || zoomSlider == null || loadImageButton == null || overlayImageView == null || transparencySlider == null || pencilModeSwitch == null || layerSelectButton == null || controlsGroup == null) {
            Log.e(TAG, "onCreate: One or more views not found!");
            Toast.makeText(this, "Критическая ошибка: Не найдены элементы интерфейса", Toast.LENGTH_LONG).show();
            finish(); // Завершаем работу, т.к. приложение не сможет функционировать
            return;
        } else {
            Log.d(TAG, "onCreate: All views found");
        }

        // --- Инициализация состояния ---
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        overlayImageView.setOnTouchListener(this);
        overlayImageView.setScaleType(ImageView.ScaleType.MATRIX); // Устанавливаем один раз
        layerVisibility = new boolean[PENCIL_HARDNESS.length];
        Arrays.fill(layerVisibility, true); // Изначально все слои видимы
        pencilPaint = new Paint();
        pencilPaint.setColor(Color.BLACK);
        pencilPaint.setStyle(Paint.Style.FILL);
        pencilPaint.setAntiAlias(true); // Немного сглаживания

        // --- Настройка слушателей ---
        setupCameraZoomSliderListener();
        setupLoadImageButtonListener();
        setupTransparencySliderListener();
        setupPencilModeSwitchListener();
        setupLayerSelectButtonListener();

        // Скрытие UI
        previewView.post(this::hideSystemUI);

        // Камера и разрешения
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        // Начальное состояние UI
        overlayImageView.setVisibility(View.GONE);
        transparencySlider.setVisibility(View.GONE);
        transparencySlider.setEnabled(false);
        layerSelectButton.setVisibility(View.GONE);

        Log.d(TAG, "onCreate: Setup complete");
    }

    // --- Обработка касаний ImageView ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v.getId() != R.id.overlayImageView) return false; // Только для ImageView

        ImageView view = (ImageView) v;
        scaleGestureDetector.onTouchEvent(event); // Передаем событие детектору масштаба

        PointF curr = new PointF(event.getX(), event.getY());

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: // Первое касание
                savedMatrix.set(matrix);
                start.set(curr);
                mode = DRAG;
                break;
            case MotionEvent.ACTION_POINTER_DOWN: // Второе касание
                oldDist = spacing(event);
                oldAngle = rotation(event);
                if (oldDist > 10f) { // Начинаем, если пальцы достаточно разведены
                    savedMatrix.set(matrix);
                    midPoint(mid, event);
                    mode = ZOOM;
                }
                break;
            case MotionEvent.ACTION_UP: // Последний палец убран
            case MotionEvent.ACTION_POINTER_UP: // Один из пальцев убран
                mode = NONE;
                break;
            case MotionEvent.ACTION_MOVE: // Движение пальцев
                if (mode == DRAG) {
                    matrix.set(savedMatrix);
                    matrix.postTranslate(curr.x - start.x, curr.y - start.y);
                } else if (mode == ZOOM && event.getPointerCount() >= 2) {
                    float newDist = spacing(event);
                    float newAngle = rotation(event);
                    if (newDist > 10f) {
                        matrix.set(savedMatrix);
                        // Масштаб
                        float scale = newDist / oldDist;
                        matrix.postScale(scale, scale, mid.x, mid.y);
                        // Вращение
                        float deltaAngle = newAngle - oldAngle;
                        matrix.postRotate(deltaAngle, mid.x, mid.y);
                        // Обновлять oldAngle не нужно здесь, т.к. deltaAngle вычисляется каждый раз
                    }
                }
                break;
        }

        view.setImageMatrix(matrix); // Применяем итоговую матрицу
        return true; // Событие обработано
    }

    // Вспомогательные методы для жестов
    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF point, MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }

    private float rotation(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        double delta_x = (event.getX(0) - event.getX(1));
        double delta_y = (event.getY(0) - event.getY(1));
        double radians = Math.atan2(delta_y, delta_x);
        return (float) Math.toDegrees(radians);
    }

    // Внутренний класс слушателя масштабирования
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Обработка масштаба происходит в onTouch, здесь можно не делать ничего
            return true;
        }
    }

    // --- Настройка слушателей UI ---
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
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                processPencilEffect(); // Генерируем слои
            } else {
                recyclePencilBitmaps(); // Очищаем слои
            }
            updateImageDisplay(); // Обновляем ImageView
        });
    }

    private void setupLayerSelectButtonListener() {
        layerSelectButton.setOnClickListener(v -> {
            Log.d(TAG, "Layer select button clicked");
            showLayerSelectionDialog();
        });
    }

    // --- Логика загрузки и обработки изображения ---
    private void loadOriginalBitmap(Uri imageUri) {
        Log.d(TAG, "loadOriginalBitmap started for URI: " + imageUri);
        try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) throw new IOException("Unable to open input stream");

            // Опции для декодирования (можно добавить downsampling для больших изображений)
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Предпочитаемый формат

            originalBitmap = BitmapFactory.decodeStream(inputStream, null, options);

            if (originalBitmap != null) {
                Log.i(TAG, "Original bitmap loaded: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());
                if (isPencilMode) {
                    processPencilEffect(); // Сразу обработать, если режим включен
                }
                updateImageDisplay(); // Показать изображение
                overlayImageView.setVisibility(View.VISIBLE);
                transparencySlider.setVisibility(View.VISIBLE);
                transparencySlider.setEnabled(true);
                transparencySlider.setValue(1.0f);
                overlayImageView.setAlpha(1.0f);
                resetImageMatrix(); // Сбросить позицию/масштаб
                Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();
            } else {
                throw new IOException("BitmapFactory returned null");
            }
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of memory loading original bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти для загрузки изображения", Toast.LENGTH_LONG).show();
            recycleAllBitmaps(); // Очистить всё
            // Скрыть связанные View
            overlayImageView.setVisibility(View.GONE);
            transparencySlider.setVisibility(View.GONE);
            transparencySlider.setEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "Error loading original bitmap", e);
            Toast.makeText(this, "Ошибка загрузки изображения", Toast.LENGTH_SHORT).show();
             recycleAllBitmaps();
             overlayImageView.setVisibility(View.GONE);
             transparencySlider.setVisibility(View.GONE);
             transparencySlider.setEnabled(false);
        }
    }

    private void processPencilEffect() {
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "processPencilEffect: originalBitmap is null or recycled.");
            return;
        }
        if (!pencilLayerBitmaps.isEmpty()) {
            Log.d(TAG, "Recycling existing pencil bitmaps.");
            recyclePencilBitmaps();
        }

        Log.i(TAG, "Processing pencil effect...");
        createGrayscaleBitmap();
        if (grayscaleBitmap == null || grayscaleBitmap.isRecycled()) {
             Log.e(TAG, "Failed to create grayscale bitmap.");
             return; // Выход, если не удалось создать серое изображение
        }

        int width = grayscaleBitmap.getWidth();
        int height = grayscaleBitmap.getHeight();
        int[] pixels = new int[width * height];
        try {
            grayscaleBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Error getting pixels from grayscale bitmap", e);
            return; // Выход, если не удалось получить пиксели
        }


        int maxThreshold = 240;
        int minThreshold = 20;
        if (PENCIL_HARDNESS.length <= 1) { // Избегаем деления на ноль
             Log.w(TAG, "Only one or zero pencil hardness levels defined.");
             return;
        }
        int thresholdStep = (maxThreshold - minThreshold) / (PENCIL_HARDNESS.length - 1);

        Log.d(TAG, "Generating " + PENCIL_HARDNESS.length + " layers...");
        for (int i = 0; i < PENCIL_HARDNESS.length; i++) {
            int currentThreshold = maxThreshold - (i * thresholdStep);
            Log.v(TAG, "Layer " + i + " (" + PENCIL_HARDNESS[i] + "), Threshold: " + currentThreshold);

            Bitmap layerBitmap = null;
            try {
                 layerBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                 int[] layerPixels = new int[width * height]; // Инициализируется прозрачным

                 for (int j = 0; j < pixels.length; j++) {
                     int grayValue = Color.red(pixels[j]);
                     if (grayValue <= currentThreshold) {
                         layerPixels[j] = Color.BLACK;
                     }
                 }
                 layerBitmap.setPixels(layerPixels, 0, width, 0, 0, width, height);
                 pencilLayerBitmaps.add(layerBitmap); // Добавляем успешно созданный слой

            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "Out of memory creating layer bitmap " + i, oom);
                Toast.makeText(this, "Недостаточно памяти для создания слоев", Toast.LENGTH_SHORT).show();
                recycleBitmap(layerBitmap); // Очищаем частично созданный
                recyclePencilBitmaps(); // Очищаем все, что успели создать
                isPencilMode = false; // Выключаем режим карандаша
                pencilModeSwitch.setChecked(false); // Обновляем Switch
                return; // Прерываем генерацию слоев
            } catch (Exception e) {
                Log.e(TAG, "Error creating layer bitmap " + i, e);
                recycleBitmap(layerBitmap);
            }
        }
        Log.i(TAG, "Pencil effect processed. Generated " + pencilLayerBitmaps.size() + " layers.");
    }

    private void createGrayscaleBitmap() {
        if (originalBitmap == null || originalBitmap.isRecycled()) return;
        Log.d(TAG, "Creating grayscale bitmap...");
        recycleBitmap(grayscaleBitmap);

        try {
            grayscaleBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grayscaleBitmap);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(cm);
            Paint grayPaint = new Paint();
            grayPaint.setColorFilter(filter);
            grayPaint.setAntiAlias(true);
            canvas.drawBitmap(originalBitmap, 0, 0, grayPaint);
            Log.d(TAG, "Grayscale bitmap created.");
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of memory creating grayscale bitmap", oom);
            Toast.makeText(this, "Недостаточно памяти для обработки", Toast.LENGTH_SHORT).show();
            grayscaleBitmap = null;
        } catch (Exception e) {
            Log.e(TAG, "Error creating grayscale bitmap", e);
            grayscaleBitmap = null;
        }
    }

    private void updateImageDisplay() {
        if (overlayImageView == null) return; // Проверка

        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.d(TAG, "updateImageDisplay: Original bitmap is null or recycled. Hiding overlay.");
             overlayImageView.setVisibility(View.GONE);
             transparencySlider.setVisibility(View.GONE);
             transparencySlider.setEnabled(false);
             recycleAllBitmaps(); // Убедимся, что все очищено
             return;
        }

        Log.d(TAG, "Updating image display. Pencil Mode: " + isPencilMode);
        recycleBitmap(finalCompositeBitmap); // Очищаем старый композит

        Bitmap bitmapToShow = null;

        if (isPencilMode && !pencilLayerBitmaps.isEmpty()) {
            Log.d(TAG, "Compositing pencil layers...");
            int width = pencilLayerBitmaps.get(0).getWidth();
            int height = pencilLayerBitmaps.get(0).getHeight();
            boolean anyLayerVisible = false;

            try {
                finalCompositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(finalCompositeBitmap);
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                for (int i = 0; i < pencilLayerBitmaps.size(); i++) {
                    if (i < layerVisibility.length && layerVisibility[i] && pencilLayerBitmaps.get(i) != null && !pencilLayerBitmaps.get(i).isRecycled()) {
                        Log.v(TAG, "Drawing layer " + i);
                        canvas.drawBitmap(pencilLayerBitmaps.get(i), 0, 0, pencilPaint);
                        anyLayerVisible = true;
                    }
                }

                if (!anyLayerVisible) Log.d(TAG, "No layers are visible for compositing.");
                bitmapToShow = finalCompositeBitmap;
                Log.d(TAG, "Compositing complete.");

            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "Out of memory creating final composite bitmap", oom);
                Toast.makeText(this, "Недостаточно памяти для отображения слоев", Toast.LENGTH_SHORT).show();
                recycleBitmap(finalCompositeBitmap); finalCompositeBitmap = null;
                bitmapToShow = originalBitmap; // Fallback
            } catch (Exception e) {
                Log.e(TAG, "Error compositing layers", e);
                bitmapToShow = originalBitmap; // Fallback
            }
        } else {
            Log.d(TAG, "Showing original bitmap.");
            bitmapToShow = originalBitmap;
        }

        if (bitmapToShow != null && !bitmapToShow.isRecycled()) {
            overlayImageView.setImageBitmap(bitmapToShow);
            overlayImageView.setImageMatrix(matrix); // Переприменяем матрицу
            overlayImageView.setVisibility(View.VISIBLE); // Убедимся, что он видим
            Log.d(TAG, "Bitmap set to overlayImageView.");
        } else {
            Log.w(TAG, "bitmapToShow is null or recycled, clearing overlayImageView.");
            overlayImageView.setImageBitmap(null);
             overlayImageView.setVisibility(View.GONE); // Скрываем, если нечего показывать
             transparencySlider.setVisibility(View.GONE);
             transparencySlider.setEnabled(false);
        }
    }


    // --- Диалог выбора слоев ---
    private void showLayerSelectionDialog() {
        Log.d(TAG, "Showing layer selection dialog.");
        if (PENCIL_HARDNESS == null || layerVisibility == null) {
            Log.e(TAG, "Cannot show dialog, layer data is null.");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Выбор слоев");

        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_layer_select, null);
        builder.setView(dialogView);

        RecyclerView recyclerView = dialogView.findViewById(R.id.layersRecyclerView);
        if (recyclerView == null) {
            Log.e(TAG, "RecyclerView not found in dialog layout!");
            Toast.makeText(this, "Ошибка отображения слоев", Toast.LENGTH_SHORT).show();
            return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        final LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this); // Передаем this
        recyclerView.setAdapter(adapter);

        builder.setPositiveButton("OK", (dialog, which) -> {
            dialog.dismiss();
        });

        // Кнопка "Выбрать все"
        builder.setNeutralButton("Все", (dialog, which) -> {
             Arrays.fill(layerVisibility, true);
             if (adapter != null) adapter.notifyDataSetChanged(); // Обновить состояние чекбоксов в диалоге
             updateImageDisplay(); // Обновить основное изображение
             // Не закрываем диалог автоматически
        });
        // Кнопка "Снять все"
         builder.setNegativeButton("Ничего", (dialog, which) -> {
              Arrays.fill(layerVisibility, false);
              if (adapter != null) adapter.notifyDataSetChanged();
              updateImageDisplay();
              // Не закрываем диалог автоматически
         });


        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Реализация интерфейса для обратной связи от Адаптера
    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        Log.d(TAG, "onLayerVisibilityChanged - Position: " + position + ", Visible: " + isVisible);
        // Массив layerVisibility уже обновлен в адаптере
        updateImageDisplay(); // Обновляем композитное изображение
    }

    // --- Управление памятью ---
    private void recycleBitmap(Bitmap bitmap) { if (bitmap != null && !bitmap.isRecycled()) { /* Log.v(TAG, "Recycling bitmap: " + bitmap); */ bitmap.recycle(); } }
    private void recyclePencilBitmaps() { Log.d(TAG, "Recycling " + pencilLayerBitmaps.size() + " pencil layer bitmaps."); for (Bitmap bmp : pencilLayerBitmaps) { recycleBitmap(bmp); } pencilLayerBitmaps.clear(); }
    private void recycleAllBitmaps() { Log.d(TAG, "Recycling all bitmaps..."); recycleBitmap(originalBitmap); originalBitmap = null; recycleBitmap(grayscaleBitmap); grayscaleBitmap = null; recyclePencilBitmaps(); recycleBitmap(finalCompositeBitmap); finalCompositeBitmap = null; Log.d(TAG, "All bitmaps recycled."); }

    // --- Сброс матрицы изображения ---
    private void resetImageMatrix() {
        Log.d(TAG, "Resetting image matrix");
        matrix.reset();
        overlayImageView.post(() -> { // Ждем, пока ImageView будет готов
            if (overlayImageView == null || overlayImageView.getDrawable() == null) return;
            int viewWidth = overlayImageView.getWidth();
            int viewHeight = overlayImageView.getHeight();
            int drawableWidth = overlayImageView.getDrawable().getIntrinsicWidth();
            int drawableHeight = overlayImageView.getDrawable().getIntrinsicHeight();
            if (viewWidth <= 0 || viewHeight <= 0 || drawableWidth <= 0 || drawableHeight <= 0) {
                 Log.w(TAG, "Cannot reset matrix, invalid dimensions.");
                 return;
            }
            matrix.reset();
            float scale; float dx = 0, dy = 0;
            if (drawableWidth * viewHeight > viewWidth * drawableHeight) { scale = (float) viewWidth / (float) drawableWidth; dy = (viewHeight - drawableHeight * scale) * 0.5f; }
            else { scale = (float) viewHeight / (float) drawableHeight; dx = (viewWidth - drawableWidth * scale) * 0.5f; }
            matrix.postScale(scale, scale); matrix.postTranslate(dx, dy);
            overlayImageView.setImageMatrix(matrix);
            savedMatrix.set(matrix);
            Log.d(TAG, "Image matrix reset and applied.");
        });
    }

    // --- Остальной код ---
    @Override protected void onDestroy() { super.onDestroy(); Log.d(TAG, "onDestroy called"); recycleAllBitmaps(); if (cameraProvider != null) { cameraProvider.unbindAll(); } }
    private void hideSystemUI() { /* ... */ Log.v(TAG, "Attempting to hide system UI"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { final WindowInsetsController controller = getWindow().getInsetsController(); if (controller != null) { controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()); controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); } else { Log.w(TAG, "WindowInsetsController is null"); hideSystemUIOldApi(); } } else { hideSystemUIOldApi(); } }
    private void hideSystemUIOldApi() { /* ... */ Log.v(TAG, "Using old API to hide system UI"); View decorView = getWindow().getDecorView(); if (decorView != null) { decorView.setSystemUiVisibility( View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN); } else { Log.w(TAG, "DecorView is null"); } }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) { hideSystemUI(); } }
    private boolean allPermissionsGranted() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private void requestPermissions() { requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA}); }
    private void startCamera() { /* ... */ Log.i(TAG, "startCamera called"); cameraProviderFuture = ProcessCameraProvider.getInstance(this); cameraProviderFuture.addListener(() -> { try { cameraProvider = cameraProviderFuture.get(); Log.i(TAG, "CameraProvider obtained successfully."); bindPreviewUseCase(); } catch (ExecutionException | InterruptedException e) { Log.e(TAG, "Error getting CameraProvider: ", e); Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show(); } }, ContextCompat.getMainExecutor(this)); }
    private void bindPreviewUseCase() { /* ... */ Log.d(TAG, "bindPreviewUseCase called"); if (cameraProvider == null) { Log.e(TAG, "CameraProvider not initialized."); return; } try { cameraProvider.unbindAll(); Log.d(TAG, "Previous use cases unbound."); CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build(); previewUseCase = new Preview.Builder().build(); previewView.post(() -> { Log.d(TAG, "Setting SurfaceProvider for Preview"); previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider()); }); Log.d(TAG, "Binding lifecycle..."); camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase); if (camera != null) { Log.i(TAG, "Camera bound to lifecycle successfully."); setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl()); } else { Log.e(TAG, "Failed to get Camera instance after binding."); zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); } } catch (Exception e) { Log.e(TAG, "Error binding preview use case: ", e); Toast.makeText(this, "Не удалось привязать камеру", Toast.LENGTH_SHORT).show(); zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); } }
    private void setupCameraZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) { /* ... */ Log.d(TAG, "setupCameraZoomSliderState called"); LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState(); zoomStateLiveData.observe(this, zoomState -> { if (zoomState == null) { Log.w(TAG, "ZoomState is null. Disabling zoom slider."); zoomSlider.setValue(0f); zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); } else { Log.i(TAG, "ZoomState updated. LinearZoom: " + zoomState.getLinearZoom()); zoomSlider.setValueFrom(0f); zoomSlider.setValueTo(1f); zoomSlider.setStepSize(0.01f); zoomSlider.setValue(zoomState.getLinearZoom()); zoomSlider.setEnabled(true); zoomSlider.setVisibility(View.VISIBLE); } }); ZoomState currentZoomState = zoomStateLiveData.getValue(); if (currentZoomState == null) { zoomSlider.setEnabled(false); zoomSlider.setVisibility(View.INVISIBLE); } else { zoomSlider.setValueFrom(0f); zoomSlider.setValueTo(1f); zoomSlider.setStepSize(0.01f); zoomSlider.setValue(currentZoomState.getLinearZoom()); zoomSlider.setEnabled(true); zoomSlider.setVisibility(View.VISIBLE); } }
    private void setupCameraZoomSliderListener() { /* ... */ zoomSlider.addOnChangeListener((slider, value, fromUser) -> { if (camera != null && fromUser) { Log.v(TAG, "Camera zoom slider changed: " + value); camera.getCameraControl().setLinearZoom(value); } }); }

}
