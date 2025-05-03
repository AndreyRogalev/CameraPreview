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
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture; // Нужна зависимость guava, если ее нет
// Если нет guava, Gradle может предложить ее добавить при синхронизации,
// или добавьте вручную: implementation "com.google.guava:guava:31.1-android" (или новее)

import com.google.android.material.slider.Slider; // Импорт для Slider

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraPreview";
    private PreviewView previewView;
    private Button switchCameraButton;
    private Slider zoomSlider;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera; // Текущий активный объект камеры
    private Preview previewUseCase;

    // Изначально выбираем заднюю камеру
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

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
                        // Можно добавить логику, что делать, если разрешения не дали
                        // finish(); // Например, закрыть приложение
                    }
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        switchCameraButton = findViewById(R.id.switch_camera_button);
        zoomSlider = findViewById(R.id.zoom_slider);

        // Проверяем и запрашиваем разрешения
        if (allPermissionsGranted()) {
            startCamera(); // Начинаем работу с камерой, если разрешения уже есть
        } else {
            requestPermissions(); // Запрашиваем разрешения
        }

        // Обработчик нажатия на кнопку переключения камеры
        switchCameraButton.setOnClickListener(v -> {
            switchCamera();
        });

        // Обработчик изменения значения ползунка зума (инициализируется после привязки камеры)
        setupZoomSliderListener(); // Настраиваем слушатель заранее

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

        // Создание CameraSelector на основе текущего lensFacing
        // Вот здесь происходит "программно-аппаратный анализ": CameraX подбирает
        // подходящую камеру по указанному критерию (LENS_FACING_BACK или LENS_FACING_FRONT)
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
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
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при привязке UseCase Preview: ", e);
            Toast.makeText(this, "Не удалось привязать камеру", Toast.LENGTH_SHORT).show();
            // Возможно, камера с нужным lensFacing не найдена
        }
    }

    // Переключение между фронтальной и задней камерами
    private void switchCamera() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        // Перепривязываем use cases с новым селектором
        bindPreviewUseCase();
    }

    // Настройка состояния и пределов ползунка зума
    private void setupZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        ZoomState zoomState = zoomStateLiveData.getValue(); // Получаем текущее состояние

        if (zoomState == null) {
            Log.w(TAG, "Не удалось получить ZoomState");
            zoomSlider.setEnabled(false); // Отключаем слайдер, если зум не поддерживается
            return;
        }

        // Получаем минимальный и максимальный линейный зум (от 0.0 до 1.0)
        float minZoom = 0f; // Линейный зум всегда от 0
        float maxZoom = 1f; // Линейный зум всегда до 1

        zoomSlider.setValueFrom(minZoom);
        zoomSlider.setValueTo(maxZoom);
        zoomSlider.setStepSize(0.01f); // Более мелкий шаг для плавности

        // Устанавливаем текущее значение ползунка
        zoomSlider.setValue(zoomState.getLinearZoom());
        zoomSlider.setEnabled(true); // Включаем слайдер
        Log.d(TAG, "Zoom настроен: MinLinear=" + minZoom + ", MaxLinear=" + maxZoom + ", CurrentLinear=" + zoomState.getLinearZoom());

    }

    // Настройка слушателя изменений ползунка зума
    private void setupZoomSliderListener() {
         zoomSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                if (camera != null && fromUser) {
                    // Используем setLinearZoom для более интуитивного управления через слайдер 0-1
                    ListenableFuture<Void> future = camera.getCameraControl().setLinearZoom(value);
                    future.addListener(() -> {
                        try {
                             future.get(); // Просто проверяем на исключения
                             // Log.v(TAG, "Linear Zoom set to: " + value); // Для отладки
                        } catch (Exception e) {
                             Log.e(TAG, "Error setting linear zoom", e);
                        }
                    }, ContextCompat.getMainExecutor(MainActivity.this));
                }
            }
        });
    }

    // Не забываем отвязывать камеру при уничтожении Activity (хотя bindToLifecycle часто справляется сам)
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
}
