package com.fooqot.test;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private TextView resultText;
    private Button scanAgainButton;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private final AtomicBoolean scanLocked = new AtomicBoolean(false);
    private ImageAnalysis imageAnalysis;

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    resultText.setText("Camera permission is required to scan QR codes.");
                    scanAgainButton.setVisibility(View.GONE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        barcodeScanner = BarcodeScanning.getClient();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(16));
        root.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("FooqotTest QR Scanner");
        title.setTextSize(22);
        title.setTextColor(0xFF171717);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(14));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        previewParams.bottomMargin = dp(14);
        root.addView(previewView, previewParams);

        resultText = new TextView(this);
        resultText.setText("Point your camera at a QR code.");
        resultText.setTextSize(16);
        resultText.setTextColor(0xFF222222);
        resultText.setTextIsSelectable(true);
        resultText.setPadding(dp(12), dp(12), dp(12), dp(12));
        resultText.setBackgroundColor(0xFFFFFFFF);
        root.addView(resultText, new LinearLayout.LayoutParams(-1, -2));

        scanAgainButton = new Button(this);
        scanAgainButton.setText("Scan Again");
        scanAgainButton.setVisibility(View.GONE);
        root.addView(scanAgainButton, new LinearLayout.LayoutParams(-1, -2));

        scanAgainButton.setOnClickListener(v -> {
            scanLocked.set(false);
            resultText.setText("Point your camera at a QR code.");
            scanAgainButton.setVisibility(View.GONE);
            if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
            startCamera();
        });

        setContentView(root);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (scanLocked.get()) {
                        imageProxy.close();
                        return;
                    }
                    @androidx.camera.core.ExperimentalGetImage
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null) {
                        imageProxy.close();
                        return;
                    }
                    InputImage inputImage = InputImage.fromMediaImage(
                            mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                    barcodeScanner.process(inputImage)
                            .addOnSuccessListener(barcodes -> {
                                if (!barcodes.isEmpty() && scanLocked.compareAndSet(false, true)) {
                                    String value = barcodes.get(0).getRawValue();
                                    runOnUiThread(() -> {
                                        resultText.setText(value == null ? "QR code detected, but it has no readable text." : value);
                                        scanAgainButton.setVisibility(View.VISIBLE);
                                    });
                                }
                            })
                            .addOnFailureListener(error ->
                                    runOnUiThread(() -> resultText.setText("Could not read QR code. Try again.")))
                            .addOnCompleteListener(task -> imageProxy.close());
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageAnalysis);
            } catch (Exception e) {
                Toast.makeText(this, "Could not start camera: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (barcodeScanner != null) barcodeScanner.close();
    }
}
