// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.microsoft.sampleandroid;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.ux.ArFragment;
import com.microsoft.azure.spatialanchors.AnchorLocateCriteria;
import com.microsoft.azure.spatialanchors.AnchorLocatedEvent;
import com.microsoft.azure.spatialanchors.CloudSpatialAnchor;
import com.microsoft.azure.spatialanchors.CloudSpatialException;
import com.microsoft.azure.spatialanchors.LocateAnchorsCompletedEvent;

import java.text.DecimalFormat;
import java.util.concurrent.ConcurrentHashMap;

public class SharedActivity extends AppCompatActivity {

    enum DemoStep {
        DemoStepChoosing, // Choosing to create or locate
        DemoStepCreating, // Creating an anchor
        DemoStepSaving,   // Saving an anchor to the cloud
        DemoStepEnteringAnchorNumber, // Picking an anchor to find
        DemoStepLocating  // Looking for an anchor
    }

    private String anchorId = "";
    private final ConcurrentHashMap<String, AnchorVisual> anchorVisuals = new ConcurrentHashMap<>();
    private AzureSpatialAnchorsManager cloudAnchorManager;
    private DemoStep currentStep = DemoStep.DemoStepChoosing;
    private static final DecimalFormat decimalFormat = new DecimalFormat("00");
    private String feedbackText;

    // Materials
    private static final int FAILED_COLOR = android.graphics.Color.RED;
    private static final int SAVED_COLOR = android.graphics.Color.GREEN;
    private static final int READY_COLOR = android.graphics.Color.YELLOW;
    private static final int FOUND_COLOR = android.graphics.Color.YELLOW;

    // UI Elements
    private EditText anchorNumInput;
    private ArFragment arFragment;
    private Button createButton;
    private TextView editTextInfo;
    private Button locateButton;
    private ArSceneView sceneView;
    private TextView textView;

    public void createButtonClicked(View source) {
        textView.setText(R.string.escanea);
        destroySession();

        // creamos el manager (contiene las credenciales de acceso)
        cloudAnchorManager = new AzureSpatialAnchorsManager(sceneView.getSession());

        // Session creada?
        cloudAnchorManager.addSessionUpdatedListener(args -> {
            if (currentStep == DemoStep.DemoStepCreating) {
                //Obtenemos progreso de referencia
                float progress = args.getStatus().getRecommendedForCreateProgress();
                if (progress >= 1.0) {
                    // Obtenemos anchor visual (trae el anchor creado con el hit, y trae también la parte visual)
                    AnchorVisual visual = anchorVisuals.get("");
                    if (visual != null) {
                        //Si tenemos el anchor (lo hemos situado) y ya hemos alcanzado el progreso de escaneo, almacenamos los datos
                        transitionToSaving(visual);
                    } else {
                        // Si no tenemos anchor visual es el caso en el que pulsamos 'crear', pero no hemos pulsado la pantalla
                        feedbackText = "Toca en una parte habilitada para colocar el anchor";
                    }
                } else {
                    // No hemos llegado al progreso mínimo para iniciar la subida
                    feedbackText = "El progreso es" + decimalFormat.format(progress * 100) + "%";
                }
            }
        });

        // Se inicia y cambiamos el estado a "creando"
        cloudAnchorManager.start();
        currentStep = DemoStep.DemoStepCreating;
        // También actualizamos los TV de la interfaz
        enableCorrectUIControls();
    }

