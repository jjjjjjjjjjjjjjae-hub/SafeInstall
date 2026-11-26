package kz.safeinstall.app;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends android.app.Activity {
    private static final int PICK_APK = 42;
    private static final int UNINSTALL_ONAY = 43;
    private static final long MAX_APK_BYTES = 500L * 1024L * 1024L;
    private static final String RELEASE_API =
            "https://api.github.com/repos/jjjjjjjjjjjjjjae-hub/SafeInstall/releases/latest";
    private static final String RELEASE_LATEST_APK_URL =
            "https://github.com/jjjjjjjjjjjjjjae-hub/SafeInstall/releases/latest/download/SafeInstall.apk";
    private static final String RELEASE_APK_NAME = "SafeInstall.apk";
    private static final String UPDATE_APK_FILE_NAME = "safeinstall-update.apk";
    private static final String UPDATE_APK_PART_FILE_NAME = "safeinstall-update.apk.part";
    private static final String ONAY_RELEASE_API =
            "https://api.github.com/repos/jjjjjjjjjjjjjjae-hub/onay-/releases/latest";
    private static final String ONAY_LATEST_APK_URL =
            "https://github.com/jjjjjjjjjjjjjjae-hub/onay-/releases/latest/download/ONAY-Demo.apk";
    private static final String ONAY_APK_NAME = "ONAY-Demo.apk";
    private static final String ONAY_PACKAGE = "kz.onay.demo";
    private static final String ONAY_UPDATE_APK_FILE_NAME = "onay-recovery.apk";
    private static final String ONAY_UPDATE_APK_PART_FILE_NAME = "onay-recovery.apk.part";

    private LinearLayout detailsCard;
    private LinearLayout permissionsList;
    private TextView emptyText;
    private TextView riskText;
    private TextView appNameText;
    private TextView metaText;
    private TextView signatureText;
    private TextView statusText;
    private File selectedApk;
    private File pendingOnayInstall;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean updateDialogShown;
    private boolean onayDialogShown;
    private boolean incomingApkHandled = false;
    private boolean waitingForOnayUninstall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        emptyText = findViewById(R.id.emptyText);
        detailsCard = findViewById(R.id.detailsCard);
        permissionsList = findViewById(R.id.permissionsList);
        riskText = findViewById(R.id.riskText);
        appNameText = findViewById(R.id.appNameText);
        metaText = findViewById(R.id.metaText);
        signatureText = findViewById(R.id.signatureText);
        statusText = findViewById(R.id.statusText);

        Button installButton = findViewById(R.id.installButton);
        Button clearButton = findViewById(R.id.clearButton);

        installButton.setOnClickListener(v -> startSystemInstaller());
        clearButton.setOnClickListener(v -> clearSelection());

        handleIncomingIntent(getIntent());
        checkForUpdate();
        if (!incomingApkHandled) checkOnayRecovery();
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
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return;

        Uri source = intent.getData();
        if (Intent.ACTION_SEND.equals(action)) {
            source = getSharedUri(intent);
        }
        if (source == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            source = intent.getClipData().getItemAt(0).getUri();
        }
        if (source == null) return;
        incomingApkHandled = true;

        try {
            selectedApk = copyToPrivateCache(source);
            inspectApk(selectedApk);
            Toast.makeText(this, "APK SafeInstall арқылы тексерілді", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            clearSelection();
            Toast.makeText(this, "APK оқу мүмкін болмады: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private Uri getSharedUri(Intent intent) {
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private void checkForUpdate() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "SafeInstall");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                JSONObject release = new JSONObject(readText(connection.getInputStream()));
                String tag = release.optString("tag_name", "");
                long remoteVersionCode = versionCodeFromTag(tag);
                if (remoteVersionCode <= currentVersionCode()) return;

                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) return;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null || !RELEASE_APK_NAME.equals(asset.optString("name"))) continue;
                    UpdateInfo update = new UpdateInfo(
                            tag,
                            remoteVersionCode,
                            RELEASE_LATEST_APK_URL,
                            asset.optString("digest", ""));
                    if (!update.downloadUrl.isEmpty()) {
                        mainHandler.post(() -> showUpdateDialog(update));
                    }
                    return;
                }
            } catch (Exception ignored) {
                // Update checks are optional; the normal APK inspection still works offline.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void showUpdateDialog(UpdateInfo update) {
        if (isFinishing() || updateDialogShown) return;
        updateDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle("SafeInstall жаңартуы бар")
                .setMessage("Жаңа нұсқа " + update.tag + " табылды. APK алдымен SafeInstall ішінде тексеріледі, үнсіз орнатылмайды.")
                .setPositiveButton("Жүктеп тексеру", (dialog, which) -> downloadUpdate(update))
                .setNegativeButton("Кейін", null)
                .setOnDismissListener(dialog -> updateDialogShown = false)
                .show();
    }

    private void checkOnayRecovery() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ONAY_RELEASE_API).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "SafeInstall");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                JSONObject release = new JSONObject(readText(connection.getInputStream()));
                String tag = release.optString("tag_name", "");
                long remoteVersion = versionCodeFromTag(tag);
                long installedVersion = installedVersionCode(ONAY_PACKAGE);
                if (installedVersion >= remoteVersion && installedVersion > 0) return;

                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) return;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null || !ONAY_APK_NAME.equals(asset.optString("name"))) continue;
                    OnayUpdateInfo update = new OnayUpdateInfo(
                            tag,
                            remoteVersion,
                            ONAY_LATEST_APK_URL,
                            asset.optString("digest", ""));
                    if (!update.downloadUrl.isEmpty()) {
                        mainHandler.post(() -> downloadOnay(update));
                    }
                    return;
                }
            } catch (Exception ignored) {
                // Recovery is optional while offline.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void showOnayRecoveryDialog(OnayUpdateInfo update, long installedVersion) {
        if (isFinishing() || onayDialogShown) return;
        onayDialogShown = true;
        String installedText = installedVersion > 0
                ? "Орнатылған Onay: v" + installedVersion + ".\n"
                : "Onay әлі орнатылмаған.\n";
        new AlertDialog.Builder(this)
                .setTitle("Onay-ды SafeInstall арқылы орнату")
                .setMessage(installedText + "Соңғы нұсқа " + update.tag
                        + " табылды. SafeInstall APK-ны тексереді. Қолтаңба ауысқан болса, ескі Onay-ды өшіруге Android растауы сұралады.")
                .setPositiveButton("Жүктеп тексеру", (dialog, which) -> downloadOnay(update))
                .setNegativeButton("Кейін", null)
                .setOnDismissListener(dialog -> onayDialogShown = false)
                .show();
    }

    private void downloadOnay(OnayUpdateInfo update) {
        Toast.makeText(this, "Onay APK-сы жүктеліп, тексеріліп жатыр…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File part = new File(getCacheDir(), ONAY_UPDATE_APK_PART_FILE_NAME);
            File target = new File(getCacheDir(), ONAY_UPDATE_APK_FILE_NAME);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(update.downloadUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                connection.setRequestProperty("User-Agent", "SafeInstall");
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IllegalStateException("Onay APK-сы жүктелмеді (HTTP " + responseCode + ")");
                }
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16 * 1024];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_APK_BYTES) {
                            throw new IllegalArgumentException("Onay APK-сы 500 MB-тан үлкен");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                verifyOnayDownload(part, update);
                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Ескі Onay файлын ауыстыру мүмкін болмады");
                }
                if (!part.renameTo(target)) {
                    throw new IllegalStateException("Onay APK-сын дайындау мүмкін болмады");
                }
                mainHandler.post(() -> {
                    try {
                        selectedApk = target;
                        inspectApk(target);
                        Toast.makeText(this,
                                "Onay тексерілді. Қолтаңба ауысса, SafeInstall ескі нұсқаны өшіруді ұсынады.",
                                Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        clearSelection();
                        Toast.makeText(this, "Onay көрсетілмеді: " + safeMessage(error), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                if (part.exists()) part.delete();
                if (target.exists()) target.delete();
                mainHandler.post(() -> Toast.makeText(this,
                        "Onay APK-сы тексерілмеді: " + safeMessage(error), Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void verifyOnayDownload(File apk, OnayUpdateInfo update) throws Exception {
        String expectedDigest = update.digest.toLowerCase(Locale.ROOT);
        if (expectedDigest.startsWith("sha256:")) {
            expectedDigest = expectedDigest.substring("sha256:".length());
        }
        if (!expectedDigest.isEmpty() && !expectedDigest.equals(sha256(apk).toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Onay APK-сының SHA-256 хэші сәйкес емес");
        }
        PackageInfo info = readApkInfo(apk);
        if (!ONAY_PACKAGE.equals(info.packageName)) {
            throw new IllegalStateException("Бұл APK Onay қолданбасына тиесілі емес");
        }
        long installed = installedVersionCode(ONAY_PACKAGE);
        if (installed > 0 && versionCode(info) <= installed) {
            throw new IllegalStateException("Onay APK-сы орнатылған нұсқадан жаңа емес");
        }
    }

    private void downloadUpdate(UpdateInfo update) {
        Toast.makeText(this, "SafeInstall жаңартуы жүктеліп жатыр…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File part = new File(getCacheDir(), UPDATE_APK_PART_FILE_NAME);
            File target = new File(getCacheDir(), UPDATE_APK_FILE_NAME);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(update.downloadUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                connection.setRequestProperty("User-Agent", "SafeInstall");
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IllegalStateException("Жаңарту файлы жүктелмеді (HTTP " + responseCode + ")");
                }

                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16 * 1024];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_APK_BYTES) {
                            throw new IllegalArgumentException("Жаңарту APK-сы 500 MB-тан үлкен");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Ескі жаңарту файлын ауыстыру мүмкін болмады");
                }
                if (!part.renameTo(target)) {
                    throw new IllegalStateException("Жаңарту файлын дайындау мүмкін болмады");
                }

                verifyUpdate(target, update);
                mainHandler.post(() -> {
                    try {
                        selectedApk = target;
                        inspectApk(target);
                        Toast.makeText(this, "Жаңарту тексерілді. Орнату / жаңарту батырмасын бас.", Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        clearSelection();
                        Toast.makeText(this, "Жаңарту көрсетілмеді: " + safeMessage(error), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                if (part.exists()) part.delete();
                if (target.exists()) target.delete();
                mainHandler.post(() -> Toast.makeText(this,
                        "SafeInstall жаңартуы тексерілмеді: " + safeMessage(error),
                        Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void verifyUpdate(File apk, UpdateInfo update) throws Exception {
        String expectedDigest = update.digest.toLowerCase(Locale.ROOT);
        if (expectedDigest.startsWith("sha256:")) {
            expectedDigest = expectedDigest.substring("sha256:".length());
        }
        if (!expectedDigest.isEmpty() && !expectedDigest.equals(sha256(apk).toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Жаңарту APK-сының SHA-256 хэші сәйкес емес");
        }

        PackageInfo info = readApkInfo(apk);
        if (!getPackageName().equals(info.packageName)) {
            throw new IllegalStateException("Жаңарту басқа package id-ге тиесілі");
        }
        if (versionCode(info) <= currentVersionCode()) {
            throw new IllegalStateException("Жаңарту нұсқасы орнатылған нұсқадан жаңа емес");
        }
        PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES);
        if (!signingDigest(info).equals(signingDigest(installed))) {
            throw new IllegalStateException("Жаңарту қолтаңбасы осы SafeInstall нұсқасына сәйкес емес");
        }
    }

    private PackageInfo readApkInfo(File apk) {
        int flags = PackageManager.GET_PERMISSIONS;
        flags |= Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (info == null || info.applicationInfo == null) {
            throw new IllegalArgumentException("жарамды APK табылмады");
        }
        return info;
    }

    private long currentVersionCode() {
        try {
            return versionCode(getPackageManager().getPackageInfo(getPackageName(), 0));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long versionCodeFromTag(String tag) {
        int end = tag.length() - 1;
        while (end >= 0 && Character.isDigit(tag.charAt(end))) end--;
        if (end == tag.length() - 1) return 0;
        try {
            return Long.parseLong(tag.substring(end + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String readText(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            result.append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private void chooseApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        startActivityForResult(intent, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UNINSTALL_ONAY) {
            if (resultCode == RESULT_OK) {
                waitForOnayRemoval();
            } else {
                waitingForOnayUninstall = false;
                pendingOnayInstall = null;
                Toast.makeText(this, "Ескі Onay өшірілмеді. Жаңа нұсқа орнатылмады.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        try {
            selectedApk = copyToPrivateCache(data.getData());
            inspectApk(selectedApk);
        } catch (Exception e) {
            clearSelection();
            Toast.makeText(this, "APK оқу мүмкін болмады: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private File copyToPrivateCache(Uri source) throws Exception {
        File dir = new File(getCacheDir(), "apk");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("уақытша бума жасалмады");
        }
        File target = new File(dir, "selected.apk");
        ContentResolver resolver = getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalStateException("файлға қолжетім жоқ");
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_APK_BYTES) {
                    throw new IllegalArgumentException("APK 500 MB-тан үлкен");
                }
                output.write(buffer, 0, count);
            }
        }
        return target;
    }

    private void inspectApk(File apk) throws Exception {
        PackageManager pm = getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            flags |= PackageManager.GET_SIGNATURES;
        }

        PackageInfo info = readApkInfo(apk);

        ApplicationInfo applicationInfo = info.applicationInfo;
        applicationInfo.sourceDir = apk.getAbsolutePath();
        applicationInfo.publicSourceDir = apk.getAbsolutePath();
        CharSequence label = pm.getApplicationLabel(applicationInfo);
        String appLabel = label == null ? "Белгісіз қолданба" : label.toString();

        StringBuilder meta = new StringBuilder();
        meta.append("Пакет: ").append(info.packageName).append('\n');
        meta.append("Нұсқа: ").append(info.versionName == null ? "—" : info.versionName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            meta.append(" (").append(info.getLongVersionCode()).append(")");
        }
        meta.append("\nӨлшемі: ").append(formatBytes(apk.length()));
        meta.append("\nSHA-256: ").append(sha256(apk));

        appNameText.setText(appLabel);
        metaText.setText(meta.toString());
        statusText.setText(installationStatus(info));
        signatureText.setText("Қолтаңба SHA-256: " + signingDigest(info));
        renderPermissions(info.requestedPermissions);

        Risk risk = assessRisk(info.requestedPermissions);
        riskText.setText(risk.title + "\n" + risk.explanation);
        riskText.setBackgroundColor(Color.parseColor(risk.color));

        emptyText.setVisibility(View.GONE);
        detailsCard.setVisibility(View.VISIBLE);
    }

    private void renderPermissions(String[] permissions) {
        permissionsList.removeAllViews();
        if (permissions == null || permissions.length == 0) {
            addPermissionRow("Рұқсат сұралмаған", false);
            return;
        }
        for (String permission : permissions) {
            addPermissionRow(permissionLabel(permission), isSensitive(permission));
        }
    }

    private void addPermissionRow(String text, boolean sensitive) {
        TextView row = new TextView(this);
        row.setText((sensitive ? "⚠  " : "•  ") + text);
        row.setTextColor(Color.parseColor(sensitive ? "#A12626" : "#40504A"));
        row.setTextSize(14);
        row.setPadding(0, 7, 0, 7);
        permissionsList.addView(row);
    }

    private String permissionLabel(String permission) {
        try {
            PermissionInfo info = getPackageManager().getPermissionInfo(permission, 0);
            CharSequence label = info.loadLabel(getPackageManager());
            if (label != null && !label.toString().equals(permission)) {
                return label + "\n" + permission;
            }
        } catch (Exception ignored) {
            // Some vendor/custom permissions do not have a public PermissionInfo.
        }
        return permission;
    }

    private boolean isSensitive(String permission) {
        String p = permission.toUpperCase(Locale.ROOT);
        return p.contains("CAMERA") || p.contains("RECORD_AUDIO") || p.contains("LOCATION")
                || p.contains("CONTACTS") || p.contains("CALL_LOG") || p.contains("PHONE")
                || p.contains("SMS") || p.contains("MICROPHONE") || p.contains("READ_MEDIA")
                || p.contains("EXTERNAL_STORAGE") || p.contains("MANAGE_EXTERNAL_STORAGE")
                || p.contains("SYSTEM_ALERT_WINDOW") || p.contains("ACCESSIBILITY")
                || p.contains("REQUEST_INSTALL_PACKAGES") || p.contains("QUERY_ALL_PACKAGES")
                || p.contains("BLUETOOTH_SCAN") || p.contains("BLUETOOTH_CONNECT")
                || p.contains("PACKAGE_USAGE_STATS") || p.contains("VPN");
    }

    private Risk assessRisk(String[] permissions) {
        int sensitive = 0;
        if (permissions != null) {
            for (String permission : permissions) {
                if (isSensitive(permission)) sensitive++;
            }
        }
        if (sensitive == 0) {
            return new Risk("ТӨМЕН ҚАУІП ДЕҢГЕЙІ", "Ерекше сезімтал рұқсат байқалмады.", "#0B7A53");
        }
        if (sensitive <= 2) {
            return new Risk("НАЗАР АУДАР", sensitive + " сезімтал рұқсат сұралды. Орнату себебін тексер.", "#A86100");
        }
        return new Risk("ЖОҒАРЫ НАЗАР", sensitive + " сезімтал рұқсат сұралды. Дереккөзін мұқият тексер.", "#A12626");
    }

    private void startSystemInstaller() {
        if (selectedApk == null || !selectedApk.exists()) {
            Toast.makeText(this, "Алдымен APK таңда", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PackageInfo selectedInfo = readApkInfo(selectedApk);
            if (ONAY_PACKAGE.equals(selectedInfo.packageName)
                    && installedVersionCode(ONAY_PACKAGE) > 0
                    && !sameSignerAsInstalled(selectedInfo, ONAY_PACKAGE)) {
                requestOnayUninstall();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "APK қолтаңбасын тексеру мүмкін болмады: " + safeMessage(e), Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "SafeInstall-ге APK орнатуға рұқсат бер", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", selectedApk);
            // ACTION_INSTALL_PACKAGE prevents SafeInstall's own APK intent filter from looping back here.
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Android жүйелік орнатқышы табылмады", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Жүйелік орнатқыш ашылмады: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void requestOnayUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("Onay қолтаңбасы ауысқан")
                .setMessage("Ескі Onay жаңа қолтаңбалы нұсқамен жаңармайды. SafeInstall ескі Onay-ды Android растауымен өшіреді, содан кейін жаңа нұсқаны орнатуға ұсынады.")
                .setPositiveButton("Ескіні өшіру", (dialog, which) -> {
                    pendingOnayInstall = selectedApk;
                    waitingForOnayUninstall = true;
                    Intent uninstall = new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + ONAY_PACKAGE));
                    uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                    startActivityForResult(uninstall, UNINSTALL_ONAY);
                })
                .setNegativeButton("Кейін", null)
                .show();
    }

    private void waitForOnayRemoval() {
        if (!waitingForOnayUninstall || pendingOnayInstall == null) return;
        if (!isPackageInstalled(ONAY_PACKAGE)) {
            File apk = pendingOnayInstall;
            waitingForOnayUninstall = false;
            pendingOnayInstall = null;
            selectedApk = apk;
            startSystemInstaller();
            return;
        }
        mainHandler.postDelayed(this::waitForOnayRemoval, 400L);
    }

    private boolean sameSignerAsInstalled(PackageInfo incoming, String packageName) throws Exception {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo installed = getPackageManager().getPackageInfo(packageName, flags);
        return signingDigest(incoming).equals(signingDigest(installed));
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private long installedVersionCode(String packageName) {
        try {
            return versionCode(getPackageManager().getPackageInfo(packageName, 0));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String installationStatus(PackageInfo incoming) {
        try {
            int flags = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            } else {
                flags |= PackageManager.GET_SIGNATURES;
            }
            PackageInfo installed = getPackageManager().getPackageInfo(incoming.packageName, flags);
            long installedVersion = versionCode(installed);
            long incomingVersion = versionCode(incoming);
            String versionMessage;
            if (incomingVersion > installedVersion) {
                versionMessage = "ЖАҢАРТУ: орнатылған " + installedVersion + " → жаңа " + incomingVersion;
            } else if (incomingVersion == installedVersion) {
                versionMessage = "Сол нұсқа орнатылған: " + installedVersion;
            } else {
                versionMessage = "ЕСКЕРТУ: бұл APK орнатылған нұсқадан ескі (" + incomingVersion
                        + " < " + installedVersion + ")";
            }

            String incomingSigner = signingDigest(incoming);
            String installedSigner = signingDigest(installed);
            if (!incomingSigner.equals(installedSigner)) {
                return versionMessage + "\nҚолтаңба сәйкес емес: Android жаңартуды қабылдамауы мүмкін.";
            }
            return versionMessage + "\nҚолтаңба сәйкес: Android жаңартуға рұқсат бере алады.";
        } catch (PackageManager.NameNotFoundException e) {
            return "ЖАҢА ОРНАТУ: бұл пакет бұрын орнатылмаған сияқты.";
        } catch (SecurityException e) {
            return "Пакет күйін Android жасырып тұр. Жаңарту/орнатуды жүйелік орнатқыш шешеді.";
        }
    }

    private long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private void clearSelection() {
        selectedApk = null;
        detailsCard.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        statusText.setText("");
        File dir = new File(getCacheDir(), "apk");
        File file = new File(dir, "selected.apk");
        if (file.exists()) file.delete();
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private String signingDigest(PackageInfo info) {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo signingInfo = info.signingInfo;
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            } else {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "қолтаңба табылмады";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(signatures[0].toByteArray()));
        } catch (Exception e) {
            return "оқылмады";
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X:", value));
        if (result.length() > 0) result.setLength(result.length() - 1);
        return result.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? "белгісіз қате" : e.getMessage();
    }

    private static class UpdateInfo {
        final String tag;
        final long versionCode;
        final String downloadUrl;
        final String digest;

        UpdateInfo(String tag, long versionCode, String downloadUrl, String digest) {
            this.tag = tag;
            this.versionCode = versionCode;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
        }
    }

    private static class OnayUpdateInfo {
        final String tag;
        final long versionCode;
        final String downloadUrl;
        final String digest;

        OnayUpdateInfo(String tag, long versionCode, String downloadUrl, String digest) {
            this.tag = tag;
            this.versionCode = versionCode;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
        }
    }

    private static class Risk {
        final String title;
        final String explanation;
        final String color;

        Risk(String title, String explanation, String color) {
            this.title = title;
            this.explanation = explanation;
            this.color = color;
        }
    }
}

