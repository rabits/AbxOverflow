package com.example.abxoverflow.droppedapk;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Interactive TCP shell on 127.0.0.1 — forward with:
 *   adb forward tcp:31337 tcp:31337
 *
 * Runs inside system_server (uid 1000). Can reach installd/PMS/vold and
 * read/write much of /data via Java I/O without spawning sh.
 */
public final class LocalShellServer extends Thread {
    public static final int PORT = 31337;
    private static final String TAG = "DropShell";

    private static LocalShellServer sInstance;

    private final Context mContext;
    private volatile InstalldClient mInstalld;
    private volatile VoldClient mVold;

    private LocalShellServer(Context context) {
        super("DropShell");
        mContext = context.getApplicationContext();
        setDaemon(true);
    }

    public static synchronized void ensureStarted(Context context) {
        if (sInstance != null && sInstance.isAlive()) {
            return;
        }
        sInstance = new LocalShellServer(context);
        sInstance.start();
    }

    public static int port() {
        return PORT;
    }

    public static boolean isRunning() {
        return sInstance != null && sInstance.isAlive();
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"))) {
            Log.i(TAG, "listening on 127.0.0.1:" + PORT);
            while (true) {
                Socket client = server.accept();
                Thread t = new Thread(() -> {
                    try {
                        new ClientSession(this, client).run();
                    } catch (Throwable e) {
                        Log.e(TAG, "client error", e);
                    } finally {
                        try {
                            client.close();
                        } catch (IOException ignored) {}
                    }
                }, "DropShell-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "server failed", t);
        }
    }

    /** One TCP connection — persistent session with optional foreground exec. */
    private static final class ClientSession {
        private final LocalShellServer mServer;
        private final Socket mSocket;
        private final InputStream mIn;
        private final OutputStream mOut;
        private final PrintWriter mWriter;
        private final StringBuilder mLine = new StringBuilder();

        private volatile Process mForeground;
        private volatile OutputStream mForegroundStdin;
        private volatile Thread mPumpThread;
        private volatile boolean mRunning = true;
        private volatile boolean mOneshot;
        private volatile boolean mExecKilled;
        private String[] mExecArgv = new String[0];

        /** Minimal telnet option stripping (IAC …). */
        private int mTelnetState;

        ClientSession(LocalShellServer server, Socket socket) throws IOException {
            mServer = server;
            mSocket = socket;
            mIn = socket.getInputStream();
            mOut = socket.getOutputStream();
            mWriter = new PrintWriter(new OutputStreamWriter(mOut, StandardCharsets.UTF_8), true);
        }

        void run() throws Exception {
            printBanner();
            sendPrompt();
            try {
                while (mRunning) {
                    int b = readFilteredByte();
                    if (b < 0) break;
                    handleInputByte((byte) b);
                }
            } catch (IOException e) {
                // peer hung up or we closed the socket after --oneshot
            }
            killForeground();
        }

        private int readFilteredByte() throws IOException {
            while (true) {
                int b = mIn.read();
                if (b < 0) return -1;
                if (filterTelnet(b)) continue;
                return b;
            }
        }

        private boolean filterTelnet(int b) {
            switch (mTelnetState) {
                case 0:
                    if (b == 255) {
                        mTelnetState = 1;
                        return true;
                    }
                    return false;
                case 1: // IAC seen
                    if (b == 255) {
                        mTelnetState = 0;
                        return false; // literal 0xFF
                    }
                    mTelnetState = (b == 250) ? 3 : 0; // SB or skip 2-byte commands
                    return true;
                case 3: // subnegotiation
                    if (b == 240) mTelnetState = 0;
                    return true;
                default:
                    mTelnetState = 0;
                    return true;
            }
        }

        private void handleInputByte(byte b) throws Exception {
            if (b == 0x03) { // Ctrl-C
                handleInterrupt();
                return;
            }
            if (b == 0x04) { // Ctrl-D
                if (mLine.length() == 0) {
                    mRunning = false;
                }
                return;
            }
            if (b == '\r') return;
            if (b == '\n') {
                String line = mLine.toString().trim();
                mLine.setLength(0);
                if (!line.isEmpty()) {
                    dispatchLine(line);
                } else if (mRunning && mForeground == null) {
                    sendPrompt();
                }
                return;
            }
            if (b == 0x7f || b == 0x08) { // backspace
                if (mLine.length() > 0) {
                    mLine.setLength(mLine.length() - 1);
                }
                return;
            }
            if (b >= 32 || b == '\t') {
                mLine.append((char) b);
            }
        }

        private void handleInterrupt() {
            mLine.setLength(0);
            if (mForeground != null) {
                killForeground();
                mWriter.println("^C");
                sendPrompt();
            }
        }

        private void dispatchLine(String line) throws Exception {
            if ("--oneshot".equals(line)) {
                mOneshot = true;
                return;
            }
            if (line.startsWith("--oneshot ")) {
                mOneshot = true;
                line = line.substring("--oneshot ".length()).trim();
                if (line.isEmpty()) {
                    return;
                }
            }
            if ("quit".equals(line) || "exit".equals(line)) {
                mWriter.println("bye");
                mRunning = false;
                return;
            }
            if (line.startsWith("exec ")) {
                if (mForeground != null) {
                    killForeground();
                }
                startExec(parseExecArgv(line));
                return;
            }
            if (mForeground != null) {
                killForeground();
                mWriter.println("^C");
            }
            try {
                mWriter.println(mServer.handle(line));
            } catch (Throwable t) {
                mWriter.println("ERR " + InstalldClient.describe(t));
            }
            finishAfterCommand();
        }

        private void startExec(String[] argv) {
            if (argv.length == 0) {
                mWriter.println("usage: exec <binary> [args...]");
                finishAfterCommand();
                return;
            }
            try {
                mExecKilled = false;
                mExecArgv = argv.clone();
                ProcessBuilder pb = new ProcessBuilder(argv);
                pb.redirectErrorStream(true);
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                Process p = pb.start();
                mForeground = p;
                mForegroundStdin = p.getOutputStream();
                Thread pump = new Thread(() -> pumpProcess(p), "DropShell-pump");
                mPumpThread = pump;
                pump.setDaemon(true);
                pump.start();
            } catch (Throwable t) {
                mWriter.println("ERR " + InstalldClient.describe(t));
                finishAfterCommand();
            }
        }

        private void pumpProcess(Process p) {
            byte[] buf = new byte[4096];
            try (InputStream stdout = p.getInputStream()) {
                int n;
                while ((n = stdout.read(buf)) >= 0) {
                    if (n == 0) continue;
                    synchronized (mOut) {
                        mOut.write(buf, 0, n);
                        mOut.flush();
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "pump ended: " + e.getMessage());
            }

            int exit = waitForQuietly(p);
            OutputStream stdin;
            synchronized (ClientSession.this) {
                if (mForeground == p) {
                    mForeground = null;
                    stdin = mForegroundStdin;
                    mForegroundStdin = null;
                    mPumpThread = null;
                } else {
                    stdin = null;
                }
            }
            closeQuietly(stdin);
            if (mRunning) {
                synchronized (mOut) {
                    mWriter.println("\n[exit " + exit + "]");
                    mWriter.flush();
                }
                finishAfterCommand();
            }
        }

        private void killForeground() {
            mExecKilled = true;
            Process p;
            Thread pump;
            OutputStream stdin;
            synchronized (this) {
                p = mForeground;
                pump = mPumpThread;
                stdin = mForegroundStdin;
                mForeground = null;
                mPumpThread = null;
                mForegroundStdin = null;
            }
            if (p != null) {
                p.destroy();
                waitForQuietly(p);
            }
            closeQuietly(stdin);
            if (pump != null) {
                pump.interrupt();
            }
        }

        private static int waitForQuietly(Process p) {
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        private static void closeQuietly(OutputStream os) {
            if (os == null) return;
            try {
                os.close();
            } catch (IOException ignored) {}
        }

        private void finishAfterCommand() {
            if (mOneshot) {
                closeSession();
            } else {
                sendPrompt();
            }
        }

        /** End session and unblock the reader thread (needed after exec in --oneshot). */
        private void closeSession() {
            mRunning = false;
            try {
                mSocket.close();
            } catch (IOException ignored) {}
        }

        private void sendPrompt() {
            if (mRunning) {
                synchronized (mOut) {
                    mWriter.print("> ");
                    mWriter.flush();
                }
            }
        }

        private void printBanner() {
            mWriter.println("# DropShell uid=1000 system_server");
            mWriter.println("# Ctrl-C kill running exec | Ctrl-D disconnect | help");
            mWriter.println("# exec <bin> [args...] streams live; other commands return immediately");
            mWriter.println("# --oneshot <cmd>  run one command and close connection");
        }
    }

    private static String[] parseExecArgv(String line) {
        String args = line.substring("exec ".length()).trim();
        if (args.startsWith("--stream ")) {
            args = args.substring("--stream ".length());
        } else if (args.startsWith("-s ")) {
            args = args.substring("-s ".length());
        }
        return splitArgs(args);
    }

    private String handle(String line) throws Exception {
        if ("help".equals(line)) {
            return helpGeneral();
        }
        if (line.startsWith("help ")) {
            return helpTopic(line.substring("help ".length()).trim());
        }
        if ("id".equals(line)) {
            return "use: exec id";
        }
        if ("getenforce".equals(line)) {
            return "use: exec getenforce";
        }
        if (line.startsWith("file ")) {
            return handleFile(line.substring("file ".length()).trim());
        }
        if (line.startsWith("perm ")) {
            return handlePerm(line.substring("perm ".length()).trim());
        }
        if (line.startsWith("install ")) {
            return PmsHelper.installApk(mContext, line.substring("install ".length()).trim());
        }
        if ("pms installer".equals(line)) {
            return PmsHelper.getInstallerFromPms();
        }
        if (line.startsWith("installd ")) {
            return handleInstalld(line.substring("installd ".length()).trim());
        }
        if (line.startsWith("vold ")) {
            return handleVold(line.substring("vold ".length()).trim());
        }
        return "unknown command (try help)";
    }

    private static String helpGeneral() {
        return ""
                + "DropShell — interactive session (uid 1000, not kernel root)\n"
                + "\n"
                + "Session:\n"
                + "  > prompt after each command; connection stays open\n"
                + "  --oneshot <cmd> — run one command and disconnect\n"
                + "  Ctrl-C — kill running exec\n"
                + "  Ctrl-D — disconnect (EOF on empty line)\n"
                + "  quit | exit — close session\n"
                + "\n"
                + "  exec <binary> [args...]   always streams stdout/stderr live\n"
                + "                            exits → [exit N] → next prompt\n"
                + "  help [file|perm|installd|vold]\n"
                + "\n"
                + "Files: file read|write|stat|mkdir|delete|rm [-r]\n"
                + "Perms: perm chmod <path> <octal>\n"
                + "PM:    install <apk> | pms installer\n"
                + "\n"
                + "See: help installd | help vold\n"
                + "\n"
                + "Example:\n"
                + "  telnet 127.0.0.1 31337\n"
                + "  > exec dmesg -w\n"
                + "  ^C\n"
                + "  > exec id";
    }

    private static String helpTopic(String topic) {
        switch (topic) {
            case "file":
                return ""
                        + "file read <path>              read file (text or base64 if >64KiB)\n"
                        + "file write <path> b64:<data>  write bytes from base64\n"
                        + "file write <path> text:<str>  write UTF-8 text after prefix\n"
                        + "file stat <path>              ls-like metadata\n"
                        + "file mkdir <path>             create directory tree\n"
                        + "file delete <path>            delete empty dir or file\n"
                        + "file rm <path>                alias for delete\n"
                        + "file rm -r <path>             recursive delete";
            case "perm":
                return ""
                        + "perm chmod <path> <octal>     root chmod via installd backup restore\n"
                        + "                              staging: " + InstalldClient.BACKUP_SRC + "\n"
                        + "                              example: perm chmod /data/local/tmp/out/x 755\n"
                        + "\n"
                        + "Note: installd copy sets owner root:root (mode 600). Use perm chmod after copy.\n"
                        + "Note: backup info format only supports chmod, not arbitrary chown.";
            case "installd":
                return ""
                        + "installd ping | methods\n"
                        + "installd copy <from> <toDir/> [pkg]   Honor JSON copyFile (root fopen)\n"
                        + "installd chmod <path> <octal>        alias for perm chmod\n"
                        + "installd backup start                returns session id\n"
                        + "installd backup exec <id> <taskCmd>\n"
                        + "installd backup finish <id>\n"
                        + "installd backup run <taskCmd>        start+exec+finish one-shot\n"
                        + "installd job <cmd>                   excuteJob (may block)\n"
                        + "installd app createAppData <pkg> <userId> <flags> <appId> <seInfo> <targetSdk>\n"
                        + "installd xattr set <path> <key> <storageType> <fileType>\n"
                        + "installd link <uuid> <from> <to> <relative>\n"
                        + "installd bind <uuid> <path> <target>\n"
                        + "installd fixup <uuid> <flags>\n"
                        + "installd restorecon <uuid> <pkg> <userId> <flags>\n"
                        + "installd nativelib link <uuid> <pkg> <lib32> <lib64> <userId>\n"
                        + "installd rmPackageDir <codePath>";
            case "vold":
                return ""
                        + "vold ping | methods\n"
                        + "vold mount <blk> <mountPoint> [zonedDevice]\n"
                        + "vold stub create <src> <mount> <fstype> [uuid] [label] [flags]\n"
                        + "vold stub destroy <volId>\n"
                        + "vold bind <sourceDir> <targetDir>\n"
                        + "vold read_partition <src> <dest> [maxBytes]";
            default:
                return "unknown help topic: " + topic + " (try: file, perm, installd, vold)";
        }
    }

    private static String handleFile(String args) throws Exception {
        if (args.startsWith("read ")) {
            return fileRead(args.substring("read ".length()).trim());
        }
        if (args.startsWith("write ")) {
            return fileWrite(args.substring("write ".length()).trim());
        }
        if (args.startsWith("stat ")) {
            return fileStat(args.substring("stat ".length()).trim());
        }
        if (args.startsWith("mkdir ")) {
            File dir = new File(args.substring("mkdir ".length()).trim());
            boolean ok = dir.mkdirs();
            return ok ? "ok mkdir " + dir.getAbsolutePath() : "failed mkdir " + dir.getAbsolutePath();
        }
        if (args.startsWith("rm -r ")) {
            return fileDelete(args.substring("rm -r ".length()).trim(), true);
        }
        if (args.startsWith("rm ")) {
            return fileDelete(args.substring("rm ".length()).trim(), false);
        }
        if (args.startsWith("delete ")) {
            return fileDelete(args.substring("delete ".length()).trim(), false);
        }
        return "usage: file read|write|stat|mkdir|delete|rm [-r] (try help file)";
    }

    private static String handlePerm(String args) throws Exception {
        if (args.startsWith("chmod ")) {
            String[] p = splitArgs(args.substring("chmod ".length()));
            if (p.length < 2) {
                return "usage: perm chmod <path> <mode_octal>";
            }
            int mode = Integer.parseInt(p[1], 8);
            return new InstalldClient().chmodViaBackupRestore(p[0], mode);
        }
        return "usage: perm chmod <path> <mode_octal>  (try help perm)";
    }

    private static String fileWrite(String rest) throws Exception {
        int sp = rest.indexOf(' ');
        if (sp < 0) {
            return "usage: file write <path> b64:<base64> | file write <path> text:<utf8>";
        }
        String path = rest.substring(0, sp);
        String payload = rest.substring(sp + 1).trim();
        byte[] data;
        if (payload.startsWith("b64:")) {
            data = Base64.decode(payload.substring(4), Base64.DEFAULT);
        } else if (payload.startsWith("text:")) {
            data = payload.substring(5).getBytes(StandardCharsets.UTF_8);
        } else {
            return "usage: file write <path> b64:<base64> | file write <path> text:<utf8>";
        }
        File parent = new File(path).getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(data);
        }
        return "wrote " + data.length + " bytes to " + path;
    }

    private static String fileStat(String path) {
        File f = new File(path);
        if (!f.exists()) {
            return "missing " + path;
        }
        String type = f.isDirectory() ? "dir" : (f.isFile() ? "file" : "other");
        return "path=" + path
                + " type=" + type
                + " size=" + f.length()
                + " canRead=" + f.canRead()
                + " canWrite=" + f.canWrite()
                + " canExecute=" + f.canExecute()
                + " mtime=" + f.lastModified();
    }

    private static String fileDelete(String path, boolean recursive) throws Exception {
        File f = new File(path);
        if (!f.exists()) {
            return "missing " + path;
        }
        if (f.isDirectory()) {
            if (!recursive) {
                String[] children = f.list();
                if (children != null && children.length > 0) {
                    return "directory not empty (use file rm -r " + path + ")";
                }
                if (!f.delete()) {
                    return "failed to delete dir " + path;
                }
            } else {
                deleteRecursive(f);
            }
        } else if (!f.delete()) {
            return "failed to delete file " + path;
        }
        return "ok deleted " + path;
    }

    private static void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!f.delete()) {
            throw new IOException("failed to delete " + f.getAbsolutePath());
        }
    }

