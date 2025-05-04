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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
// Убедитесь, что добавлена зависимость guava

import com.google.android.material.slider.Slider;

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements View.OnTouchListener {

    private static final String TAG = "CameraPreviewApp"; // Изменил TAG для логов
    private PreviewView previewView;
    private Slider zoomSlider; // Слайдер зума камеры
    private Button loadImageButton;
    private ImageView overlayImageView;
    private Slider transparencySlider; // Слайдер прозрачности изображения

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;

    // --- Для обработки жестов изображения ---
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;
    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;
    private float oldAngle = 0f;
    // --- Конец переменных для жестов ---

    private ScaleGestureDetector scaleGestureDetector;

    // ActivityResultLauncher для запроса разрешений
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted != null) { // Добавил проверку на null
                         allPermissionsGranted &= granted;
                    } else {
                        allPermissionsGranted = false; // Если хоть одно разрешение null, считаем что не все даны
                    }
                }
                if (allPermissionsGranted) {
                    Log.i(TAG, "Camera permission granted.");
                    startCamera();
                } else {
                    Log.w(TAG, "Camera permission not granted.");
                    Toast.makeText(this, "Разрешение на камеру не предоставлено", Toast.LENGTH_SHORT).show();
                    // Возможно, стоит деактивировать функционал камеры или закрыть приложение
                }
            });

    // ActivityResultLauncher для выбора изображения
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Log.i(TAG, "Image URI selected: " + uri);
                    loadImageIntoOverlay(uri);
                } else {
                    Log.i(TAG, "No image selected by user.");
                }
            });


    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity starting");

        // Находим все View
        previewView = findViewById(R.id.previewView);
        zoomSlider = findViewById(R.id.zoom_slider);
        loadImageButton = findViewById(R.id.load_image_button);
        overlayImageView = findViewById(R.id.overlayImageView);
        transparencySlider = findViewById(R.id.transparency_slider);

        // Проверяем, нашлись ли View (для отладки)
        if (previewView == null || zoomSlider == null || loadImageButton == null || overlayImageView == null || transparencySlider == null) {
            Log.e(TAG, "onCreate: One or more views not found!");
            Toast.makeText(this, "Ошибка: Не найдены элементы интерфейса", Toast.LENGTH_LONG).show();
            return; // Прерываем выполнение, если что-то не так
        } else {
            Log.d(TAG, "onCreate: All views found");
        }


        // Инициализация детектора масштабирования
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        // Устанавливаем слушатель касаний НА ImageView
        overlayImageView.setOnTouchListener(this);

        // Скрываем UI после отрисовки View
        previewView.post(this::hideSystemUI);

        // Проверяем и запрашиваем разрешения КАМЕРЫ
        if (allPermissionsGranted()) {
            Log.d(TAG, "onCreate: Camera permission already granted");
            startCamera();
        } else {
            Log.d(TAG, "onCreate: Requesting camera permission");
            requestPermissions();
        }

        // --- Настройка слушателей ---
        setupCameraZoomSliderListener();
        setupLoadImageButtonListener();
        setupTransparencySliderListener();

        // Убедимся, что оверлей и его слайдер скрыты при старте
        overlayImageView.setVisibility(View.GONE);
        transparencySlider.setVisibility(View.GONE);
        transparencySlider.setEnabled(false); // И деактивирован

        Log.d(TAG, "onCreate: Setup complete");
    }

    // --- Обработка касаний (без изменений) ---
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v.getId() != R.id.overlayImageView) return false; // Обрабатываем только касания ImageView

        ImageView view = (ImageView) v;
        // view.setScaleType(ImageView.ScaleType.MATRIX); // Можно установить один раз в onCreate

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
    // --- Вспомогательные методы для жестов (без изменений) ---
    private float spacing(MotionEvent event) { if (event.getPointerCount() < 2) return 0f; float x = event.getX(0) - event.getX(1); float y = event.getY(0) - event.getY(1); return (float) Math.sqrt(x * x + y * y); }
    private void midPoint(PointF point, MotionEvent event) { if (event.getPointerCount() < 2) return; float x = event.getX(0) + event.getX(1); float y = event.getY(0) + event.getY(1); point.set(x / 2, y / 2); }
    private float rotation(MotionEvent event) { if (event.getPointerCount() < 2) return 0f; double delta_x = (event.getX(0) - event.getX(1)); double delta_y = (event.getY(0) - event.getY(1)); double radians = Math.atan2(delta_y, delta_x); return (float) Math.toDegrees(radians); }
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener { @Override public boolean onScale(ScaleGestureDetector detector) { return true; } }


    // --- Логика загрузки изображения ---
    private void setupLoadImageButtonListener() {
        loadImageButton.setOnClickListener(v -> {
            Log.i(TAG, "Load image button pressed. Launching image picker.");
            try {
                 pickImageLauncher.launch("image/*"); // Запускаем выбор изображения
            } catch (Exception e) {
                Log.e(TAG, "Error launching image picker", e);
                Toast.makeText(this, "Не удалось открыть галерею", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadImageIntoOverlay(Uri imageUri) {
        Log.d(TAG, "loadImageIntoOverlay started for URI: " + imageUri);
        try {
            // Устанавливаем изображение
            overlayImageView.setImageURI(imageUri);
            Log.d(TAG, "Image URI set to ImageView");

             // Сброс матрицы трансформаций и установка типа масштабирования
            overlayImageView.setScaleType(ImageView.ScaleType.MATRIX);
            matrix.reset();
            // --- Начальное позиционирование и масштабирование (ВАЖНО!) ---
            // Нужно подождать, пока ImageView получит размеры и изображение загрузится,
            // чтобы правильно центрировать и масштабировать. Используем post().
            overlayImageView.post(() -> {
                 if (overlayImageView.getDrawable() == null) {
                     Log.e(TAG, "Drawable is null after setting URI and posting. Cannot calculate initial matrix.");
                     return; // Выходим, если изображение не загрузилось
                 }
                // Получаем размеры View и изображения
                int viewWidth = overlayImageView.getWidth();
                int viewHeight = overlayImageView.getHeight();
                int drawableWidth = overlayImageView.getDrawable().getIntrinsicWidth();
                int drawableHeight = overlayImageView.getDrawable().getIntrinsicHeight();

                if (viewWidth == 0 || viewHeight == 0 || drawableWidth <= 0 || drawableHeight <= 0) {
                     Log.w(TAG, "Cannot calculate initial matrix: Zero dimensions (View: " + viewWidth + "x" + viewHeight + ", Drawable: " + drawableWidth + "x" + drawableHeight + ")");
                     return; // Не можем рассчитать без размеров
                }

                Log.d(TAG, "Calculating initial matrix. View: " + viewWidth + "x" + viewHeight + ", Drawable: " + drawableWidth + "x" + drawableHeight);

                matrix.reset(); // Снова сбрасываем на всякий случай

                // Рассчитываем масштаб, чтобы вписать изображение в View (аналог fitCenter)
                float scale;
                float dx = 0, dy = 0;

                if (drawableWidth * viewHeight > viewWidth * drawableHeight) {
                    // Изображение шире, чем View (относительно)
                    scale = (float) viewWidth / (float) drawableWidth;
                    dy = (viewHeight - drawableHeight * scale) * 0.5f; // Центрируем по вертикали
                } else {
                    // Изображение выше, чем View (относительно)
                    scale = (float) viewHeight / (float) drawableHeight;
                    dx = (viewWidth - drawableWidth * scale) * 0.5f; // Центрируем по горизонтали
                }

                 // Применяем масштаб и центрирование
                 matrix.postScale(scale, scale);
                 matrix.postTranslate(dx, dy);

                 overlayImageView.setImageMatrix(matrix); // Применяем начальную матрицу
                 savedMatrix.set(matrix); // Сохраняем начальную матрицу
                 mode = NONE; // Сбрасываем режим жестов
                 Log.i(TAG, "Initial matrix calculated and applied. Scale: " + scale + ", dx: " + dx + ", dy: " + dy);
            });


            // Делаем видимым и активным оверлей и слайдер
            overlayImageView.setVisibility(View.VISIBLE);
            transparencySlider.setVisibility(View.VISIBLE);
            transparencySlider.setEnabled(true);
            transparencySlider.setValue(1.0f); // Сброс прозрачности
            overlayImageView.setAlpha(1.0f);

            Log.i(TAG, "Overlay ImageView and Transparency Slider made visible and enabled.");
            Toast.makeText(this, "Изображение загружено", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error processing image URI in loadImageIntoOverlay", e);
            Toast.makeText(this, "Ошибка обработки изображения", Toast.LENGTH_SHORT).show();
            overlayImageView.setVisibility(View.GONE); // Скрываем при ошибке
            transparencySlider.setVisibility(View.GONE);
            transparencySlider.setEnabled(false);
        }
    }

    // --- Слушатель слайдера прозрачности ---
    private void setupTransparencySliderListener() {
        transparencySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (overlayImageView.getVisibility() == View.VISIBLE && fromUser) {
                 Log.v(TAG, "Transparency slider changed: " + value); // Добавил лог
                overlayImageView.setAlpha(value); // Устанавливаем прозрачность
            }
        });
    }

    // --- Код камеры, разрешений, UI (как раньше, но с логами) ---

    private void hideSystemUI() { /* ... код как в предыдущем ответе ... */ Log.v(TAG, "Attempting to hide system UI"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { final WindowInsetsController controller = getWindow().getInsetsController(); if (controller != null) { controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars()); controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); } else { Log.w(TAG, "WindowInsetsController is null even after posting hideSystemUI"); hideSystemUIOldApi(); } } else { hideSystemUIOldApi(); } }
    private void hideSystemUIOldApi() { /* ... код как в предыдущем ответе ... */ Log.v(TAG, "Using old API to hide system UI"); View decorView = getWindow().getDecorView(); if (decorView != null) { decorView.setSystemUiVisibility( View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN); } else { Log.w(TAG, "DecorView is null when trying old API"); } }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) { hideSystemUI(); } }
    private boolean allPermissionsGranted() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private void requestPermissions() { requestPermissionLauncher.launch(new String[]{Manifest.permission.CAMERA}); }

    private void startCamera() {
        Log.i(TAG, "startCamera called");
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Log.i(TAG, "CameraProvider obtained successfully.");
                bindPreviewUseCase();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error getting CameraProvider: ", e);
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewUseCase() {
        Log.d(TAG, "bindPreviewUseCase called");
        if (cameraProvider == null) { Log.e(TAG, "CameraProvider not initialized. Cannot bind."); return; }
        try {
             cameraProvider.unbindAll(); // Отвязываем перед новой привязкой
             Log.d(TAG, "Previous use cases unbound.");

            CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
            previewUseCase = new Preview.Builder().build();

             // --- Важно: Получаем Surface Provider ПОСЛЕ того, как View гарантированно готово ---
             previewView.post(() -> { // Используем post, чтобы убедиться, что PreviewView готово
                 Log.d(TAG, "Setting SurfaceProvider for Preview");
                 previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
             });

            Log.d(TAG, "Binding lifecycle...");
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);

            if (camera != null) {
                Log.i(TAG, "Camera bound to lifecycle successfully.");
                setupCameraZoomSliderState(camera.getCameraInfo(), camera.getCameraControl());
            } else {
                Log.e(TAG, "Failed to get Camera instance after binding.");
                zoomSlider.setEnabled(false);
                zoomSlider.setVisibility(View.INVISIBLE); // Скрываем зум, если камера не привязалась
            }
        } catch (IllegalStateException e) {
             Log.e(TAG, "Error binding preview use case: IllegalStateException (SurfaceProvider might not be ready?)", e);
             Toast.makeText(this, "Ошибка привязк preview", Toast.LENGTH_SHORT).show();
             zoomSlider.setEnabled(false);
             zoomSlider.setVisibility(View.INVISIBLE);
        } catch (Exception e) {
            Log.e(TAG, "Error binding preview use case: ", e);
            Toast.makeText(this, "Не удалось привязать камеру", Toast.LENGTH_SHORT).show();
            zoomSlider.setEnabled(false);
            zoomSlider.setVisibility(View.INVISIBLE);
        }
    }

     private void setupCameraZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        Log.d(TAG, "setupCameraZoomSliderState called");
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        // Добавляем Observer, чтобы реагировать на ИЗМЕНЕНИЯ состояния зума (например, после привязки)
        zoomStateLiveData.observe(this, zoomState -> {
            if (zoomState == null) {
                Log.w(TAG, "ZoomState is null. Disabling zoom slider.");
                zoomSlider.setValue(0f);
                zoomSlider.setEnabled(false);
                zoomSlider.setVisibility(View.INVISIBLE); // Скрываем, если не поддерживается
            } else {
                 Log.i(TAG, "ZoomState updated. MinRatio: " + zoomState.getMinZoomRatio() + ", MaxRatio: " + zoomState.getMaxZoomRatio() + ", LinearZoom: " + zoomState.getLinearZoom());
                 // Настраиваем слайдер для ЛИНЕЙНОГО зума
                 zoomSlider.setValueFrom(0f);
                 zoomSlider.setValueTo(1f);
                 zoomSlider.setStepSize(0.01f);
                 zoomSlider.setValue(zoomState.getLinearZoom()); // Устанавливаем текущее значение
                 zoomSlider.setEnabled(true); // Включаем
                 zoomSlider.setVisibility(View.VISIBLE); // Показываем
            }
        });
         // Первоначальная настройка на основе текущего значения (если оно уже есть)
         ZoomState currentZoomState = zoomStateLiveData.getValue();
         if (currentZoomState == null) {
             zoomSlider.setEnabled(false);
             zoomSlider.setVisibility(View.INVISIBLE);
         } else {
             zoomSlider.setValueFrom(0f);
             zoomSlider.setValueTo(1f);
             zoomSlider.setStepSize(0.01f);
             zoomSlider.setValue(currentZoomState.getLinearZoom());
             zoomSlider.setEnabled(true);
             zoomSlider.setVisibility(View.VISIBLE);
         }
    }

    private void setupCameraZoomSliderListener() {
         zoomSlider.addOnChangeListener((slider, value, fromUser) -> {
             if (camera != null && fromUser) {
                 Log.v(TAG, "Camera zoom slider changed: " + value);
                 camera.getCameraControl().setLinearZoom(value); // Используем fire-and-forget
             }
         });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
}
// --- Конец кода файла ---
