package com.mrzero.filemanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hosts the tab bar, swaps content, and owns the storage-permission flow. */
public class MainActivity extends AppCompatActivity {

    public static final int TAB_BROWSE = 0, TAB_RECENTS = 1, TAB_SEARCH = 2,
            TAB_STORAGE = 3, TAB_SETTINGS = 4;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    public TabBar tabBar;
    public FrameLayout content;
    public BrowsePage browsePage;
    public RecentsPage recentsPage;
    public SearchPage searchPage;
    public StoragePage storagePage;
    public SettingsPage settingsPage;
    private int currentTab = TAB_BROWSE;
    private View detailOverlay;
    private FrameLayout root;
    private FrameLayout busyView;

    public ExecutorService io() { return io; }

    /** runOnUiThread from a static context, for sheet callbacks. */
    public static void runUi(Activity act, Runnable r) {
        act.runOnUiThread(r);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Apple.edgeToEdge(getWindow());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        this.root = root;

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        tabBar = new TabBar(this);
        tabBar.setOnTab(i -> {
            if (hasStoragePermission()) {
                showTab(i);
            } else {
                tabBar.select(currentTab, false);
                requestStorageAccess();
            }
        });
        FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                tabBar.contentInset());
        tabLp.gravity = android.view.Gravity.BOTTOM;
        root.addView(tabBar, tabLp);

        buildPages();
        showTab(TAB_BROWSE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (browsePage != null && browsePage.onBack()) return;
                if (detailOverlay != null) {
                    closeDetail();
                    return;
                }
                if (currentTab != TAB_BROWSE) {
                    tabBar.select(TAB_BROWSE, true);
                    return;
                }
                if (browsePage != null && !browsePage.isAtStart()) {
                    browsePage.goUp();
                    return;
                }
                finish();
            }
        });

        handleIncomingIntent(getIntent());
    }

    private void buildPages() {
        Root.attach(this);
        browsePage = new BrowsePage(this);
        recentsPage = new RecentsPage(this);
        searchPage = new SearchPage(this);
        storagePage = new StoragePage(this);
        settingsPage = new SettingsPage(this);
    }

    public void showTab(int index) {
        currentTab = index;
        content.removeAllViews();
        View page;
        switch (index) {
            case TAB_RECENTS: page = recentsPage; break;
            case TAB_SEARCH: page = searchPage; break;
            case TAB_STORAGE: page = storagePage; break;
            case TAB_SETTINGS: page = settingsPage; break;
            case TAB_BROWSE:
            default: page = browsePage; break;
        }
        content.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tabBar.select(index, false);
        if (index == TAB_BROWSE && browsePage != null) browsePage.onShown();
        if (index == TAB_RECENTS && recentsPage != null) recentsPage.refresh();
        if (index == TAB_SEARCH && searchPage != null) searchPage.onShown();
        if (index == TAB_STORAGE && storagePage != null) storagePage.onShown();
        if (index == TAB_SETTINGS && settingsPage != null) settingsPage.refresh();
    }

    public int currentTab() { return currentTab; }

    /** Pushes a screen over the tabs, iOS navigation style. */
    public void pushDetail(View pane) {
        if (detailOverlay != null) return;
        detailOverlay = pane;
        FrameLayout decor = (FrameLayout) getWindow().getDecorView();
        decor.addView(pane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (pane instanceof DetailPane) ((DetailPane) pane).animatePushIn();
    }

    public void closeDetail() {
        if (detailOverlay == null) return;
        final View pane = detailOverlay;
        detailOverlay = null;
        if (pane instanceof DetailPane) {
            ((DetailPane) pane).animatePopOut(() -> {
                if (pane.getParent() instanceof ViewGroup) {
                    ((ViewGroup) pane.getParent()).removeView(pane);
                }
            });
        } else if (pane.getParent() instanceof ViewGroup) {
            ((ViewGroup) pane.getParent()).removeView(pane);
        }
        if (browsePage != null) browsePage.onShown();
    }

    public boolean hasDetail() { return detailOverlay != null; }

    // ------------------------------------------------------------------
    // Permission: internal/shared storage, read and write
    // ------------------------------------------------------------------

    /** Can we read and list the shared volume? */
    public boolean hasStoragePermission() {
        return canReadStorage();
    }

    public boolean canReadStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            // All files access implies read; without it only granted media is
            // readable, which is not enough to browse folders.
            if (Environment.isExternalStorageManager()) return true;
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    || anyMediaPermissionGranted();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Can we create, rename and delete files? Needs write, not just read. */
    public boolean canWriteStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean anyMediaPermissionGranted() {
        if (Build.VERSION.SDK_INT < 33) return false;
        String[] media = {
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
        };
        for (String p : media) {
            if (checkSelfPermission(p)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    /** Human-readable reason the app still cannot write, or null when it can. */
    public String writeBlockedReason() {
        if (canWriteStorage()) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            return "Grant \"" + getString(R.string.app_name)
                    + "\" All files access to create, rename or delete files.";
        }
        return "Storage write permission was not granted.";
    }

    public void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                openAllFilesAccessSettings();
                return;
            }
            showTab(currentTab);
        } else if (Build.VERSION.SDK_INT >= 23) {
            // One request carries both halves: on API 24-29 write implies read.
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
        } else {
            showTab(currentTab);
        }
    }

    private void openAllFilesAccessSettings() {
        Sheets.alert(this, getString(R.string.permission_title),
                getString(R.string.permission_body), getString(R.string.grant), null, null,
                v -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable t2) {
                            toast("Enable \"All files access\" in Settings");
                        }
                    }
                }, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The All files access screen is external, so re-check on the way back.
        if (!hasStoragePermission() && currentTab != TAB_SETTINGS) {
            if (browsePage != null) browsePage.onShown();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (hasStoragePermission()) {
                showTab(currentTab);
                if (!canWriteStorage()) toast(writeBlockedReason());
            } else {
                toast("Storage permission is required to browse files");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action)) {
            Uri data = intent.getData();
            if (data == null && intent.getClipData() != null) {
                data = intent.getClipData().getItemAt(0).getUri();
            }
            if (data == null) return;
            tabBar.select(TAB_BROWSE, false);
            if (browsePage != null) browsePage.revealIntentUri(data);
        }
    }

    public void toast(String msg) {
        Sheets.toast(this, msg);
    }

    /**
     * Blocks input and shows a spinner while a long operation runs. Compression
     * and extraction can take many seconds, and without this the rows stay
     * tappable underneath, which invites a second run on the same files.
     */
    public void busy(boolean on) {
        if (on) {
            if (busyView != null) return;
            FrameLayout box = new FrameLayout(this);
            box.setBackgroundColor(0x99000000);
            box.setClickable(true);
            box.setFocusable(true);
            ProgressBar spin = new ProgressBar(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Apple.dp(this, 44), Apple.dp(this, 44), android.view.Gravity.CENTER);
            box.addView(spin, lp);
            root.addView(box, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            busyView = box;
        } else if (busyView != null) {
            root.removeView(busyView);
            busyView = null;
        }
    }

    public Activity activity() { return this; }
}
