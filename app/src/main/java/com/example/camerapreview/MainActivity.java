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
// УДАЛЕНО: import android.widget.Button; - больше не нужен
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
// import com.google.guava:guava:31.1-android (или новее), если еще не добавлено

import com.google.android.material.slider.Slider;

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraPreview";
    private PreviewView previewView;
    // УДАЛЕНО: private Button switchCameraButton;
    private Slider zoomSlider;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;

    // УДАЛЕНО: private int lensFacing = CameraSelector.LENS_FACING_BACK;

    private final ActivityResultLauncher<String[]> activityResultLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), new ActivityResultCallback<Map<String, Boolean>>() {
                @Override
                public void onActivityResult(Map<String, Boolean> result) {
                    boolean allPermissionsGranted = true;
                    for (Boolean granted : result.values()) {
                        allPermissionsGranted &= granted;
                    }

                    if (allPermissionsGranted) {
                        startCamera();
                        Log.d(TAG, "Permissions granted by the user.");
                    } else {
                        Log.d(TAG, "Permissions not granted by the user.");
                        Toast.makeText(MainActivity.this, "Разрешения не предоставлены", Toast.LENGTH_SHORT).show();
                        // finish();
                    }
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        // УДАЛЕНО: switchCameraButton = findViewById(R.id.switch_camera_button);
        zoomSlider = findViewById(R.id.zoom_slider);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        // УДАЛЕНО: Обработчик нажатия на кнопку переключения камеры
        /*
        switchCameraButton.setOnClickListener(v -> {
            switchCamera();
        });
        */

        setupZoomSliderListener();

    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        activityResultLauncher.launch(new String[]{Manifest.permission.CAMERA});
    }

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

    private void bindPreviewUseCase() {
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider не инициализирован.");
            return;
        }

        cameraProvider.unbindAll();

        // ИЗМЕНЕНО: Всегда используем основную заднюю камеру
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK) // Фиксируем заднюю камеру
                .build();

        previewUseCase = new Preview.Builder().build();
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);

            if (camera != null) {
                setupZoomSliderState(camera.getCameraInfo(), camera.getCameraControl());
            } else {
                Log.e(TAG, "Не удалось получить объект Camera после привязки.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при привязке UseCase Preview: ", e);
             // Эта ошибка может возникнуть, если на устройстве ВООБЩЕ нет задней камеры, что маловероятно
            Toast.makeText(this, "Не удалось привязать заднюю камеру", Toast.LENGTH_SHORT).show();
        }
    }

    // УДАЛЕНО: Метод switchCamera() больше не нужен
    /*
    private void switchCamera() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        bindPreviewUseCase();
    }
    */

    private void setupZoomSliderState(CameraInfo cameraInfo, CameraControl cameraControl) {
        LiveData<ZoomState> zoomStateLiveData = cameraInfo.getZoomState();
        ZoomState zoomState = zoomStateLiveData.getValue();

        if (zoomState == null) {
            Log.w(TAG, "Не удалось получить ZoomState");
            zoomSlider.setEnabled(false);
            return;
        }

        float minZoom = 0f;
        float maxZoom = 1f;

        zoomSlider.setValueFrom(minZoom);
        zoomSlider.setValueTo(maxZoom);
        zoomSlider.setStepSize(0.01f);

        zoomSlider.setValue(zoomState.getLinearZoom());
        zoomSlider.setEnabled(true);
        Log.d(TAG, "Zoom настроен: MinLinear=" + minZoom + ", MaxLinear=" + maxZoom + ", CurrentLinear=" + zoomState.getLinearZoom());

    }

    private void setupZoomSliderListener() {
         zoomSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                if (camera != null && fromUser) {
                    ListenableFuture<Void> future = camera.getCameraControl().setLinearZoom(value);
                    future.addListener(() -> {
                        try {
                             future.get();
                        } catch (Exception e) {
                             Log.e(TAG, "Error setting linear zoom", e);
                        }
                    }, ContextCompat.getMainExecutor(MainActivity.this));
                }
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
