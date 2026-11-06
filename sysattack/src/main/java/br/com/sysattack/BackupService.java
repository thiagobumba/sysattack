package br.com.sysattack;

import br.com.sysattack.ApiServlet.ApiException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Cópias de segurança do SysAttack.
 *
 * <p>Cada backup é UM arquivo zip com tudo o que é preciso para restaurar: o banco (cópia física consistente feita com o
 * BACKUP TO do H2, que funciona com o sistema em uso, e um script SQL portátil que independe da versão do H2), os
 * documentos enviados, a chave de criptografia (ai.key) e o config.properties. O arquivo é gravado como ".part" e só
 * ganha o nome final quando está completo; em seguida é verificado (CRC do zip e abertura do banco copiado), recebe um
 * SHA-256, é replicado para a pasta espelho (se configurada) e a retenção é aplicada (diários, semanais e mensais).
 */
final class BackupService {
    private BackupService() {}

    private static final Logger LOG = Logger.getLogger("sysattack");
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final Pattern NAME = Pattern.compile("^sysattack-(\\d{8}-\\d{6})-(auto|manual|pre-update)\\.zip$");
    private static final Object LOCK = new Object();
    private static final String HISTORY = "backup-history.jsonl";

    // ------------------------------------------------------------------ configuração
    private static Properties cfg() {
        Properties p = AppConfig.load();
        return p;
    }

    private static String setting(String prop, String env, String key) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        if (v == null || v.isBlank()) v = cfg().getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Pasta dos backups: -Dsysattack.backup, variável SYSATTACK_BACKUP ou backup.dir no config.properties; padrão: <dados>/backups. */
    static File backupDir() {
        String v = setting("sysattack.backup", "SYSATTACK_BACKUP", "backup.dir");
        return v != null ? new File(v) : new File(Db.dataDir(), "backups");
    }

    /** Segunda cópia (outro disco, compartilhamento de rede, nuvem sincronizada): -Dsysattack.backup.mirror, SYSATTACK_BACKUP_MIRROR ou backup.mirror. */
    static File mirrorDir() {
        String v = setting("sysattack.backup.mirror", "SYSATTACK_BACKUP_MIRROR", "backup.mirror");
        return v != null ? new File(v) : null;
    }

    // ------------------------------------------------------------------ execução
    /** Faz um backup só se o último bem-sucedido tem mais de {@code hours} horas (usado ao iniciar, antes de atualizar o esquema). */
    static void runIfStale(String kind, int hours) {
        try {
            Map<String, Object> last = lastOk();
            if (last != null && Duration.between(LocalDateTime.parse((String) last.get("at")), LocalDateTime.now()).toHours() < hours) return;
        } catch (RuntimeException ignored) { /* histórico ilegível: faz o backup */ }
        run(kind);
    }

