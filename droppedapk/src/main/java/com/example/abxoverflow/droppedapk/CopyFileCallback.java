package com.example.abxoverflow.droppedapk;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Binder side of IFileOperationCallbackNative for installd copyFile callbacks.
 */
final class CopyFileCallback extends Binder {
    static final String DESCRIPTOR = "android.os.IFileOperationCallbackNative";
    private static final int TRANSACTION_onFileCopyEvent = IBinder.FIRST_CALL_TRANSACTION;

    private final StringBuilder mLog = new StringBuilder();

    CopyFileCallback() {
    }

    @Override
    public String getInterfaceDescriptor() {
        return DESCRIPTOR;
    }

    String log() {
        return mLog.toString().trim();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code == TRANSACTION_onFileCopyEvent) {
            data.enforceInterface(DESCRIPTOR);
            int event = data.readInt();
            String content = data.readString();
            mLog.append("event=").append(event);
            if (content != null && !content.isEmpty()) {
                mLog.append(" content=").append(content);
            }
            mLog.append('\n');
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    /** Proxy implementing IFileOperationCallbackNative for IInstalld.copyFile(). */
    static Object asInterface(Class<?> iface, CopyFileCallback binder) throws Exception {
        return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("asBinder".equals(name)) {
                        return binder;
                    }
                    if ("onFileCopyEvent".equals(name)) {
                        binder.onFileCopyEvent((Integer) args[0], (String) args[1]);
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private void onFileCopyEvent(int event, String content) {
        mLog.append("event=").append(event);
        if (content != null && !content.isEmpty()) {
            mLog.append(" content=").append(content);
        }
        mLog.append('\n');
    }
}
