package br.com.sysattack;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Leitor de cronogramas do Microsoft Project, só para importar tarefas: lê o arquivo .mpp (Project 2010 ou mais novo,
 * formato "MPP14") e o XML do Project (Arquivo > Salvar como > XML, MSPDI). Sem bibliotecas externas.
 *
 * <p>O .mpp é um "arquivo dentro de arquivo" (OLE2/CFB). As tarefas ficam na pasta {@code TBkndTask}: os textos (nome) em
 * Var2Data, indexados pelo VarMeta, e os campos de tamanho fixo (nível, datas, % concluído) em FixedData, localizados pelo
 * FixedMeta. As ligações entre tarefas ficam em {@code TBkndCons}. Os deslocamentos dos campos foram conferidos contra
 * arquivos reais (resumos fecham com as datas das filhas e todas as ligações respeitam o tipo).
 */
final class ProjectFileReader {
    private ProjectFileReader() {}

    /** Ligação com uma predecessora: tipo 0=TT (fim-fim), 1=TI (fim-início), 2=IT (início-fim), 3=II (início-início). */
    static final class Link {
        final int pred; final int type; final double lagDays;
        Link(int pred, int type, double lagDays) { this.pred = pred; this.type = type; this.lagDays = lagDays; }
    }

    static final class Row {
        int uid, id, level;
        String name = "";
        LocalDateTime start, finish;
        int percent;
        boolean summary, milestone;
        final List<Link> preds = new ArrayList<>();
    }

    static final class Result {
        String format = "";
        String projectName = "";
        final List<Row> rows = new ArrayList<>();
    }

    private static final int MAX_ROWS = 5000;
    private static final int MAX_STREAM = 64 * 1024 * 1024;

    static Result parse(byte[] data) {
        if (data == null || data.length < 64) throw bad("O arquivo está vazio ou incompleto.");
        try {
            if ((data[0] & 0xff) == 0xD0 && (data[1] & 0xff) == 0xCF) return parseMpp(data);
            String head = new String(data, 0, Math.min(data.length, 400), StandardCharsets.UTF_8);
            if (head.contains("<?xml") || head.contains("<Project")) return parseXml(data);
        } catch (ApiServlet.ApiException e) {
            throw e;
        } catch (RuntimeException e) { // arquivo corrompido: nunca vira erro 500
            throw bad("Não consegui ler este arquivo. Ele pode estar danificado ou ser de uma versão do Project não suportada. Exporte como XML do Project e importe o .xml.");
        }
        throw bad("Formato não reconhecido. Use um arquivo .mpp do Microsoft Project ou o XML do Project (Arquivo > Salvar como > XML).");
    }

    private static ApiServlet.ApiException bad(String m) { return new ApiServlet.ApiException(400, m); }