    static Map<String, Object> run(String kind) {
        synchronized (LOCK) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> res = new LinkedHashMap<>();
            LocalDateTime now = LocalDateTime.now();
            res.put("at", now.withNano(0).toString());
            res.put("kind", kind);
            File dir = backupDir();
            File tmp = new File(dir, ".tmp-" + TS.format(now));
            File part = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Não foi possível criar a pasta de backups: " + dir);
                if (!tmp.mkdirs()) throw new IOException("Não foi possível criar a pasta temporária em " + dir);
                String name = "sysattack-" + TS.format(now) + "-" + kind + ".zip";
                File phys = new File(tmp, "physical.zip"), script = new File(tmp, "script.sql.zip");
                Map<String, Long> counts = new LinkedHashMap<>();
                boolean scriptOk = false;
                String scriptMsg = "";
                try (Connection c = Db.get(); Statement s = c.createStatement()) {
                    s.execute("BACKUP TO '" + sql(phys) + "'");
                    try {
                        s.execute("SCRIPT TO '" + sql(script) + "' COMPRESSION ZIP");
                        scriptOk = script.isFile() && script.length() > 0;
                    } catch (SQLException e) {
                        scriptMsg = e.getMessage();
                        LOG.warning("Backup: o script SQL portátil falhou (a cópia física foi feita): " + e);
                    }
                    counts = tableCounts(c);
                }
                if (!phys.isFile() || phys.length() == 0) throw new IOException("A cópia física do banco não foi gerada.");

                File finalFile = new File(dir, name);
                part = new File(dir, name + ".part");
                int docs = 0;
                try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(part.toPath()))) {
                    z.setLevel(Deflater.BEST_SPEED);
                    JsonObject man = new JsonObject();
                    man.addProperty("created_at", res.get("at").toString());
                    man.addProperty("kind", kind);
                    man.addProperty("data_dir", Db.dataDir());
                    man.addProperty("data_id", Db.dataId());
                    man.addProperty("h2_version", Db.h2Version());
                    man.addProperty("script_ok", scriptOk);
                    if (!scriptMsg.isEmpty()) man.addProperty("script_error", scriptMsg);
                    man.add("tables", GSON.toJsonTree(counts));
                    put(z, "manifest.json", GSON.toJson(man).getBytes(StandardCharsets.UTF_8));
                    putFile(z, "database/physical.zip", phys);
                    if (scriptOk) putFile(z, "database/script.sql.zip", script);
                    File docsDir = new File(Db.dataDir(), "docs");
                    File[] ds = docsDir.listFiles(File::isFile);
                    if (ds != null) for (File d : ds) { putFile(z, "files/docs/" + d.getName(), d); docs++; }
                    for (String cfgName : new String[] {"ai.key", "config.properties"}) {
                        File f = new File(Db.dataDir(), cfgName);
                        if (f.isFile()) putFile(z, "config/" + cfgName, f);
                    }
                }
                Files.move(part.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Db.restrict(finalFile);
                Db.restrict(dir);
                part = null;
                String sha = sha256(finalFile);
                Files.write(new File(dir, name + ".sha256").toPath(), (sha + "  " + name + "\n").getBytes(StandardCharsets.UTF_8));
                res.put("file", name);
                res.put("size", finalFile.length());
                res.put("sha256", sha);
                res.put("docs", docs);
                res.put("script", scriptOk);

                Map<String, Object> v = verify(finalFile);
                res.put("verified", v.get("ok"));
                res.put("verify_message", v.get("message"));

                res.put("mirror", mirror(finalFile, sha));
                prune(dir, mirrorDir());
                res.put("ok", true);
            } catch (Exception e) {
                LOG.severe("Backup FALHOU: " + e);
                res.put("ok", false);
                res.put("error", String.valueOf(e.getMessage()));
                if (part != null) part.delete();
            } finally {
                deleteTree(tmp);
                res.put("seconds", (System.currentTimeMillis() - t0) / 1000.0);
                appendHistory(dir, res);
            }
            if (Boolean.TRUE.equals(res.get("ok"))) {
                LOG.info("Backup concluído: " + res.get("file") + " (" + res.get("size") + " bytes, verificado=" + res.get("verified") + ")");
            }
            return res;
        }
    }

    private static Map<String, Long> tableCounts(Connection c) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        for (String t : tables) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM \"" + t.replace("\"", "\"\"") + "\"")) {
                rs.next();
                out.put(t.toLowerCase(), rs.getLong(1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ verificação
    /**
     * Confere um arquivo de backup: lê o zip inteiro (CRC), confere o SHA-256 (se houver) e abre a cópia física do banco
     * em modo somente leitura, percorrendo todas as tabelas. É o teste de restauração, sem tocar no banco em uso.
     */
    static Map<String, Object> verify(File archive) {
        Map<String, Object> out = new LinkedHashMap<>();
        File tmp = new File(archive.getParentFile(), ".verify-" + System.nanoTime());
        try (ZipFile z = new ZipFile(archive)) {
            boolean hasManifest = false, hasPhysical = false;
            Enumeration<? extends ZipEntry> en = z.entries();
            byte[] buf = new byte[65536];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                try (InputStream in = z.getInputStream(e)) { while (in.read(buf) > 0) { /* lê tudo: confere o CRC */ } }
                if (e.getName().equals("manifest.json")) hasManifest = true;
                if (e.getName().equals("database/physical.zip")) hasPhysical = true;
            }
            if (!hasManifest || !hasPhysical) throw new IOException("Arquivo incompleto (faltam manifest.json ou database/physical.zip).");
            File side = new File(archive.getPath() + ".sha256");
            if (side.isFile()) {
                String want = new String(Files.readAllBytes(side.toPath()), StandardCharsets.UTF_8).trim().split("\\s+")[0];
                if (!want.equalsIgnoreCase(sha256(archive))) throw new IOException("O SHA-256 não confere: o arquivo foi alterado ou está corrompido.");
            }
            if (!tmp.mkdirs()) throw new IOException("Sem pasta temporária para a verificação.");
            File phys = new File(tmp, "physical.zip");
            try (InputStream in = z.getInputStream(z.getEntry("database/physical.zip"))) { Files.copy(in, phys.toPath()); }
            File mv = null;
            try (ZipFile pz = new ZipFile(phys)) {
                Enumeration<? extends ZipEntry> pe = pz.entries();
                while (pe.hasMoreElements()) {
                    ZipEntry e = pe.nextElement();
                    if (e.getName().endsWith(".mv.db")) {
                        mv = new File(tmp, "check.mv.db");
                        try (InputStream in = pz.getInputStream(e)) { Files.copy(in, mv.toPath()); }
                        break;
                    }
                }
            }
            if (mv == null) throw new IOException("A cópia física não contém o arquivo do banco.");
            long tables = 0, rows = 0;
            try (Connection c = java.sql.DriverManager.getConnection("jdbc:h2:file:" + new File(tmp, "check").getAbsolutePath() + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", "sa", "");
                 Statement s = c.createStatement()) {
                List<String[]> cols = new ArrayList<>();
                try (ResultSet rs = s.executeQuery("SELECT t.TABLE_NAME, (SELECT c.COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS c WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME ORDER BY c.ORDINAL_POSITION LIMIT 1)"
                        + " FROM INFORMATION_SCHEMA.TABLES t WHERE t.TABLE_SCHEMA = 'PUBLIC' AND t.TABLE_TYPE = 'BASE TABLE'")) {
                    while (rs.next()) cols.add(new String[] {rs.getString(1), rs.getString(2)});
                }
                for (String[] t : cols) {
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(\"" + t[1] + "\") FROM \"" + t[0] + "\"")) { // percorre todas as linhas
                        rs.next();
                        rows += rs.getLong(1);
                        tables++;
                    }
                }
            }
            if (tables == 0) throw new IOException("O banco copiado não tem tabelas.");
            out.put("ok", true);
            out.put("message", "Íntegro: " + tables + " tabelas e " + rows + " registros lidos da cópia.");
        } catch (Exception e) {
            LOG.warning("Verificação do backup " + archive.getName() + " falhou: " + e);
            out.put("ok", false);
            out.put("message", String.valueOf(e.getMessage()));
        } finally {
            deleteTree(tmp);
        }
        return out;
    }

    // ------------------------------------------------------------------ espelho e retenção
    private static String mirror(File archive, String sha) {
        File m = mirrorDir();
        if (m == null) return "não configurado";
        try {
            if (!m.isDirectory() && !m.mkdirs()) throw new IOException("pasta inacessível: " + m);
            File dest = new File(m, archive.getName());
            Files.copy(archive.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(dest).equalsIgnoreCase(sha)) throw new IOException("a cópia espelho não confere com o original");
            Files.write(new File(m, archive.getName() + ".sha256").toPath(), (sha + "  " + archive.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            return "ok";
        } catch (Exception e) {
            LOG.warning("Backup: falha ao copiar para a pasta espelho: " + e);
            return "falhou: " + e.getMessage();
        }
    }

    /** Retenção: um backup por dia nos últimos 14 dias, por semana nas últimas 8, por mês nos últimos 12; manuais por 90 dias; sempre os 3 mais novos. */
    private static final class Item {
        final String name; final LocalDateTime at; final String kind;
        Item(String name, LocalDateTime at, String kind) { this.name = name; this.at = at; this.kind = kind; }
    }

    static Set<String> keepSet(List<String> names, LocalDate today) {
        List<Item> items = new ArrayList<>();
        for (String n : names) {
            Matcher m = NAME.matcher(n);
            if (m.matches()) items.add(new Item(n, LocalDateTime.parse(m.group(1), TS), m.group(2)));
        }
        items.sort((x, y) -> y.at.compareTo(x.at));
        Set<String> keep = new HashSet<>(), days = new HashSet<>(), weeks = new HashSet<>(), months = new HashSet<>();
        int rank = 0;
        for (Item i : items) {
            LocalDate d = i.at.toLocalDate();
            long age = java.time.temporal.ChronoUnit.DAYS.between(d, today);
            String day = d.toString(), week = d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), month = d.getYear() + "-" + d.getMonthValue();
            if (rank++ < 3) keep.add(i.name);
            if (i.kind.equals("manual") && age <= 90) keep.add(i.name);
            if (age <= 14 && days.add(day)) keep.add(i.name);
            if (age <= 56 && weeks.add(week)) keep.add(i.name);
            if (age <= 366 && months.add(month)) keep.add(i.name);
        }
        return keep;
    }

    private static void prune(File... dirs) {
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            String[] names = dir.list((d, n) -> NAME.matcher(n).matches());
            if (names == null) continue;
            Set<String> keep = keepSet(java.util.Arrays.asList(names), LocalDate.now());
            for (String n : names) {
                if (keep.contains(n)) continue;
                new File(dir, n).delete();
                new File(dir, n + ".sha256").delete();
            }
        }
    }

    // ------------------------------------------------------------------ histórico
    private static void appendHistory(File dir, Map<String, Object> res) {
        try {
            if (!dir.isDirectory()) return;
            Files.write(new File(dir, HISTORY).toPath(), (GSON.toJson(res) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("Backup: não foi possível gravar o histórico: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        File f = new File(backupDir(), HISTORY);
        if (!f.isFile()) return out;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                if (lines.get(i).isBlank()) continue;
                try { out.add(GSON.fromJson(lines.get(i), LinkedHashMap.class)); } catch (RuntimeException ignored) { /* linha ruim */ }
            }
        } catch (IOException ignored) { /* sem histórico */ }
        return out;
    }

    private static Map<String, Object> lastOk() {
        for (Map<String, Object> h : history(200)) if (Boolean.TRUE.equals(h.get("ok"))) return h;
        return null;
    }

    // ------------------------------------------------------------------ agendamento
    /** Backup diário às 02:00 e, se o servidor ficou desligado nessa hora, um backup logo após subir. */
    static void schedule(ScheduledExecutorService ex) {
        LocalDateTime next = LocalDate.now().atTime(2, 0);
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1);
        long delay = Duration.between(LocalDateTime.now(), next).toMinutes();
        ex.scheduleAtFixedRate(() -> run("auto"), delay, 24 * 60, TimeUnit.MINUTES);
        Map<String, Object> last = lastOk();
        boolean stale;
        try { stale = last == null || Duration.between(LocalDateTime.parse((String) last.get("at")), LocalDateTime.now()).toHours() >= 26; } catch (RuntimeException e) { stale = true; }
        if (stale) ex.schedule(() -> run("auto"), 2, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ API (administrador)
    static Object handle(String m, List<String> s, HttpServletRequest req, HttpServletResponse resp, Map<String, Object> user) throws Exception {
        String what = s.size() > 1 ? s.get(1) : "";
        if (m.equals("GET") && what.isEmpty()) return status();
        if (m.equals("POST") && what.equals("run")) return run("manual");
        if (s.size() == 3 && what.equals("verify") && m.equals("POST")) return verify(file(s.get(2)));
        if (s.size() == 3 && what.equals("download") && m.equals("GET")) {
            File f = file(s.get(2));
            resp.setContentType("application/zip");
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("Content-Length", String.valueOf(f.length()));
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
            try (InputStream in = Files.newInputStream(f.toPath())) { in.transferTo(resp.getOutputStream()); }
            return ApiServlet.FILE_SENT;
        }
        throw new ApiException(405, "Operação não suportada.");
    }

    private static File file(String name) {
        if (!NAME.matcher(name).matches()) throw new ApiException(400, "Nome de backup inválido.");
        File f = new File(backupDir(), name);
        if (!f.isFile()) throw new ApiException(404, "Backup não encontrado.");
        return f;
    }

    static Map<String, Object> status() throws SQLException {
        Map<String, Object> o = new LinkedHashMap<>();
        File data = new File(Db.dataDir()), bdir = backupDir(), mdir = mirrorDir();
        o.put("data_dir", data.getAbsolutePath());
        o.put("data_source", Db.dataSource());
        o.putAll(Invites.addressInfo());
        o.put("backup_dir", bdir.getAbsolutePath());
        o.put("mirror_dir", mdir == null ? null : mdir.getAbsolutePath());
        o.put("data_id", Db.dataId());
        o.put("h2_version", Db.h2Version());
        File mv = new File(data, "sysattack.mv.db");
        o.put("db_size", mv.length());
        long docs = 0, n = 0;
        File[] ds = new File(data, "docs").listFiles(File::isFile);
        if (ds != null) for (File d : ds) { docs += d.length(); n++; }
        o.put("docs_size", docs);
        o.put("docs_count", n);
        o.put("free_space", bdir.getParentFile() == null && !bdir.exists() ? 0 : freeSpace(bdir));
        List<Map<String, Object>> list = new ArrayList<>();
        File[] all = bdir.listFiles((d, nm) -> NAME.matcher(nm).matches());
        if (all != null) {
            java.util.Arrays.sort(all, Collections.reverseOrder());
            for (File f : all) {
                Matcher mt = NAME.matcher(f.getName());
                mt.matches();
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", f.getName());
                e.put("size", f.length());
                e.put("at", LocalDateTime.parse(mt.group(1), TS).toString());
                e.put("kind", mt.group(2));
                list.add(e);
            }
        }
        o.put("backups", list);
        List<Map<String, Object>> hist = history(30);
        o.put("history", hist);
        Map<String, Object> ok = lastOk();
        o.put("last_ok", ok);
        o.put("warnings", warnings(data, bdir, mdir, ok, hist, mv));
        return o;
    }

    private static long freeSpace(File f) {
        File x = f;
        while (x != null && !x.exists()) x = x.getParentFile();
        return x == null ? 0 : x.getUsableSpace();
    }

    private static List<String> warnings(File data, File bdir, File mdir, Map<String, Object> ok, List<Map<String, Object>> hist, File mv) {
        List<String> w = new ArrayList<>();
        try {
            if (ok == null) w.add("Nenhum backup bem-sucedido ainda. Clique em “Fazer backup agora”.");
            else if (Duration.between(LocalDateTime.parse((String) ok.get("at")), LocalDateTime.now()).toHours() > 26) w.add("O último backup bem-sucedido tem mais de 26 horas (" + ok.get("at").toString().replace('T', ' ') + ").");
            if (!hist.isEmpty() && Boolean.FALSE.equals(hist.get(0).get("ok"))) w.add("O último backup FALHOU: " + hist.get(0).get("error"));
            if (ok != null && Boolean.FALSE.equals(ok.get("verified"))) w.add("O último backup foi gerado, mas a verificação falhou: " + ok.get("verify_message"));
            if (ok != null && Boolean.FALSE.equals(ok.get("script"))) w.add("O script SQL portátil não foi gerado no último backup (a cópia física foi).");
        } catch (RuntimeException ignored) { /* aviso opcional */ }
        if (mdir == null) w.add("Não há segunda cópia (espelho). Se o disco do servidor falhar, o banco e os backups se perdem juntos. Defina backup.mirror (outro disco ou compartilhamento de rede).");
        try {
            if (Files.getFileStore(data.toPath()).equals(Files.getFileStore(freeSpaceRoot(bdir).toPath()))) w.add("Os backups estão no mesmo disco do banco. Isso não protege contra falha do disco: aponte backup.dir para outro disco.");
        } catch (IOException | RuntimeException ignored) { /* não comparável */ }
        String home = System.getProperty("user.home", "");
        if (Db.dataSource().startsWith("padrão") && (home.toLowerCase().contains("systemprofile") || home.toLowerCase().contains("windows"))) {
            w.add("A pasta de dados está no perfil do serviço do Windows (" + data + "). Defina um caminho fixo (-Dsysattack.data ou SYSATTACK_DATA) para o banco não “mudar” se a conta do serviço mudar.");
        } else if (Db.dataSource().startsWith("padrão")) {
            w.add("A pasta de dados usa o padrão do usuário que roda o Tomcat (" + data + "). Recomenda-se um caminho fixo fora do perfil (-Dsysattack.data).");
        }
        File legacy = new File(System.getProperty("user.home", ""), ".sysattack");
        try {
            if (!legacy.getCanonicalFile().equals(data.getCanonicalFile()) && new File(legacy, "sysattack.mv.db").isFile()) {
                w.add("Existe outro banco em " + legacy + " que NÃO está em uso. Confirme se os dados corretos são os de " + data + ".");
            }
        } catch (IOException ignored) { /* sem comparação */ }
        long need = mv.length() * 3;
        if (freeSpace(bdir) < need) w.add("Pouco espaço livre na pasta de backups (menos que 3× o tamanho do banco).");
        return w;
    }

    private static File freeSpaceRoot(File f) {
        File x = f;
        while (x != null && !x.exists()) x = x.getParentFile();
        return x == null ? f : x;
    }

    // ------------------------------------------------------------------ utilitários
    private static String sql(File f) { return f.getAbsolutePath().replace("'", "''"); }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    private static void putFile(ZipOutputStream z, String name, File f) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        Files.copy(f.toPath(), z);
        z.closeEntry();
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