    public void locateButtonClicked(View source) {
        if (currentStep == DemoStep.DemoStepChoosing) {
            // Actualizamos estado y TVs
            currentStep = DemoStep.DemoStepEnteringAnchorNumber;
            textView.setText(R.string.introduce);
            enableCorrectUIControls();
        } else {
            String inputVal = anchorNumInput.getText().toString();
            if (!inputVal.isEmpty()) {
                // Tenemos el id introducido y se ha pulsado 'locate'
                anchorLookedUp(inputVal);
                //Actualizamos estado y TVs
                currentStep = DemoStep.DemoStepLocating;
                enableCorrectUIControls();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shared);

        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ar_fragment);
        arFragment.setOnTapArPlaneListener(this::onTapArPlaneListener);

        sceneView = arFragment.getArSceneView();

        textView = findViewById(R.id.textView);
        textView.setVisibility(View.VISIBLE);
        locateButton = findViewById(R.id.locateButton);
        createButton = findViewById(R.id.createButton);
        anchorNumInput = findViewById(R.id.anchorNumText);
        editTextInfo = findViewById(R.id.editTextInfo);
        enableCorrectUIControls();

        Scene scene = sceneView.getScene();
        scene.addOnUpdateListener(frameTime -> {
            if (cloudAnchorManager != null) {
                // Pass frames to Spatial Anchors for processing.
                cloudAnchorManager.update(sceneView.getArFrame());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroySession();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ArFragment of Sceneform automatically requests the camera permission before creating the AR session,
        // so we don't need to request the camera permission explicitly.
        // This will cause onResume to be called again after the user responds to the permission request.
        if (!SceneformHelper.hasCameraPermission(this)) {
            return;
        }

        if (sceneView != null && sceneView.getSession() == null) {
            if (!SceneformHelper.trySetupSessionForSceneView(this, sceneView)) {

                finish();
                return;
            }
        }

        updateStatic();
    }

    private void anchorLookedUp(String anchorId) {

        // Se ha introducido un anchor id en el editText
        this.anchorId = anchorId;
        destroySession();

        cloudAnchorManager = new AzureSpatialAnchorsManager(sceneView.getSession());
        cloudAnchorManager.addAnchorLocatedListener((AnchorLocatedEvent event) ->
                runOnUiThread(() -> {
                    // El manager nos devuelve un anchor con un estado
                    CloudSpatialAnchor anchor = event.getAnchor();
                    switch (event.getStatus()) {
                        case AlreadyTracked:
                        case Located:
                            // El estado corresponde a que se ha localizado el anchor con ese id :)
                            // Se obtiene el anchor, y se pinta en su posición (realmente anchor es la posicion)
                            // Deberíamos decir, se obtiene el anchor y se pinta un modelo 3D en ese anchor
                            AnchorVisual foundVisual = new AnchorVisual(arFragment, anchor.getLocalAnchor());
                            foundVisual.setCloudAnchor(anchor);
                            foundVisual.getAnchorNode().setParent(arFragment.getArSceneView().getScene());
                            String cloudAnchorIdentifier = foundVisual.getCloudAnchor().getIdentifier();
                            foundVisual.setColor(this, FOUND_COLOR);
                            foundVisual.render(arFragment);
                            anchorVisuals.put(cloudAnchorIdentifier, foundVisual);
                            break;
                        case NotLocatedAnchorDoesNotExist:
                            break;
                    }
                }));

        cloudAnchorManager.addLocateAnchorsCompletedListener((LocateAnchorsCompletedEvent event) -> {
            // Listener que indica que hemos finalizado la localización
            // Actualizamos el estado y los TVs
            currentStep = DemoStep.DemoStepChoosing;
            runOnUiThread(() -> {
                textView.setText(R.string.encontrado);
                enableCorrectUIControls();
            });
        });

        cloudAnchorManager.start();

        // Establecemos el criterio de localización, definimos que sea por ID
        AnchorLocateCriteria criteria = new AnchorLocateCriteria();
        criteria.setIdentifiers(new String[]{anchorId});
        // Mantenemos el objecto situado, aunque nos movamos, porque tenemos un watcher activo
        cloudAnchorManager.startLocating(criteria);

    }

    // Callback llamado cuando se finaliza el envío del anchor id al servicio web
    private void anchorPosted(String anchorId) {
        // Mostramos el número por el cual podemos buscar el id
        // (es más simple buscar por el número asociado que si lo tuviésemos que hacer por el id)
        textView.setText(getString(R.string.anchorid, anchorId));

        // Hacemos un 'reset'.
        // Reseteamos estado, sesion, limpiamos anchors en pantalla y TVs
        currentStep = DemoStep.DemoStepChoosing;
        cloudAnchorManager.stop();
        cloudAnchorManager = null;
        clearVisuals();
        enableCorrectUIControls();
    }

    private void createAnchor(HitResult hitResult) {
        // hitResult.createAnchor -> Crea un nuevo anchor en la ubicación del tap.
        // Un anchor es un triple de vector. Eje X, Y y Z en una posición (hit)
        // Aquí se crea la parte visual en una posicion (recibe el anchor)
        AnchorVisual visual = new AnchorVisual(arFragment, hitResult.createAnchor());
        visual.setColor(this, READY_COLOR);
        // Se muestra
        visual.render(arFragment);
        // anchorsVisual es un hashmap (mapa clave valor) seguro para subprocesos
        // se establece una key (vacia en este caso) y el objeto AnchorVisual
        anchorVisuals.put("", visual);
    }

    private void clearVisuals() {
        for (AnchorVisual visual : anchorVisuals.values()) {
            visual.destroy();
        }
        //Limpiamos los anchors que se ven en pantalla
        anchorVisuals.clear();
    }

    private void createAnchorExceptionCompletion(String message) {
        textView.setText(message);
        currentStep = DemoStep.DemoStepChoosing;
        cloudAnchorManager.stop();
        cloudAnchorManager = null;
        enableCorrectUIControls();
    }

    private void destroySession() {
        if (cloudAnchorManager != null) {
            cloudAnchorManager.stop();
            cloudAnchorManager = null;
        }

        stopWatcher();

        clearVisuals();
    }

    private void enableCorrectUIControls() {
        switch (currentStep) {
            case DemoStepChoosing:
                textView.setVisibility(View.VISIBLE);
                locateButton.setVisibility(View.VISIBLE);
                createButton.setVisibility(View.VISIBLE);
                anchorNumInput.setVisibility(View.GONE);
                editTextInfo.setVisibility(View.GONE);
                break;
            case DemoStepCreating:
            case DemoStepLocating:
            case DemoStepSaving:
                textView.setVisibility(View.VISIBLE);
                locateButton.setVisibility(View.GONE);
                createButton.setVisibility(View.GONE);
                anchorNumInput.setVisibility(View.GONE);
                editTextInfo.setVisibility(View.GONE);
                break;
            case DemoStepEnteringAnchorNumber:
                textView.setVisibility(View.VISIBLE);
                locateButton.setVisibility(View.VISIBLE);
                createButton.setVisibility(View.GONE);
                anchorNumInput.setVisibility(View.VISIBLE);
                editTextInfo.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void onTapArPlaneListener(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
        if (currentStep == DemoStep.DemoStepCreating) {
            AnchorVisual visual = anchorVisuals.get("");
            if (visual == null) {
                createAnchor(hitResult);
            }
        }
    }

    private void stopWatcher() {
        if (cloudAnchorManager != null) {
            cloudAnchorManager.stopLocating();
        }
    }

    private void transitionToSaving(AnchorVisual visual) {

        // Cambiamos estado y actualizamos TVs
        currentStep = DemoStep.DemoStepSaving;
        enableCorrectUIControls();

        //Creamos un cloudAnchor y se lo asignamos al objeto visual
        CloudSpatialAnchor cloudAnchor = new CloudSpatialAnchor();
        visual.setCloudAnchor(cloudAnchor);
        //Además el cloud anchor tomará los valores locales que se almacenaron en el visual,
        // a la hora de crearlo.
        cloudAnchor.setLocalAnchor(visual.getLocalAnchor());

        //Creamos el cloudanchor utilizando el manager
        cloudAnchorManager.createAnchorAsync(cloudAnchor)
                .thenAccept(anchor -> {
                    // T0do fue bien. Obtenemos el ID y cambiamos el color.
                    String anchorId = anchor.getIdentifier();
                    visual.setColor(this, SAVED_COLOR);

                    Intent sendIntent = new Intent();
                    sendIntent.setAction(Intent.ACTION_SEND);
                    sendIntent.putExtra(Intent.EXTRA_TEXT, "Anchor id creado: " + anchorId);
                    sendIntent.setType("text/plain");

                    Intent shareIntent = Intent.createChooser(sendIntent, null);
                    startActivity(shareIntent);

                    //Además borramos el hashmap local que habíamos creado, y creamos otro con
                    // el ID del anchor que nos llega del API
                    anchorVisuals.put(anchorId, visual);
                    anchorVisuals.remove("");

                    anchorPosted(anchorId);

                }).exceptionally(thrown -> {

            thrown.printStackTrace();
            String exceptionMessage = thrown.toString();
            Throwable t = thrown.getCause();
            if (t instanceof CloudSpatialException) {
                exceptionMessage = (((CloudSpatialException) t).getErrorCode().toString());
            }
            createAnchorExceptionCompletion(exceptionMessage);
            visual.setColor(this, FAILED_COLOR);
            return null;
        });
    }

    private void updateStatic() {
        new android.os.Handler().postDelayed(() -> {
            switch (currentStep) {
                case DemoStepChoosing:
                case DemoStepEnteringAnchorNumber:
                    break;
                case DemoStepCreating:
                    textView.setText(feedbackText);
                    break;
                case DemoStepLocating:
                    textView.setText(getString(R.string.buscando, anchorId));
                    break;
                case DemoStepSaving:
                    textView.setText(R.string.guardando);
                    break;
            }

            updateStatic();
        }, 500);
    }
}
