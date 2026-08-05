package com.example.abxoverflow.droppedapk;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflected wrapper around android.os.IVold (vold runs as root).
 */
final class VoldClient {
    private static final String TAG = "DropShell";

    private final Object mVold;

    VoldClient() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "vold");
        if (binder == null) {
            throw new IllegalStateException("vold service is null");
        }
        Class<?> stub = Class.forName("android.os.IVold$Stub");
        mVold = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Log.i(TAG, "VoldClient connected: " + mVold);
    }

    Object vold() {
        return mVold;
    }

    String listMethods() {
        StringBuilder sb = new StringBuilder();
        for (Method m : mVold.getClass().getMethods()) {
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

    String mountFstab(String blkDevice, String mountPoint, String zonedDevice) throws Exception {
        invoke("mountFstab", blkDevice, mountPoint, zonedDevice == null ? "" : zonedDevice);
        return "ok mountFstab " + blkDevice + " -> " + mountPoint;
    }

    String createStubVolume(String sourcePath, String mountPath, String fsType,
                            String fsUuid, String fsLabel, int flags) throws Exception {
        Object fd = invoke("createStubVolume", sourcePath, mountPath, fsType,
                fsUuid == null ? "" : fsUuid, fsLabel == null ? "" : fsLabel, flags);
        return "ok createStubVolume fd=" + fd;
    }

    String bindMount(String sourceDir, String targetDir) throws Exception {
        invoke("bindMount", sourceDir, targetDir);
        return "ok bindMount " + sourceDir + " -> " + targetDir;
    }

    String destroyStubVolume(String volId) throws Exception {
        invoke("destroyStubVolume", volId);
        return "ok destroyStubVolume " + volId;
    }

    private Object invoke(String name, Object... args) throws Exception {
        Method m = findMethod(name, args);
        try {
            return m.invoke(mVold, args);
        } catch (InvocationTargetException e) {
            Throwable t = e.getTargetException();
            if (t instanceof Exception) throw (Exception) t;
            throw e;
        }
    }

    private Method findMethod(String name, Object[] args) throws NoSuchMethodException {
        outer:
        for (Method m : mVold.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != args.length) continue;
            for (int i = 0; i < p.length; i++) {
                if (args[i] == null) continue;
                Class<?> expected = p[i];
                Class<?> actual = args[i].getClass();
                if (expected.isPrimitive()) {
                    if (expected == int.class && !(actual == Integer.class)) continue outer;
                    if (expected == boolean.class && !(actual == Boolean.class)) continue outer;
                    if (expected == long.class && !(actual == Long.class)) continue outer;
                } else if (!expected.isAssignableFrom(actual)) {
                    continue outer;
                }
            }
            return m;
        }
        throw new NoSuchMethodException(name + " (" + args.length + " args)");
    }
}
