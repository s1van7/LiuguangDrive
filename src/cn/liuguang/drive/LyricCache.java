package cn.liuguang.drive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 歌词时间轴落盘。
 *
 * 蓝牙只给"当前这一句"，所以第一次听某首歌只能一句一句攒。攒完存到本机，
 * 下次再放同一首歌就直接有整首时间轴，不用再等一遍。这是自动的，用户不需要
 * 导入任何文件；文件名是曲目键的哈希，用户看不见。
 */
final class LyricCache {
    private static final int MAX_FILES = 300;
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MIN_LINES = 3;

    private final File dir;

    LyricCache(File filesDir) {
        this.dir = new File(filesDir, "lyric-timeline");
    }

    void save(String key, List<LrcParser.Line> lines) {
        if (key == null || key.isEmpty() || lines == null || lines.size() < MIN_LINES) return;
        try {
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, key + ".txt");
            File temp = new File(dir, key + ".tmp");
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), "UTF-8"));
            try {
                int bytes = 0;
                for (LrcParser.Line line : lines) {
                    String row = line.time + "\t" + line.text + "\n";
                    bytes += row.length() * 3;
                    if (bytes > MAX_BYTES) break;
                    writer.write(row);
                }
            } finally {
                writer.close();
            }
            if (!temp.renameTo(target)) {
                target.delete();
                temp.renameTo(target);
            }
            prune();
        } catch (Exception ignored) {
            // 缓存失败不影响播放，下次重新攒
        }
    }

    List<LrcParser.Line> load(String key) {
        List<LrcParser.Line> lines = new ArrayList<LrcParser.Line>();
        if (key == null || key.isEmpty()) return lines;
        File file = new File(dir, key + ".txt");
        if (!file.isFile()) return lines;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String row;
                while ((row = reader.readLine()) != null) {
                    int tab = row.indexOf('\t');
                    if (tab <= 0 || tab + 1 >= row.length()) continue;
                    long time;
                    try { time = Long.parseLong(row.substring(0, tab)); }
                    catch (NumberFormatException e) { continue; }
                    String text = row.substring(tab + 1).trim();
                    if (text.isEmpty()) continue;
                    lines.add(new LrcParser.Line(Math.max(0L, time), text));
                }
            } finally {
                reader.close();
            }
            // 文件损坏时给出空结果，避免把半截时间轴当成完整歌词
            if (lines.size() < MIN_LINES) lines.clear();
        } catch (Exception ignored) {
            lines.clear();
        }
        return lines;
    }

    /** 只保留最近使用的若干个文件，防止长期使用后占满存储。 */
    private void prune() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        for (int i = MAX_FILES; i < files.length; i++) files[i].delete();
    }

    void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
    }

    int count() {
        File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }
}
