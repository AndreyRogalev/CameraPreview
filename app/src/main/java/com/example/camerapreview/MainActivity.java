// --- Полный путь к файлу ---
// /root/CameraPreview/app/src/main/java/com/example/camerapreview/MainActivity.java
// --- Начало кода файла ---
package com.example.camerapreview;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button; // Добавили Button
import android.widget.ImageView; // Добавили ImageView
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
// Убедитесь, что добавлена зависимость guava

import com.google.android.material.slider.Slider;

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements View.OnTouchListener { // Реализуем OnTouchListener

    private static final String TAG = "CameraPreview";
    private PreviewView previewView;
    private Slider zoomSlider;
    private Button loadImageButton; // Кнопка загрузки
    private ImageView overlayImageView; // ImageView для оверлея
    private Slider transparencySlider; // Слайдер прозрачности

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;

    // --- Для обработки жестов изображения ---
    private Matrix matrix = new Matrix(); // Матрица для трансформаций ImageView
    private Matrix savedMatrix = new Matrix(); // Сохраненная матрица

    // Состояния для жестов
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    // Точки касания
    private PointF start = new PointF(); // Начальная точка для DRAG
    private PointF mid = new PointF(); // Средняя точка для ZOOM
    private float oldDist = 1f; // Предыдущее расстояние между пальцами
    private float oldAngle = 0f; // Предыдущий угол между пальцами
    private float rotation = 0f; // Текущий угол поворота
    // --- Конец переменных для жестов ---

    private ScaleGestureDetector scaleGestureDetector; // Детектор масштабирования

    // ActivityResultLauncher для запроса разрешений
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : result.values()) {
                    allPermissionsGranted &= granted;
                }
                if (allPermissionsGranted) {
                    startCamera();
                    Log.d(TAG, "Permissions granted.");
                } else {
                    Log.d(TAG, "Permissions not granted.");
                    Toast.makeText(this, "Разрешения не предоставлены", Toast.LENGTH_SHORT).show();
                }
            });

    // ActivityResultLauncher для выбора изображения
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Log.d(TAG, "Image URI selected: " + uri);
                    loadImageIntoOverlay(uri);
                } else {
                    Log.d(TAG, "No image selected.");
                }
            });


    @SuppressLint("ClickableViewAccessibility") // Подавление предупреждения для setOnTouchListener
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        zoomSlider = findViewById(R.id.zoom_slider);
        loadImageButton = findViewById(R.id.load_image_button);
        overlayImageView = findViewById(R.id.overlayImageView);
        transparencySlider = findViewById(R.id.transparency_slider);

        // Инициализация детектора масштабирования
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        // Устанавливаем слушатель касаний НА ImageView
        overlayImageView.setOnTouchListener(this);

        // Скрываем UI после отрисовки View
        previewView.post(this::hideSystemUI);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        // --- Настройка слушателей кнопок и слайдеров ---
        setupCameraZoomSliderListener(); // Переименовал для ясности
        setupLoadImageButtonListener();
        setupTransparencySliderListener();

        // Изначально слайдер прозрачности скрыт/неактивен
        transparencySlider.setVisibility(View.GONE);
        transparencySlider.setEnabled(false);
    }

    // --- Обработка касаний для ImageView (перемещение, масштабирование, вращение) ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        ImageView view = (ImageView) v;
        view.setScaleType(ImageView.ScaleType.MATRIX); // Убедимся, что используется матрица

        // Передаем событие в ScaleGestureDetector
        scaleGestureDetector.onTouchEvent(event);

        PointF curr = new PointF(event.getX(), event.getY());

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: // Первое касание
                savedMatrix.set(matrix);
                start.set(curr);
                mode = DRAG;
                Log.d(TAG, "Mode=DRAG");
                break;
            case MotionEvent.ACTION_POINTER_DOWN: // Второе касание (и последующие)
                oldDist = spacing(event);
                oldAngle = rotation(event); // Получаем начальный угол
                Log.d(TAG, "oldDist=" + oldDist);
                if (oldDist > 10f) { // Начинаем масштабирование/вращение, если пальцы достаточно разведены
                    savedMatrix.set(matrix);
                    midPoint(mid, event); // Находим среднюю точку
                    mode = ZOOM;
                    Log.d(TAG, "Mode=ZOOM");
                }
                break;
            case MotionEvent.ACTION_UP: // Последнее касание убрано
            case MotionEvent.ACTION_POINTER_UP: // Одно из касаний убрано
                mode = NONE;
                Log.d(TAG, "Mode=NONE");
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG) {
                    matrix.set(savedMatrix);
                    float dx = curr.x - start.x;
                    float dy = curr.y - start.y;
                    matrix.postTranslate(dx, dy);
                } else if (mode == ZOOM && event.getPointerCount() >= 2) {
                    float newDist = spacing(event);
                    float newAngle = rotation(event); // Текущий угол
                    Log.d(TAG, "newDist=" + newDist);
                    if (newDist > 10f) {
                        matrix.set(savedMatrix);
                        float scale = newDist / oldDist;
                        matrix.postScale(scale, scale, mid.x, mid.y);

                        // Вращение
                        float deltaAngle = newAngle - oldAngle;
                        rotation += deltaAngle; // Накапливаем угол
                        matrix.postRotate(deltaAngle, mid.x, mid.y); // Вращаем на разницу углов
                        oldAngle = newAngle; // Обновляем старый угол для следующего шага
                    }
                }
                break;
        }

        view.setImageMatrix(matrix); // Применяем итоговую матрицу к ImageView
        return true; // Возвращаем true, чтобы указать, что событие обработано
    }

    // --- Вспомогательные методы для жестов ---

    // Расстояние между первыми двумя пальцами
    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    // Средняя точка между первыми двумя пальцами
    private void midPoint(PointF point, MotionEvent event) {
         if (event.getPointerCount() < 2) return;
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }

     // Угол между первыми двумя пальцами
    private float rotation(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        double delta_x = (event.getX(0) - event.getX(1));
        double delta_y = (event.getY(0) - event.getY(1));
        double radians = Math.atan2(delta_y, delta_x);
        return (float) Math.toDegrees(radians);
    }


    // --- Слушатель для ScaleGestureDetector ---
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Масштабирование обрабатывается в ACTION_MOVE при mode == ZOOM
            // Но можно использовать detector.getScaleFactor() здесь как альтернативу
            // float scaleFactor = detector.getScaleFactor();
            // matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            // overlayImageView.setImageMatrix(matrix);
            return true; // Указываем, что масштабирование обработано (даже если логика в onTouch)
        }
    }

    // --- Логика загрузки изображения ---
    private void setupLoadImageButtonListener() {
        loadImageButton.setOnClickListener(v -> {
            Log.d(TAG, "Load image button clicked");
            pickImageLauncher.launch("image/*"); // Запускаем выбор изображения
        });
    }

    private void loadImageIntoOverlay(Uri imageUri) {
        try {
            overlayImageView.setImageURI(imageUri);
            overlayImageView.setVisibility(View.VISIBLE);
            transparencySlider.setVisibility(View.VISIBLE);
            transparencySlider.setEnabled(true);
            transparencySlider.setValue(1.0f); // Сброс прозрачности на непрозрачный
            overlayImageView.setAlpha(1.0f);

            // Сброс матрицы трансформаций при загрузке нового изображения
            matrix.reset();
            // Можно установить начальный масштаб/положение, если нужно
            // matrix.postTranslate(x, y);
            // matrix.postScale(s, s);
            overlayImageView.setImageMatrix(matrix);
            savedMatrix.set(matrix); // Обновить сохраненную матрицу
            mode = NONE; // Сбросить режим жестов

            Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error loading image into overlay", e);
            Toast.makeText(this, "Ошибка загрузки изображения", Toast.LENGTH_SHORT).show();
            overlayImageView.setVisibility(View.GONE);
            transparencySlider.setVisibility(View.GONE);
            transparencySlider.setEnabled(false);
        }
    }

    // --- Слушатель слайдера прозрачности ---
    private void setupTransparencySliderListener() {
        transparencySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (overlayImageView.getVisibility() == View.VISIBLE && fromUser) {
                overlayImageView.setAlpha(value); // Устанавливаем прозрачность
            }
        });
    }


    // --- Остальной код (камера, разрешения, UI) без существенных изменений ---

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                 Log.w(TAG, "WindowInsetsController is null");
                 hideSystemUIOldApi();
            }
        } else {
            hideSystemUIOldApi();
        }
    }

    private void hideSystemUIOldApi() {
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
            Log.w(TAG, "DecorView is null when trying old API");
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
        requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreviewUseCase();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error getting CameraProvider: ", e);
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewUseCase() {
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider not initialized.");
            return;
        }
        cameraProvider.unbindAll();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        previewUseCase = new Preview.Builder().build();
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);
            if (camera != null) {
                setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl()); // Переименовал
            } else {
                Log.e(TAG, "Failed to get Camera instance after binding.");
                zoomSlider.setEnabled(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error binding preview use case: ", e);
            Toast.makeText(this, "Failed to bind camera", Toast.LENGTH_SHORT).show();
            zoomSlider.setEnabled(false);
        }
    }

    // Переименовал для ясности
    private void setupCameraZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        ZoomState zoomState = zoomStateLiveData.getValue();
        if (zoomState == null) {
            Log.w(TAG, "Zoom not supported or ZoomState not available");
            zoomSlider.setValue(0f);
            zoomSlider.setEnabled(false);
            return;
        }
        float minZoom = zoomState.getMinZoomRatio();
        float maxZoom = zoomState.getMaxZoomRatio();
        Log.d(TAG, "Camera Zoom Ratio Range: " + minZoom + " - " + maxZoom);
        zoomSlider.setValueFrom(0f);
        zoomSlider.setValueTo(1f);
        zoomSlider.setStepSize(0.01f);
        zoomSlider.setValue(zoomState.getLinearZoom());
        zoomSlider.setEnabled(true);
        Log.d(TAG, "Camera Zoom slider setup: MinLinear=0.0, MaxLinear=1.0, CurrentLinear=" + zoomState.getLinearZoom());
    }

    // Переименовал для ясности
    private void setupCameraZoomSliderListener() {
         zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
             if (camera != null && fromUser) {
                 ListenableFuture<Void> future = camera.getCameraControl().setLinearZoom(value);
                 future.addListener(()->{/* Ignore result */}, ContextCompat.getMainExecutor(this));
             }
         });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
}
// --- Конец кода файла ---
