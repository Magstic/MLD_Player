package com.magstic.mldplayer;

import android.Manifest;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.color.DynamicColors;

public final class MainActivity extends AppCompatActivity implements PlayerController.Host {
    private ActivityResultLauncher<Uri> openFolderLauncher;
    private ActivityResultLauncher<String[]> openSf2Launcher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private LinearLayout bottomNavigationView;
    private MaterialButton playerNavButton;
    private MaterialButton importNavButton;
    private MaterialButton settingsNavButton;
    private PlayerController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        registerLaunchers();
        setContentView(R.layout.activity_main);

        controller = PlaybackControllerStore.get(getApplicationContext());
        controller.attachHost(this);
        bottomNavigationView = (LinearLayout) findViewById(R.id.bottom_navigation);
        playerNavButton = (MaterialButton) findViewById(R.id.navigation_player);
        importNavButton = (MaterialButton) findViewById(R.id.navigation_import);
        settingsNavButton = (MaterialButton) findViewById(R.id.navigation_settings);
        configureNavigation();
        configureEdgeToEdge();

        controller.initialize();
        requestNotificationPermissionIfNeeded();
        if (savedInstanceState == null) {
            showScreen(R.id.navigation_player);
        } else {
            updateNavigationSelection(resolveVisibleScreenId());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.onHostResume();
    }

    @Override
    protected void onDestroy() {
        controller.detachHost(this);
        super.onDestroy();
    }

    @Override
    public Context getHostContext() {
        return this;
    }

    @Override
    public void requestOpenFolderPicker() {
        openFolderLauncher.launch(null);
    }

    @Override
    public void requestOpenSf2Picker() {
        openSf2Launcher.launch(new String[] { "*/*" });
    }

    @Override
    public void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    public PlayerController getController() {
        return controller;
    }

    public void navigateToImport() {
        showScreen(R.id.navigation_import);
    }

    public void navigateToPlayer() {
        showScreen(R.id.navigation_player);
    }

    public void navigateToSettings() {
        showScreen(R.id.navigation_settings);
    }

    private void registerLaunchers() {
        openFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                new androidx.activity.result.ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (controller != null) {
                            controller.onFolderPicked(uri);
                            navigateToPlayer();
                        }
                    }
                });
        openSf2Launcher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                new androidx.activity.result.ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (controller != null) {
                            controller.onSf2Picked(uri);
                        }
                    }
                });
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                });
    }

    private void showScreen(@IdRes int menuItemId) {
        String tag;
        Fragment fragment = null;

        if (menuItemId == R.id.navigation_import) {
            tag = "import";
        } else if (menuItemId == R.id.navigation_settings) {
            tag = "settings";
        } else {
            tag = "player";
        }

        fragment = getSupportFragmentManager().findFragmentByTag(tag);
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        updateNavigationSelection(menuItemId);

        if (current != null && tag.equals(current.getTag())) {
            return;
        }
        if (fragment == null) {
            if ("import".equals(tag)) {
                fragment = new ImportFragment();
            } else if ("settings".equals(tag)) {
                fragment = new SettingsFragment();
            } else {
                fragment = new PlayerFragment();
            }
        }

        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    private void configureNavigation() {
        playerNavButton.setOnClickListener(v -> showScreen(R.id.navigation_player));
        importNavButton.setOnClickListener(v -> showScreen(R.id.navigation_import));
        settingsNavButton.setOnClickListener(v -> showScreen(R.id.navigation_settings));
    }

    private int resolveVisibleScreenId() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

        if (current instanceof ImportFragment) {
            return R.id.navigation_import;
        }
        if (current instanceof SettingsFragment) {
            return R.id.navigation_settings;
        }
        return R.id.navigation_player;
    }

    private void updateNavigationSelection(@IdRes int selectedId) {
        applyNavigationButtonState(playerNavButton, selectedId == R.id.navigation_player);
        applyNavigationButtonState(importNavButton, selectedId == R.id.navigation_import);
        applyNavigationButtonState(settingsNavButton, selectedId == R.id.navigation_settings);
    }

    private void applyNavigationButtonState(MaterialButton button, boolean selected) {
        int backgroundColor = MaterialColors.getColor(
                button,
                selected ? com.google.android.material.R.attr.colorPrimaryContainer : com.google.android.material.R.attr.colorSurfaceVariant,
                0);
        int iconColor = MaterialColors.getColor(
                button,
                selected ? com.google.android.material.R.attr.colorOnPrimaryContainer : com.google.android.material.R.attr.colorOnSurfaceVariant,
                0);

        button.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        button.setIconTint(ColorStateList.valueOf(iconColor));
        button.setSelected(selected);
    }

    private void configureEdgeToEdge() {
        View root = findViewById(R.id.activity_root);
        View fragmentContainer = findViewById(R.id.fragment_container);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (controller != null) {
            controller.setAppearanceLightStatusBars(true);
            controller.setAppearanceLightNavigationBars(true);
        }
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            fragmentContainer.setPadding(0, 0, 0, 0);
            bottomNavigationView.setPadding(
                    bottomNavigationView.getPaddingLeft(),
                    bottomNavigationView.getPaddingTop(),
                    bottomNavigationView.getPaddingRight(),
                    systemBars.bottom + dp(6));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
}
