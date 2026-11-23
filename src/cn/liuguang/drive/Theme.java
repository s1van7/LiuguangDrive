package cn.liuguang.drive;

import android.graphics.Color;

/**
 * 配色方案。四套主题共用一套语义字段，界面与面板只读字段，不判断主题 id。
 *
 * dark 决定系统栏图标用亮色还是暗色；veilTop/veilBottom 是背景对封面模糊图的
 * 覆盖强度（0-255），浅色主题需要更厚的覆盖才不会被深色封面压暗。
 */
final class Theme {
    static final int STREAM = 0, MIST = 1, NIGHT = 2, CELADON = 3;
    static final String[] NAMES = {"流光", "晨雾", "极夜", "青瓷"};

    final int id;
    final boolean dark;
    final boolean largeText;

    final int bgTop, bgBottom, surface, surfaceHi, line;
    final int text, sub, dim, accent, accentInk, good, warn, shadow;
    final int veilTop, veilBottom;

    private Theme(int id, boolean largeText) {
        this.id = id;
        this.largeText = largeText;
        switch (id) {
            case MIST:
                dark = false;
                bgTop = 0xFFF0E7D8; bgBottom = 0xFFD8C7AC;
                surface = 0xFFF9F3E8; surfaceHi = 0xFFFFFCF4; line = 0xFFCBB99F;
                text = 0xFF3A3128; sub = 0xFF6F6254; dim = 0xFF9B8D7C;
                accent = 0xFFAD7C42; accentInk = 0xFFFFF8EC;
                good = 0xFF3F8F62; warn = 0xFFB26F16;
                veilTop = 202; veilBottom = 228; shadow = 0xFFB7A488;
                break;
            case NIGHT:
                dark = true;
                bgTop = 0xFF060607; bgBottom = 0xFF000000;
                surface = 0xFF131316; surfaceHi = 0xFF1E1E22; line = 0xFF2A2A30;
                text = 0xFFFFFFFF; sub = 0xFFA5A5AD; dim = 0xFF6A6A73;
                accent = 0xFFA9C4FF; accentInk = 0xFF0A1020;
                good = 0xFF7BD8A6; warn = 0xFFF0B45C;
                veilTop = 222; veilBottom = 250; shadow = 0xFF000000;
                break;
            case CELADON:
                dark = true;
                bgTop = 0xFF0F1D1B; bgBottom = 0xFF050E0D;
                surface = 0xFF16241F; surfaceHi = 0xFF1F322A; line = 0xFF2A3C34;
                text = 0xFFEAF4F0; sub = 0xFF93ACA5; dim = 0xFF5D726C;
                accent = 0xFF7FD8C0; accentInk = 0xFF06201A;
                good = 0xFF8FE0B8; warn = 0xFFE2B25E;
                veilTop = 208; veilBottom = 244; shadow = 0xFF020806;
                break;
            case STREAM:
            default:
                dark = true;
                bgTop = 0xFF15120E; bgBottom = 0xFF080706;
                surface = 0xFF1E1A15; surfaceHi = 0xFF2B241C; line = 0xFF342C23;
                text = 0xFFF7F0E6; sub = 0xFFA89B8B; dim = 0xFF6D6358;
                accent = 0xFFF2B98A; accentInk = 0xFF1F1509;
                good = 0xFF86D6A8; warn = 0xFFE8A552;
                veilTop = 188; veilBottom = 232; shadow = 0xFF050403;
                break;
        }
    }

    static Theme of(int id, boolean largeText) {
        if (id < 0 || id >= NAMES.length) id = STREAM;
        return new Theme(id, largeText);
    }

    static String name(int id) { return id >= 0 && id < NAMES.length ? NAMES[id] : NAMES[0]; }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }
}
