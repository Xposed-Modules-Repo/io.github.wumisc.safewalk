package io.github.wumisc.safewalk;

import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/* loaded from: classes3.dex */
public final class DiagLogger {
    public static final String LOG_FILE_NAME = "safewalk_diag.log";
    private static final long MAX_FILE_SIZE = 819200;
    public static final String TAG = "SafeWalk";
    private static volatile boolean debugEnabled = false;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /* loaded from: classes3.dex */
    public enum Level {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E");

        final String tag;

        Level(String tag) {
            this.tag = tag;
        }
    }

    private DiagLogger() {
    }

    public static void setDebugEnabled(boolean on) {
        debugEnabled = on;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void i(String tag, String msg) {
        log(Level.INFO, tag, msg);
    }

    public static void d(String tag, String msg) {
        if (debugEnabled) {
            log(Level.DEBUG, tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        log(Level.WARN, tag, msg);
    }

    public static void e(String tag, String msg) {
        log(Level.ERROR, tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        log(Level.ERROR, tag, msg + " | " + stackTrace(t));
    }

    public static synchronized void log(Level level, String tag, String msg) {
        File logFile = null;
        synchronized (DiagLogger.class) {
            String time = SDF.format(new Date());
            String line = "[" + time + "][" + level.tag + "][" + tag + "] " + msg;
            logToLogcat(level, tag, msg);
            try {
                logFile = resolveLogFile();
            } catch (Throwable th) {
            }
            if (logFile == null) {
                return;
            }
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                truncateKeepTail(logFile);
            }
            try {
                FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter w = new OutputStreamWriter(fos);
                try {
                    w.write(line + "\n");
                    w.flush();
                } finally {
                    try { w.close(); } catch (Throwable ignored) {}
                    try { fos.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable th) {
            }
        }
    }

    public static File resolveLogFile() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) {
                if (!dl.exists()) {
                    dl.mkdirs();
                }
                return new File(dl, LOG_FILE_NAME);
            }
        } catch (Throwable th) {
        }
        return new File("/storage/emulated/0/Download/safewalk_diag.log");
    }

    private static void logToLogcat(Level level, String tag, String msg) {
        try {
            switch (level) {
                case DEBUG:
                    Log.d(TAG, "[" + tag + "] " + msg);
                    break;
                case WARN:
                    Log.w(TAG, "[" + tag + "] " + msg);
                    break;
                case ERROR:
                    Log.e(TAG, "[" + tag + "] " + msg);
                    break;
                default:
                    Log.i(TAG, "[" + tag + "] " + msg);
                    break;
            }
        } catch (Throwable th) {
        }
    }

    private static void truncateKeepTail(File logFile) {
        try {
            RandomAccessFile raf = new RandomAccessFile(logFile, "rw");
            try {
                long len = raf.length();
                long keep = len / 2;
                raf.seek(keep);
                long remain = len - keep;
                byte[] buf = new byte[(int) Math.min(remain, 65536L)];
                long pos = keep;
                long writePos = 0;
                raf.seek(0L);
                while (pos < len) {
                    raf.seek(pos);
                    int n = raf.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    raf.seek(writePos);
                    raf.write(buf, 0, n);
                    pos += n;
                    writePos += n;
                }
                raf.setLength(writePos);
                raf.close();
            } catch (Throwable th) {
                raf.close();
                throw th;
            }
        } catch (Throwable th2) {
        }
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] st = t.getStackTrace();
        int max = Math.min(st.length, 3);
        for (int i = 0; i < max; i++) {
            sb.append(" <- ").append(st[i]);
        }
        return sb.toString();
    }
}
