// --- Полный путь к файлу: /root/CameraPreview/app/src/main/java/com/example/camerapreview/LayerAdapter.java ---
package com.example.camerapreview;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.LayerViewHolder> {

    private static final String TAG = "LayerAdapter";
    private final String[] layerNames;
    private final boolean[] visibility;
    private final OnLayerVisibilityChangedListener listener;

    // Интерфейс для обратной связи с Activity
    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    // Конструктор
    public LayerAdapter(String[] layerNames, boolean[] visibility, OnLayerVisibilityChangedListener listener) {
        if (layerNames == null || visibility == null || layerNames.length != visibility.length) {
            throw new IllegalArgumentException("Layer names and visibility arrays must be non-null and have the same length.");
        }
        this.layerNames = layerNames;
        this.visibility = visibility;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Используем стандартный макет Android для простоты, но с CheckBox
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_layer, parent, false); // Используем свой макет!
        return new LayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LayerViewHolder holder, int position) {
        holder.bind(position); // Используем метод bind для настройки View
    }

    @Override
    public int getItemCount() {
        return layerNames.length;
    }

    // ViewHolder для элемента списка
    class LayerViewHolder extends RecyclerView.ViewHolder {
        TextView layerNameTextView;
        CheckBox layerCheckBox;

        LayerViewHolder(@NonNull View itemView) {
            super(itemView);
            // Находим View по ID из нашего макета list_item_layer.xml
            layerNameTextView = itemView.findViewById(R.id.layerNameTextView);
            layerCheckBox = itemView.findViewById(R.id.layerCheckBox);

            if (layerNameTextView == null || layerCheckBox == null) {
                 Log.e(TAG, "ViewHolder views not found in list_item_layer.xml!");
            }
        }

        // Метод для связывания данных с View
        void bind(final int position) {
             if (layerNameTextView != null) {
                layerNameTextView.setText(layerNames[position]);
             }
             if (layerCheckBox != null) {
                 // Сначала снимаем слушатель, чтобы избежать срабатывания при установке checked
                 layerCheckBox.setOnCheckedChangeListener(null);
                 layerCheckBox.setChecked(visibility[position]);
                 // Устанавливаем слушатель обратно
                 layerCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                     // Проверяем, действительно ли изменилось состояние
                     if (visibility[position] != isChecked) {
                         Log.d(TAG, "Layer " + position + " (" + layerNames[position] + ") visibility changed to: " + isChecked);
                         visibility[position] = isChecked;
                         if (listener != null) {
                             listener.onLayerVisibilityChanged(position, isChecked);
                         }
                     }
                 });
             }
        }
    }

    // Создадим простой макет для элемента списка: app/src/main/res/layout/list_item_layer.xml
    /*
    <?xml version="1.0" encoding="utf-8"?>
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="8dp"
        android:gravity="center_vertical">

        <TextView
            android:id="@+id/layerNameTextView"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textAppearance="?android:attr/textAppearanceListItem"
            android:text="Layer Name"/>

        <CheckBox
            android:id="@+id/layerCheckBox"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"/>
    </LinearLayout>
    */
}
