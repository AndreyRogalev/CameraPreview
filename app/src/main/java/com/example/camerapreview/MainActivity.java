// --- Полный путь к файлу ---
// /root/CameraPreview/app/src/main/java/com/example/camerapreview/MainActivity.java
// (Замените /root/ на актуальный путь к вашей домашней директории в Termux/Ubuntu, если он отличается)
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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
// Убедитесь, что добавлена зависимость guava: implementation "com.google.guava:guava:31.1-android" (или новее) в app/build.gradle

import com.google.android.material.slider.Slider;

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraPreview";
    private PreviewView previewView;
    private Slider zoomSlider;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera; // Текущий активный объект камеры
    private Preview previewUseCase;

    // ActivityResultLauncher для запроса разрешений
    private final ActivityResultLauncher<String[]> activityResultLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), new ActivityResultCallback<Map<String, Boolean>>() {
                @Override
                public void onActivityResult(Map<String, Boolean> result) {
                    boolean allPermissionsGranted = true;
                    for (Boolean granted : result.values()) {
                        allPermissionsGranted &= granted;
                    }

                    if (allPermissionsGranted) {
                        startCamera(); // Запускаем камеру, если разрешение получено
                        Log.d(TAG, "Permissions granted by the user.");
                    } else {
                        Log.d(TAG, "Permissions not granted by the user.");
                        Toast.makeText(MainActivity.this, "Разрешения не предоставлены", Toast.LENGTH_SHORT).show();
                        // Можно закрыть приложение, если камера критична
                        // finish();
                    }
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // --- ИЗМЕНЕНО ЗДЕСЬ ---
        // Сначала устанавливаем макет
        setContentView(R.layout.activity_main);
        // Затем скрываем системные панели
        hideSystemUI();
        // --- КОНЕЦ ИЗМЕНЕНИЯ ---

        previewView = findViewById(R.id.previewView);
        zoomSlider = findViewById(R.id.zoom_slider);

        // Проверяем и запрашиваем разрешения
        if (allPermissionsGranted()) {
            startCamera(); // Начинаем работу с камерой, если разрешения уже есть
        } else {
            requestPermissions(); // Запрашиваем разрешения
        }

        // Настраиваем слушатель ползунка зума
        setupZoomSliderListener();
    }

    // Метод для скрытия системных панелей (статус-бар, навигация)
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Для Android 11 (API 30) и выше
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Для старых версий Android (ниже API 30)
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    // Для большей надежности скрытия на старых API
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }


    // Проверка наличия необходимых разрешений
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // Запрос разрешений
    private void requestPermissions() {
        activityResultLauncher.launch(new String[]{Manifest.permission.CAMERA});
    }

    // Запуск и привязка камеры к жизненному циклу и PreviewView
    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreviewUseCase();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Ошибка при получении CameraProvider: ", e);
                Toast.makeText(this, "Не удалось запустить камеру", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Привязка UseCase предпросмотра
    private void bindPreviewUseCase() {
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider не инициализирован.");
            return;
        }

        // Отвязываем предыдущие use cases перед привязкой новых
        cameraProvider.unbindAll();

        // Создание CameraSelector для основной задней камеры
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // Настройка Preview UseCase
        previewUseCase = new Preview.Builder().build();
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            // Привязываем CameraSelector и Preview UseCase к жизненному циклу Activity
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);

            // Настраиваем ползунок зума после успешной привязки камеры
            if (camera != null) {
                setupZoomSliderState(camera.getCameraInfo(), camera.getCameraControl());
            } else {
                Log.e(TAG, "Не удалось получить объект Camera после привязки.");
                zoomSlider.setEnabled(false); // Отключаем зум если камера не привязалась
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при привязке UseCase Preview: ", e);
            Toast.makeText(this, "Не удалось привязать заднюю камеру", Toast.LENGTH_SHORT).show();
            zoomSlider.setEnabled(false); // Отключаем зум если камера не привязалась
        }
    }

    // Настройка состояния и пределов ползунка зума
    private void setupZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        ZoomState zoomState = zoomStateLiveData.getValue(); // Получаем текущее состояние

        // Просто проверяем, доступно ли состояние зума
        if (zoomState == null) {
            Log.w(TAG, "Зум не поддерживается или не удалось получить ZoomState");
            zoomSlider.setValue(0f); // Сбросить значение на всякий случай
            zoomSlider.setEnabled(false); // Отключаем слайдер
            return;
        }

        // Получаем реальные минимальный и максимальный коэффициенты зума
        float minZoom = zoomState.getMinZoomRatio();
        float maxZoom = zoomState.getMaxZoomRatio();

        Log.d(TAG, "Zoom Ratio Range: " + minZoom + " - " + maxZoom);

        // Настраиваем слайдер для ЛИНЕЙНОГО зума (0.0 - 1.0)
        zoomSlider.setValueFrom(0f);
        zoomSlider.setValueTo(1f);
        zoomSlider.setStepSize(0.01f); // Более мелкий шаг для плавности

        // Устанавливаем текущее значение ползунка на основе линейного зума
        zoomSlider.setValue(zoomState.getLinearZoom());
        zoomSlider.setEnabled(true); // Включаем слайдер
        Log.d(TAG, "Zoom настроен: MinLinear=0.0, MaxLinear=1.0, CurrentLinear=" + zoomState.getLinearZoom());
    }

    // Настройка слушателя изменений ползунка зума
    private void setupZoomSliderListener() {
         zoomSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                if (camera != null && fromUser) {
                    // Используем setLinearZoom для интуитивного управления через слайдер 0-1
                    ListenableFuture<Void> future = camera.getCameraControl().setLinearZoom(value);
                    future.addListener(() -> {
                        try {
                             future.get(); // Просто проверяем на исключения
                        } catch (Exception e) {
                             // Игнорируем CameraControl.OperationCanceledException, если пользователь быстро двигает слайдер
                             if (!(e instanceof androidx.camera.core.CameraControl.OperationCanceledException)) {
                                Log.e(TAG, "Ошибка при установке linear zoom", e);
                             }
                        }
                    }, ContextCompat.getMainExecutor(MainActivity.this));
                }
            }
        });
    }

    // Отвязываем камеру при уничтожении Activity
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            // CameraX обычно сам корректно отвязывает при уничтожении Activity,
            // связанной через bindToLifecycle, но явный unbind не повредит.
            cameraProvider.unbindAll();
        }
    }
}
// --- Конец кода файла ---
