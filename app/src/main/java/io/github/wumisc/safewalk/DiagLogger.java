package io.github.wumisc.safewalk;

import android.content.Context;
import android.os.Environment;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DiagLogger {
    private static final String LOG_NAME = "safewalk_diag.log";
    private static final long MAX_SIZE = 800 * 1024;
    private static DiagLogger instance;
    private File logFile;

    private DiagLogger(Context ctx) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        logFile = new File(downloadDir, LOG_NAME);
    }

    public static synchronized DiagLogger getInstance(Context ctx) {
        if (instance == null) instance = new DiagLogger(ctx);
        return instance;
    }

    public void log(String msg) {
        try {
            if (logFile.length() > MAX_SIZE) {
                logFile.delete();
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, true))) {
                writer.write(line);
            }
            XposedBridge.log("[SafeWalkLog] " + msg);
        } catch (Exception e) {
            XposedBridge.log("[SafeWalkLog Err] " + e.getMessage());
        }
    }

    public void clearLog() {
        try {
            logFile.delete();
        } catch (Exception ignored) {}
    }
}
