package com.example.abxoverflow.droppedapk;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Minimal IBackupSessionCallback binder for installd startBackupSession. */
final class BackupSessionCallback extends Binder {
    static final String DESCRIPTOR = "android.os.IBackupSessionCallback";

    private final StringBuilder mLog = new StringBuilder();

    String log() {
        return mLog.toString().trim();
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        mLog.append("tx=").append(code).append('\n');
        return true;
    }

    static Object asInterface(Class<?> iface, BackupSessionCallback binder) {
        return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if ("asBinder".equals(method.getName())) {
                        return binder;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }
}