    private String handleInstalld(String args) throws Exception {
        InstalldClient c = installd();
        if ("ping".equals(args)) {
            return String.valueOf(c.installd());
        }
        if ("methods".equals(args)) {
            return c.listMethods();
        }
        if (args.startsWith("copy ")) {
            String[] p = splitArgs(args.substring("copy ".length()));
            if (p.length < 2) return "usage: installd copy <from> <toDir/> [pkgName]";
            String pkg = p.length >= 3 ? p[2] : mContext.getPackageName();
            return c.copyFileJson(p[0], p[1], pkg);
        }
        if (args.startsWith("chmod ")) {
            String[] p = splitArgs(args.substring("chmod ".length()));
            if (p.length < 2) return "usage: installd chmod <path> <mode_octal>";
            int mode = Integer.parseInt(p[1], 8);
            return c.chmodViaBackupRestore(p[0], mode);
        }
        if ("backup start".equals(args)) {
            return "session=" + c.startBackupSession();
        }
        if (args.startsWith("backup exec ")) {
            String[] p = splitArgs(args.substring("backup exec ".length()));
            if (p.length < 2) return "usage: installd backup exec <sessionId> <taskCmd...>";
            int session = Integer.parseInt(p[0]);
            String cmd = joinFrom(p, 1);
            int task = c.executeBackupTask(session, cmd);
            Thread.sleep(3000);
            return "session=" + session + " task=" + task + " cmd=" + cmd;
        }
        if (args.startsWith("backup finish ")) {
            int session = Integer.parseInt(args.substring("backup finish ".length()).trim());
            return "finish=" + c.finishBackupSession(session);
        }
        if (args.startsWith("backup run ")) {
            String cmd = args.substring("backup run ".length()).trim();
            int session = c.startBackupSession();
            Thread.sleep(500);
            int task = c.executeBackupTask(session, cmd);
            Thread.sleep(3000);
            int fin = c.finishBackupSession(session);
            return "session=" + session + " task=" + task + " finish=" + fin + " cmd=" + cmd;
        }
        if (args.startsWith("job ")) {
            return c.executeJob(args.substring("job ".length()).trim());
        }
        if (args.startsWith("app createAppData ")) {
            String[] p = splitArgs(args.substring("app createAppData ".length()));
            if (p.length < 6) {
                return "usage: installd app createAppData <pkg> <userId> <flags> <appId> <seInfo> <targetSdk>";
            }
            return c.createAppData(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), p[4], Integer.parseInt(p[5]));
        }
        if (args.startsWith("xattr set ")) {
            String[] p = splitArgs(args.substring("xattr set ".length()));
            if (p.length < 4) {
                return "usage: installd xattr set <path> <keyDesc> <storageType> <fileType>";
            }
            return c.setFileXattr(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        }
        if (args.startsWith("link ")) {
            String[] p = splitArgs(args.substring("link ".length()));
            if (p.length < 4) return "usage: installd link <uuid> <from> <to> <relative>";
            return c.linkFile(p[0], p[1], p[2], p[3]);
        }
        if (args.startsWith("bind ")) {
            String[] p = splitArgs(args.substring("bind ".length()));
            if (p.length < 3) return "usage: installd bind <uuid> <path> <target>";
            return c.bindFile(p[0], p[1], p[2]);
        }
        if (args.startsWith("fixup ")) {
            String[] p = splitArgs(args.substring("fixup ".length()));
            if (p.length < 2) return "usage: installd fixup <uuid> <flags>";
            return c.fixupAppData(p[0], Integer.parseInt(p[1]));
        }
        if (args.startsWith("restorecon ")) {
            String[] p = splitArgs(args.substring("restorecon ".length()));
            if (p.length < 4) {
                return "usage: installd restorecon <uuid> <pkg> <userId> <flags>";
            }
            return c.restoreconAppData(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        }
        if (args.startsWith("nativelib link ")) {
            String[] p = splitArgs(args.substring("nativelib link ".length()));
            if (p.length < 5) {
                return "usage: installd nativelib link <uuid> <pkg> <lib32> <lib64> <userId>";
            }
            return c.linkNativeLibraryDirectory(p[0], p[1], p[2], p[3], Integer.parseInt(p[4]));
        }
        if (args.startsWith("rmPackageDir ")) {
            return c.rmPackageDir(args.substring("rmPackageDir ".length()).trim());
        }
        return "unknown installd command (try help installd)";
    }

    private String handleVold(String args) throws Exception {
        VoldClient c = vold();
        if ("ping".equals(args)) {
            return String.valueOf(c.vold());
        }
        if ("methods".equals(args)) {
            return c.listMethods();
        }
        if (args.startsWith("mount ")) {
            String[] p = splitArgs(args.substring("mount ".length()));
            if (p.length < 2) return "usage: vold mount <blk> <mountPoint> [zonedDevice]";
            String zone = p.length >= 3 ? p[2] : "";
            return c.mountFstab(p[0], p[1], zone);
        }
        if (args.startsWith("stub create ")) {
            String[] p = splitArgs(args.substring("stub create ".length()));
            if (p.length < 3) {
                return "usage: vold stub create <source> <mount> <fstype> [uuid] [label] [flags]";
            }
            String uuid = p.length >= 4 ? p[3] : "";
            String label = p.length >= 5 ? p[4] : "";
            int flags = p.length >= 6 ? Integer.decode(p[5]) : 0;
            return c.createStubVolume(p[0], p[1], p[2], uuid, label, flags);
        }
        if (args.startsWith("stub destroy ")) {
            return c.destroyStubVolume(args.substring("stub destroy ".length()).trim());
        }
        if (args.startsWith("bind ")) {
            String[] p = splitArgs(args.substring("bind ".length()));
            if (p.length < 2) return "usage: vold bind <sourceDir> <targetDir>";
            return c.bindMount(p[0], p[1]);
        }
        if (args.startsWith("read_partition ")) {
            return readPartition(splitArgs(args.substring("read_partition ".length())));
        }
        return "unknown vold command (try help vold)";
    }

    private String readPartition(String[] p) throws Exception {
        if (p.length < 2) {
            return "usage: vold read_partition <src> <dest> [maxBytes]";
        }
        String src = p[0];
        String dest = p[1];
        long maxBytes = p.length >= 3 ? Long.parseLong(p[2]) : 0;

        StringBuilder log = new StringBuilder();
        log.append("src=").append(src).append(" dest=").append(dest).append('\n');

        try {
            String r = installd().copyFileJson(src, dest + "/", mContext.getPackageName());
            log.append("installd: ").append(r).append('\n');
        } catch (Exception e) {
            log.append("installd failed: ").append(InstalldClient.describe(e)).append('\n');
            String mnt = "/data/local/tmp/.vold_mnt";
            new File(mnt).mkdirs();
            try {
                String r = vold().mountFstab(src, mnt, "");
                log.append("vold: ").append(r).append('\n');
                File[] files = new File(mnt).listFiles();
                if (files != null) {
                    for (File f : files) {
                        log.append("  ").append(f.getName()).append(" size=").append(f.length()).append('\n');
                    }
                }
            } catch (Exception e2) {
                log.append("vold mountFstab failed: ").append(InstalldClient.describe(e2)).append('\n');
                return log.toString().trim();
            }
        }

        File out = new File(dest);
        if (!out.exists()) {
            return log.append("dest missing").toString().trim();
        }
        long size = out.length();
        log.append("dest size=").append(size).append('\n');
        if (maxBytes > 0 && size > maxBytes) {
            truncateFile(dest, maxBytes);
            log.append("truncated to ").append(maxBytes).append('\n');
            size = maxBytes;
        }
        if (size <= 256) {
            log.append("hex=").append(bytesToHex(readFileBytes(dest, (int) size)));
        }
        return log.toString().trim();
    }

    private static void truncateFile(String path, long maxBytes) throws IOException {
        try (FileInputStream fis = new FileInputStream(path);
             FileOutputStream fos = new FileOutputStream(path)) {
            byte[] buf = new byte[8192];
            long left = maxBytes;
            int n;
            while (left > 0 && (n = fis.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                fos.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static byte[] readFileBytes(String path, int max) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buf = new byte[256];
            int n;
            while (baos.size() < max && (n = fis.read(buf, 0, Math.min(buf.length, max - baos.size()))) > 0) {
                baos.write(buf, 0, n);
            }
        }
        return baos.toByteArray();
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private InstalldClient installd() throws Exception {
        if (mInstalld == null) {
            mInstalld = new InstalldClient();
        }
        return mInstalld;
    }

    private VoldClient vold() throws Exception {
        if (mVold == null) {
            mVold = new VoldClient();
        }
        return mVold;
    }

    private static String fileRead(String path) throws Exception {
        File f = new File(path);
        if (!f.canRead()) {
            return "cannot read " + path;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) >= 0) {
                if (n > 0) baos.write(buf, 0, n);
            }
        }
        byte[] data = baos.toByteArray();
        if (data.length > 65536) {
            return "size=" + data.length + " (truncated base64)\n"
                    + Base64.encodeToString(Arrays.copyOf(data, 65536), Base64.NO_WRAP);
        }
        return "size=" + data.length + "\n" + new String(data, StandardCharsets.UTF_8);
    }

    private static String[] splitArgs(String s) {
        return s.trim().split("\\s+");
    }

    private static String joinFrom(String[] parts, int start) {
        if (start >= parts.length) return "";
        StringBuilder sb = new StringBuilder(parts[start]);
        for (int i = start + 1; i < parts.length; i++) {
            sb.append(' ').append(parts[i]);
        }
        return sb.toString();
    }
}
