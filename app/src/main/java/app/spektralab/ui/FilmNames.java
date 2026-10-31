package app.spektralab.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * 胶片 / 相纸的中文名。
 *
 * 为什么放在代码里而不是 stock_catalog.json：
 * catalog 由 app/build/gen-stock-catalog.py 从引擎 profile 生成，字段只有英文 name；
 * 往生成物里塞中文会在下次重新生成时被覆盖，且 APK 里还多带几个开源引擎没有的
 * profile（黑白、CineStill）。所以中文名是一张编译期表 + 一条按 id 推导的兜底规则：
 * 没收录的新胶片也一定能显示成「柯达 xxx · 负片」，不会退回纯英文。
 *
 * 规则：品牌中文 + 系列中文 + 型号原样（型号里是数字/字母，翻译反而看不懂）。
 */
public final class FilmNames {

    private FilmNames() { }

    /** 收录了译名的胶片 id。 */
    private static final Map<String, String> FILMS = new HashMap<>();
    /** 收录了译名的相纸 / 拷贝片 id。 */
    private static final Map<String, String> PAPERS = new HashMap<>();

    private static final Map<String, String> BRANDS = new HashMap<>();

    static {
        BRANDS.put("kodak", "柯达");
        BRANDS.put("fujifilm", "富士");
        BRANDS.put("fuji", "富士");
        BRANDS.put("ilford", "依尔福");
        BRANDS.put("agfa", "爱克发");
        BRANDS.put("konica", "柯尼卡");
        BRANDS.put("lucky", "乐凯");
        BRANDS.put("rollei", "禄来");
        BRANDS.put("cinestill", "CineStill");
        BRANDS.put("lomography", "Lomography");
        BRANDS.put("adox", "阿多克斯");
        BRANDS.put("ferrania", "费拉尼亚");
        BRANDS.put("orwo", "欧沃");
        BRANDS.put("harman", "哈曼");
        BRANDS.put("silberra", "西尔贝拉");

        // ---- 胶片 ----
        FILMS.put("cinestill_800t", "CineStill 800T");
        FILMS.put("fujifilm_neopan_acros_100_ii", "富士 阿库罗斯 100 II");
        FILMS.put("ilford_hp5_plus_400", "依尔福 HP5 Plus 400");
        FILMS.put("kodak_tmax_100", "柯达 T-Max 100");
        FILMS.put("kodak_tri_x_400", "柯达 Tri-X 400");
        FILMS.put("kodak_portra_160", "柯达 波特拉 160");
        FILMS.put("kodak_portra_400", "柯达 波特拉 400");
        FILMS.put("kodak_portra_800", "柯达 波特拉 800");
        FILMS.put("kodak_portra_800_push1", "柯达 波特拉 800 推冲 +1");
        FILMS.put("kodak_portra_800_push2", "柯达 波特拉 800 推冲 +2");
        FILMS.put("kodak_ektar_100", "柯达 埃克塔 100");
        FILMS.put("kodak_gold_200", "柯达 金胶卷 200");
        FILMS.put("kodak_ultramax_400", "柯达 至尊 400");
        FILMS.put("kodak_ektachrome_100", "柯达 埃克塔克罗姆 100");
        FILMS.put("kodak_kodachrome_64", "柯达 柯达克罗姆 64");
        FILMS.put("kodak_verita_200d", "柯达 维利塔 200D");
        FILMS.put("kodak_vision3_50d", "柯达 威胜3 50D");
        FILMS.put("kodak_vision3_200t", "柯达 威胜3 200T");
        FILMS.put("kodak_vision3_250d", "柯达 威胜3 250D");
        FILMS.put("kodak_vision3_500t", "柯达 威胜3 500T");
        FILMS.put("fujifilm_c200", "富士 C200");
        FILMS.put("fujifilm_xtra_400", "富士 X-Tra 400");
        FILMS.put("fujifilm_pro_400h", "富士 400H");
        FILMS.put("fujifilm_velvia_100", "富士 维尔维亚 100");
        FILMS.put("fujifilm_provia_100f", "富士 普罗维亚 100F");

        // ---- 相纸 / 拷贝片（stage = printing）----
        PAPERS.put("ilford_multigrade_iv_rc", "依尔福 多级 IV RC 相纸");
        PAPERS.put("kodak_portra_endura", "柯达 波特拉 恩杜拉");
        PAPERS.put("kodak_endura_premier", "柯达 恩杜拉 顶级");
        PAPERS.put("kodak_supra_endura", "柯达 苏普拉 恩杜拉");
        PAPERS.put("kodak_ultra_endura", "柯达 至尊 恩杜拉");
        PAPERS.put("fujifilm_crystal_archive_typeii", "富士 水晶影像 II 型");
        PAPERS.put("kodak_2383", "柯达 2383 电影正片");
        PAPERS.put("kodak_2393", "柯达 2393 电影正片");
        PAPERS.put("kodak_ektacolor_edge", "柯达 埃克塔彩色 EDGE");
    }

    /** 胶片中文名。englishName 只用于兜底推导，可为 null。 */
    public static String film(String id, String englishName) {
        return resolve(FILMS, id, englishName);
    }

    /** 相纸 / 拷贝片中文名。 */
    public static String paper(String id, String englishName) {
        return resolve(PAPERS, id, englishName);
    }

    /**
     * 行尾的短标签：负片 / 反转 / 电影 / 相纸 / 拷贝片。
     *
     * @param stage "printing" 表示相纸一路
     * @param type  profile 里的 type（negative / positive）
     * @param use   profile 里的 use（still / cine）
     */
    public static String typeLabel(String stage, String type, String use) {
        final boolean printing = "printing".equals(stage);
        final boolean cine = "cine".equals(use);
        final boolean positive = "positive".equals(type);
        if (printing) return cine ? "拷贝片" : "相纸";
        if (cine) return "电影";
        return positive ? "反转" : "负片";
    }

    /** 一行列表要显示的主文本：中文名 + 短标签（标签在 UI 里右对齐，这里给拼好的备选）。 */
    public static String line(String zh, String typeLabel) {
        return (typeLabel == null || typeLabel.isEmpty()) ? zh : zh + " · " + typeLabel;
    }

    // ------------------------------------------------------------ 兜底

    private static String resolve(Map<String, String> table, String id, String englishName) {
        if (id == null) return englishName == null ? "" : englishName;
        String hit = table.get(id);
        if (hit != null) return hit;

        // brand_series_model → "品牌中文 + Series Model"
        String rest = id;
        String brandZh = null;
        final String[] parts = id.split("_");
        if (parts.length > 1 && BRANDS.containsKey(parts[0])) {
            brandZh = BRANDS.get(parts[0]);
            rest = id.substring(parts[0].length() + 1);
        }
        String series = humanize(rest);
        if (series.isEmpty()) series = englishName == null ? "" : englishName;
        return brandZh == null ? series : brandZh + " " + series;
    }

    /** "vision3_500t" → "Vision3 500T"；"pro_400h" → "Pro 400H"。 */
    private static String humanize(String s) {
        if (s == null || s.isEmpty()) return "";
        final String[] parts = s.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