    // ------------------------------------------------------------------ .mpp (OLE2 / CFB)

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }

    /** Leitor mínimo de CFB: só o necessário para achar os fluxos pelo caminho. */
    private static final class Cfb {
        final byte[] d; final int ss, mss, cutoff;
        int[] fat; int[] miniFat = new int[0]; byte[] mini = new byte[0];
        final List<String> names = new ArrayList<>(); final List<Integer> types = new ArrayList<>();
        final List<int[]> links = new ArrayList<>(); // left, right, child, start, size
        final Map<String, Integer> byPath = new LinkedHashMap<>();

        Cfb(byte[] data) {
            d = data;
            if (data.length < 512) throw bad("Arquivo .mpp inválido.");
            ss = 1 << u16(d, 30); mss = 1 << u16(d, 32);
            if (ss < 512 || ss > 4096 || mss < 16 || mss > 512) throw bad("Arquivo .mpp inválido.");
            int nFat = i32(d, 44), dirStart = i32(d, 48), miniFatStart = i32(d, 60), nMiniFat = i32(d, 64),
                difatStart = i32(d, 68), nDifat = i32(d, 72);
            cutoff = i32(d, 56);
            if (nFat <= 0 || nFat > 100000 || nDifat < 0 || nDifat > 100000) throw bad("Arquivo .mpp inválido.");
            List<Integer> fatSecs = new ArrayList<>();
            for (int i = 0; i < 109 && fatSecs.size() < nFat; i++) fatSecs.add(i32(d, 76 + i * 4));
            int sec = difatStart;
            for (int k = 0; k < nDifat && sec >= 0 && sec < 0xFFFFFFFAL; k++) {
                int base = off(sec);
                for (int i = 0; i < ss / 4 - 1 && fatSecs.size() < nFat; i++) fatSecs.add(i32(d, base + i * 4));
                sec = i32(d, base + ss - 4);
            }
            fat = new int[fatSecs.size() * (ss / 4)];
            int p = 0;
            for (int f : fatSecs) { int base = off(f); for (int i = 0; i < ss / 4; i++) fat[p++] = i32(d, base + i * 4); }
            byte[] dir = chain(dirStart, -1);
            for (int i = 0; i + 128 <= dir.length; i += 128) {
                int nl = u16(dir, i + 64);
                String nm = nl >= 2 ? new String(dir, i, nl - 2, StandardCharsets.UTF_16LE) : "";
                names.add(nm); types.add((int) dir[i + 66]);
                links.add(new int[] {i32(dir, i + 68), i32(dir, i + 72), i32(dir, i + 76), i32(dir, i + 116), i32(dir, i + 120)});
            }
            if (nMiniFat > 0 && miniFatStart >= 0) {
                byte[] mf = chain(miniFatStart, -1);
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = i32(mf, i * 4);
            }
            if (!links.isEmpty() && links.get(0)[3] >= 0) mini = chain(links.get(0)[3], -1);
            walk(links.get(0)[2], "", new HashSet<>());
        }

        int off(int sector) {
            long o = ((long) sector + 1) * ss;
            if (sector < 0 || o + ss > d.length) throw bad("Arquivo .mpp danificado ou incompleto.");
            return (int) o;
        }

        byte[] chain(int start, int size) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n = start, guard = 0;
            while (n >= 0 && n < 0xFFFFFFFAL && n < fat.length && guard++ < 2_000_000) {
                int o = off(n);
                out.write(d, o, ss);
                if (out.size() > MAX_STREAM) throw bad("Arquivo grande demais para importar.");
                n = fat[n];
            }
            byte[] all = out.toByteArray();
            return size >= 0 && size < all.length ? java.util.Arrays.copyOf(all, size) : all;
        }

        void walk(int idx, String path, Set<Integer> seen) {
            if (idx < 0 || idx >= names.size() || !seen.add(idx)) return;
            int[] e = links.get(idx);
            walk(e[0], path, seen);
            String p = path + "/" + names.get(idx);
            byPath.put(p, idx);
            if (types.get(idx) == 1 && e[2] >= 0) walk(e[2], p, seen);
            walk(e[1], path, seen);
        }

        /** Conteúdo do fluxo, ou null se não existir. */
        byte[] stream(String path) {
            Integer idx = byPath.get(path);
            if (idx == null) return null;
            int[] e = links.get(idx);
            int size = e[4];
            if (size < 0 || size > MAX_STREAM) throw bad("Arquivo .mpp inválido.");
            if (size < cutoff) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n = e[3], guard = 0;
                while (n >= 0 && n < 0xFFFFFFFAL && n < miniFat.length && guard++ < 1_000_000) {
                    int o = n * mss;
                    if (o + mss > mini.length) break;
                    out.write(mini, o, mss);
                    n = miniFat[n];
                }
                byte[] all = out.toByteArray();
                return size < all.length ? java.util.Arrays.copyOf(all, size) : all;
            }
            return chain(e[3], size);
        }
    }

    private static final LocalDate MPP_EPOCH = LocalDate.of(1983, 12, 31);

    /** Data/hora do MPP: 2 bytes de horas (décimos de minuto) e 2 bytes de dias desde 31/12/1983. */
    private static LocalDateTime mppDate(byte[] b, int o) {
        if (o + 4 > b.length) return null;
        int t = u16(b, o), days = u16(b, o + 2);
        if (days == 0 || days > 60000) return null;
        return MPP_EPOCH.plusDays(days).atStartOfDay().plusMinutes(t / 10);
    }

    private static Result parseMpp(byte[] data) {
        Cfb cfb = new Cfb(data);
        String dir = null;
        for (String p : cfb.byPath.keySet()) {
            if (p.endsWith("/TBkndTask")) { dir = p.substring(0, p.length() - "/TBkndTask".length()); break; }
        }
        if (dir == null) {
            throw bad("Não consegui ler este .mpp. Ele precisa ser do Project 2010 ou mais novo. No Project, use Arquivo > Salvar como > XML do Project e importe o .xml.");
        }
        String t = dir + "/TBkndTask/";
        byte[] varMeta = cfb.stream(t + "VarMeta"), var2 = cfb.stream(t + "Var2Data"),
               fixMeta = cfb.stream(t + "FixedMeta"), fixData = cfb.stream(t + "FixedData");
        if (varMeta == null || var2 == null || fixMeta == null || fixData == null || varMeta.length < 24) {
            throw bad("Este .mpp não tem a tabela de tarefas no formato esperado. Exporte como XML do Project e importe o .xml.");
        }
        int n = i32(varMeta, 8);
        if (n < 0 || n > 2_000_000 || 24L + 12L * n > varMeta.length) throw bad("Arquivo .mpp inválido (tabela de tarefas).");
        Map<Integer, String> names = new HashMap<>();
        Set<Integer> uids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int uid = i32(varMeta, 24 + i * 12), off = i32(varMeta, 28 + i * 12), type = i32(varMeta, 32 + i * 12) & 0xffff;
            uids.add(uid);
            if (type == 14 && off >= 0 && off + 4 <= var2.length) {
                int sz = i32(var2, off);
                if (sz >= 0 && off + 4 + sz <= var2.length) names.put(uid, new String(var2, off + 4, sz & ~1, StandardCharsets.UTF_16LE).replace("\0", "").trim());
            }
        }
        if (uids.size() > MAX_ROWS + 1) throw bad("O cronograma tem tarefas demais para importar (limite de " + MAX_ROWS + ").");
        // localiza o registro fixo de cada tarefa: o FixedMeta guarda o deslocamento; o registro começa com ID e UID
        Map<Integer, Integer> recOff = new HashMap<>();
        for (int m = 0; m + 4 <= fixMeta.length; m++) {
            int o = i32(fixMeta, m);
            if (o < 0 || o + 12 > fixData.length) continue;
            int id = i32(fixData, o), uid = i32(fixData, o + 4);
            if (uids.contains(uid) && id >= 0 && id < 100000 && !recOff.containsKey(uid) && o + 176 <= fixData.length) recOff.put(uid, o);
        }
        if (recOff.isEmpty()) throw bad("Não encontrei tarefas neste .mpp. Exporte como XML do Project e importe o .xml.");

        Result res = new Result();
        res.format = "mpp";
        List<Row> all = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : recOff.entrySet()) {
            int o = e.getValue();
            Row r = new Row();
            r.uid = e.getKey(); r.id = i32(fixData, o);
            r.name = names.getOrDefault(r.uid, "");
            r.level = fixData[o + 172] & 0xff;
            r.start = mppDate(fixData, o + 96); r.finish = mppDate(fixData, o + 100);
            r.percent = Math.min(100, u16(fixData, o + 92));
            if (r.uid == 0) { res.projectName = r.name; continue; }
            all.add(r);
        }
        all.sort((a, b) -> Integer.compare(a.id, b.id));
        // ligações entre tarefas (TBkndCons): registros de 20 bytes {id, predecessora, sucessora, tipo, atraso}
        byte[] cons = cfb.stream(dir + "/TBkndCons/FixedData");
        Map<Integer, Row> byUid = new HashMap<>();
        for (Row r : all) byUid.put(r.uid, r);
        if (cons != null) {
            for (int i = 0; i + 20 <= cons.length; i += 20) {
                int pred = i32(cons, i + 4), succ = i32(cons, i + 8), type = u16(cons, i + 12);
                int lag = (short) u16(cons, i + 14); // décimos de minuto (jornada de 8 h = 4800)
                Row s = byUid.get(succ);
                if (s != null && byUid.containsKey(pred) && type <= 3) s.preds.add(new Link(pred, type, lag / 4800.0));
            }
        }
        finish(all, res);
        return res;
    }

    /** Preenche resumo/marco, normaliza datas e aplica os limites. */
    private static void finish(List<Row> all, Result res) {
        if (all.size() > MAX_ROWS) throw bad("O cronograma tem tarefas demais para importar (limite de " + MAX_ROWS + ").");
        for (int i = 0; i < all.size(); i++) {
            Row r = all.get(i);
            r.summary = i + 1 < all.size() && all.get(i + 1).level > r.level;
            if (r.start != null && r.finish != null && r.finish.isBefore(r.start)) r.finish = r.start;
            r.milestone = !r.summary && r.start != null && r.start.equals(r.finish);
        }
        res.rows.addAll(all);
    }

    // ------------------------------------------------------------------ XML do Project (MSPDI)

    private static String text(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        for (int i = 0; i < l.getLength(); i++) if (l.item(i).getParentNode() == e) return l.item(i).getTextContent().trim();
        return "";
    }

    private static LocalDateTime xmlDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            if (s.length() > 19 && (s.endsWith("Z") || s.matches(".*[+-][0-9]{2}:[0-9]{2}$"))) return OffsetDateTime.parse(s).toLocalDateTime();
            return LocalDateTime.parse(s.length() >= 19 ? s.substring(0, 19) : s + "T00:00:00");
        } catch (RuntimeException ex) {
            try { return LocalDate.parse(s.substring(0, 10)).atStartOfDay(); } catch (RuntimeException ex2) { return null; }
        }
    }

    private static int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    private static Result parseXml(byte[] data) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // sem XXE
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(new ByteArrayInputStream(data));
        } catch (Exception ex) {
            throw bad("Não consegui ler o XML do Project. Confirme que foi salvo em Arquivo > Salvar como > XML do Project.");
        }
        Element root = doc.getDocumentElement();
        Result res = new Result();
        res.format = "xml";
        res.projectName = text(root, "Title").isEmpty() ? text(root, "Name") : text(root, "Title");
        NodeList tasksNode = root.getElementsByTagName("Tasks");
        if (tasksNode.getLength() == 0) throw bad("O XML não tem tarefas.");
        List<Row> all = new ArrayList<>();
        NodeList kids = tasksNode.item(0).getChildNodes();
        int seq = 0;
        for (int i = 0; i < kids.getLength(); i++) {
            Node nd = kids.item(i);
            if (!(nd instanceof Element) || !nd.getNodeName().equals("Task")) continue;
            Element t = (Element) nd;
            int uid = num(text(t, "UID"), -1);
            if (uid <= 0 && text(t, "OutlineLevel").equals("0")) continue; // linha-resumo do projeto
            if (uid == 0) continue;
            if ("0".equals(text(t, "Active"))) continue;
            Row r = new Row();
            r.uid = uid; r.id = num(text(t, "ID"), ++seq);
            r.name = text(t, "Name");
            r.level = num(text(t, "OutlineLevel"), 1);
            r.start = xmlDate(text(t, "Start")); r.finish = xmlDate(text(t, "Finish"));
            r.percent = Math.max(0, Math.min(100, num(text(t, "PercentComplete"), 0)));
            NodeList pl = t.getElementsByTagName("PredecessorLink");
            for (int k = 0; k < pl.getLength(); k++) {
                Element pe = (Element) pl.item(k);
                int pu = num(text(pe, "PredecessorUID"), -1), ty = num(text(pe, "Type"), 1);
                if (pu > 0 && ty >= 0 && ty <= 3) r.preds.add(new Link(pu, ty, num(text(pe, "LinkLag"), 0) / 4800.0));
            }
            all.add(r);
            if (all.size() > MAX_ROWS) throw bad("O cronograma tem tarefas demais para importar (limite de " + MAX_ROWS + ").");
        }
        if (all.isEmpty()) throw bad("O XML não tem tarefas.");
        all.sort((a, b) -> Integer.compare(a.id, b.id));
        finish(all, res);
        // descarta ligações para tarefas inexistentes
        Set<Integer> have = new HashSet<>();
        for (Row r : all) have.add(r.uid);
        for (Row r : all) r.preds.removeIf(l -> !have.contains(l.pred));
        return res;
    }
}
