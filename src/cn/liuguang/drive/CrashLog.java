package cn.liuguang.drive;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 未捕获异常落盘，方便用户在无 USB 调试的车机上把崩溃现场带出来。 */
final class CrashLog {
    private static final String FILE = "crash.log";
    private static final int MAX_CHARS = 12000;

    private CrashLog() {}

    static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try { write(context, error); } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    private static void write(Context context, Throwable error) {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        String head = "时间 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                + "\n机型 " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n\n";
        String body = head + stack;
        if (body.length() > MAX_CHARS) body = body.substring(0, MAX_CHARS);
        File file = new File(context.getFilesDir(), FILE);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((body + "\n\n----\n\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
            // 记录崩溃时不能再抛异常
        }
        if (file.length() > 256 * 1024) file.delete();
    }

    static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return "";
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            String text = new String(buffer.toByteArray(), "UTF-8");
            if (text.length() > MAX_CHARS) text = "…（仅显示最近记录）\n" + text.substring(text.length() - MAX_CHARS);
            return text.trim();
        } catch (Exception e) {
            return "";
        }
    }

    static void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
    }
}
