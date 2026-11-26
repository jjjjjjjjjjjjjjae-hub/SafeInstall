package kz.safeinstall.app;

import android.content.ContentResolver;
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
import java.security.MessageDigest;
import java.util.Locale;

public class MainActivity extends android.app.Activity {
    private static final int PICK_APK = 42;
    private static final long MAX_APK_BYTES = 500L * 1024L * 1024L;

    private LinearLayout detailsCard;
    private LinearLayout permissionsList;
    private TextView emptyText;
    private TextView riskText;
    private TextView appNameText;
    private TextView metaText;
    private TextView signatureText;
    private File selectedApk;

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

        Button selectButton = findViewById(R.id.selectButton);
        Button installButton = findViewById(R.id.installButton);
        Button clearButton = findViewById(R.id.clearButton);

        selectButton.setOnClickListener(v -> chooseApk());
        installButton.setOnClickListener(v -> startSystemInstaller());
        clearButton.setOnClickListener(v -> clearSelection());
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

        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (info == null || info.applicationInfo == null) {
            throw new IllegalArgumentException("жарамды APK табылмады");
        }

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
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", selectedApk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !getPackageManager().canRequestPackageInstalls()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Жүйелік орнатқыш ашылмады: " + safeMessage(e), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void clearSelection() {
        selectedApk = null;
        detailsCard.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
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
