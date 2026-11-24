package br.com.sysattack;

import br.com.sysattack.ApiServlet.ApiException;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.servlet.http.HttpServletRequest;

/**
 * Importação de um backup: traz para esta instalação o banco, os documentos e a chave de criptografia de outra.
 *
 * <p>O arquivo do banco H2 só pode ser trocado com o banco <b>fechado</b>; com o Tomcat no ar ele está aberto e em uso.
 * Por isso a importação acontece em dois tempos: o administrador envia o zip, que é conferido na hora (CRC, SHA-256 e
 * abertura do banco da cópia) e fica <i>marcado</i>; a troca em si é feita por {@link #applyPending()}, chamado no
 * começo do {@link Db#init()}, antes de qualquer conexão. Nada é apagado: o que existe hoje é movido para
 * {@code antes-da-restauracao-<data>} dentro da pasta de dados, e se a troca falhar ela é desfeita.
 *
 * <p>A {@code ai.key} vem sempre do backup (sem ela os segredos gravados no banco importado — senha do SMTP, segredo do
 * OAuth, 2FA — não podem ser lidos). O {@code config.properties} é opcional, porque endereço, SMTP e pastas do novo
 * local normalmente são outros.
 */
final class BackupRestore {
    private BackupRestore() {}

    private static final Logger LOG = Logger.getLogger("sysattack");
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Limite do arquivo enviado (banco e documentos, compactados). */
    static final long MAX_IMPORT = 1024L * 1024 * 1024;
    private static final String ZIP = "restore-pending.zip";
    private static final String JSON = "restore-pending.json";
    private static final String HISTORY = "restore-history.jsonl";
    /** Sempre substituídos pela importação (a pasta {@code backups} NÃO entra: o histórico e os backups ficam). */
    private static final String[] ALWAYS = {"sysattack.mv.db", "sysattack.trace.db", "sysattack.lock.db", "schema.version", ".initialized", "docs"};

    // ------------------------------------------------------------------ envio e marcação
    /** Recebe o zip no corpo da requisição (nome em X-File-Name), confere e marca a importação. */
    static Map<String, Object> stage(HttpServletRequest req, Map<String, Object> user) throws Exception {
        String by = String.valueOf(user.get("email"));
        if (req.getContentLengthLong() > MAX_IMPORT) throw new ApiException(413, limit());
        String name = ApiServlet.header(req, "X-File-Name").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.length() > 120) name = name.substring(name.length() - 120);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new ApiException(400, "Envie o arquivo .zip de um backup do SysAttack.");
        boolean withConfig = "1".equals(ApiServlet.header(req, "X-With-Config"));

        File dir = new File(Db.dataDir());
        File tmp = new File(dir, ".import-" + System.nanoTime() + ".zip");
        try {
            long total = 0;
            try (InputStream in = req.getInputStream(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMPORT) throw new ApiException(413, limit());
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) throw new ApiException(400, "O arquivo chegou vazio.");
            Db.restrict(tmp);

            Map<String, Object> info = inspect(tmp);
            Map<String, Object> v = BackupService.verify(tmp);
            if (!Boolean.TRUE.equals(v.get("ok"))) throw new ApiException(400, "O arquivo não passou na verificação e não foi aceito: " + v.get("message"));

            info.put("file", name);
            info.put("size", total);
            info.put("sha256", BackupService.sha256(tmp));
            info.put("with_config", withConfig && Boolean.TRUE.equals(info.get("has_config")));
            info.put("requested_at", LocalDateTime.now().withNano(0).toString());
            info.put("requested_by", by);
            info.put("verify_message", v.get("message"));

            File pend = new File(dir, ZIP), j = new File(dir, JSON);
            Files.move(tmp.toPath(), pend.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(j.toPath(), GSON.toJson(info).getBytes(StandardCharsets.UTF_8));
            Db.restrict(pend);
            Db.restrict(j);
            LOG.warning("Importação de backup MARCADA por " + by + ": " + name + " (" + total + " bytes). Será aplicada no próximo início do Tomcat.");
            Audit.log(req, by, "BACKUP_IMPORTADO", name + " (marcado; aplica ao reiniciar)", true);

            Map<String, Object> out = new LinkedHashMap<>(info);
            out.put("ok", true);
            return out;
        } finally {
            if (tmp.isFile() && !tmp.delete()) LOG.warning("Não foi possível apagar o temporário " + tmp);
        }
    }

    private static String limit() {
        return "O arquivo passa de " + (MAX_IMPORT / 1048576) + " MB.";
    }

    /** Lê o manifest e o conteúdo do zip, sem extrair nada: data do backup, tabelas, documentos e o que ele traz. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> inspect(File archive) throws IOException {
        Map<String, Object> o = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(archive)) {
            ZipEntry mf = z.getEntry("manifest.json");
            if (mf == null || z.getEntry("database/physical.zip") == null) {
                throw new ApiException(400, "Este zip não é um backup do SysAttack (faltam manifest.json ou database/physical.zip).");
            }
            try (InputStream in = z.getInputStream(mf)) {
                Map<String, Object> man = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), LinkedHashMap.class);
                if (man != null) {
                    o.put("created_at", man.get("created_at"));
                    o.put("kind", man.get("kind"));
                    o.put("origin_dir", man.get("data_dir"));
                    o.put("origin_id", man.get("data_id"));
                    o.put("h2_version", man.get("h2_version"));
                    o.put("tables", man.get("tables"));
                }
            } catch (RuntimeException e) {
                throw new ApiException(400, "O manifest.json do arquivo está ilegível.");
            }
            int docs = 0;
            boolean key = false, cfg = false;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("files/docs/") && !n.endsWith("/")) docs++;
                else if (n.equals("config/ai.key")) key = true;
                else if (n.equals("config/config.properties")) cfg = true;
            }
            o.put("docs", docs);
            o.put("has_ai_key", key);
            o.put("has_config", cfg);
        }
        return o;
    }

    /** A importação marcada e ainda não aplicada, ou {@code null}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> pending() {
        File dir = new File(Db.dataDir());
        File zip = new File(dir, ZIP), j = new File(dir, JSON);
        if (!zip.isFile()) return null;
        Map<String, Object> out = null;
        if (j.isFile()) {
            try {
                out = GSON.fromJson(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8), LinkedHashMap.class);
            } catch (IOException | RuntimeException ignored) { /* marcação ilegível: mostra o que dá */ }
        }
        if (out == null) out = new LinkedHashMap<>();
        out.put("size", zip.length());
        return out;
    }

    /** Desfaz a marcação: o arquivo enviado é apagado e nada muda no próximo início. */
    static Map<String, Object> cancel(HttpServletRequest req, Map<String, Object> user) {
        File dir = new File(Db.dataDir());
        File zip = new File(dir, ZIP);
        boolean had = zip.isFile();
        String by = String.valueOf(user.get("email"));
        if (had && !zip.delete()) throw new ApiException(500, "Não foi possível apagar o arquivo da importação: " + zip);
        new File(dir, JSON).delete();
        if (had) {
            LOG.warning("Importação de backup CANCELADA por " + by + ".");
            Audit.log(req, by, "BACKUP_IMPORT_CANCELADO", "", true);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", true);
        o.put("cancelled", had);
        return o;
    }

    /** O resultado da última importação aplicada (fica no histórico, na pasta de backups). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> last() {
        File f = new File(BackupService.backupDir(), HISTORY);
        if (!f.isFile()) return null;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).isBlank()) continue;
                return GSON.fromJson(lines.get(i), LinkedHashMap.class);
            }
        } catch (IOException | RuntimeException ignored) { /* sem histórico legível */ }
        return null;
    }

    // ------------------------------------------------------------------ aplicação (início do Tomcat)
    /**
     * Aplica a importação marcada. Chamado no começo do {@link Db#init()}, com o banco ainda fechado. Extrai tudo para
     * uma pasta de trabalho primeiro; só depois move o que existe hoje para {@code antes-da-restauracao-<data>} e põe o
     * novo no lugar — assim a janela em que a pasta fica "no meio do caminho" é mínima e dá para voltar atrás.
     */
    static void applyPending() {
        File dir = new File(Db.dataDir());
        File zip = new File(dir, ZIP);
        if (!zip.isFile()) return;

        Map<String, Object> req = pending();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("at", LocalDateTime.now().withNano(0).toString());
        res.put("file", req == null ? null : req.get("file"));
        res.put("requested_by", req == null ? null : req.get("requested_by"));
        res.put("created_at", req == null ? null : req.get("created_at"));
        boolean withConfig = req != null && Boolean.TRUE.equals(req.get("with_config"));
        res.put("with_config", withConfig);

        File work = new File(dir, ".restore-work");
        File safe = new File(dir, "antes-da-restauracao-" + TS.format(LocalDateTime.now()));
        List<String> broughtIn = new ArrayList<>();
        try {
            LOG.warning("IMPORTAÇÃO DE BACKUP: aplicando " + res.get("file") + " antes de abrir o banco…");
            deleteTree(work);
            if (!work.mkdirs()) throw new IOException("não foi possível criar a pasta de trabalho " + work);
            int docs = extract(zip, work, withConfig);
            res.put("docs", docs);
            File db = new File(work, "sysattack.mv.db");
            if (!db.isFile() || db.length() == 0) throw new IOException("o backup não traz o arquivo do banco");

            List<String> replace = new ArrayList<>();
            for (String n : ALWAYS) replace.add(n);
            boolean key = new File(work, "ai.key").isFile();
            if (key) replace.add("ai.key");
            if (new File(work, "config.properties").isFile()) replace.add("config.properties");
            res.put("ai_key", key);
            if (!key) LOG.warning("IMPORTAÇÃO DE BACKUP: o arquivo não traz a ai.key; a chave atual foi mantida. Segredos gravados no banco importado podem não abrir.");

            if (!safe.mkdirs()) throw new IOException("não foi possível criar " + safe);
            for (String n : replace) {
                File f = new File(dir, n);
                if (!f.exists()) continue;
                Files.move(f.toPath(), new File(safe, n).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            File[] fresh = work.listFiles();
            if (fresh != null) {
                for (File f : fresh) {
                    Files.move(f.toPath(), new File(dir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    broughtIn.add(f.getName());
                }
            }
            Db.markInitialized(); // o banco importado já tem usuários: o "primeiro acesso" não vale mais
            Files.move(zip.toPath(), new File(safe, "importado-" + zip.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            new File(dir, JSON).delete();
            deleteTree(work);
            Db.restrict(safe);
            res.put("ok", true);
            res.put("kept_at", safe.getName());
            LOG.warning("IMPORTAÇÃO DE BACKUP concluída (" + docs + " documentos). O estado anterior ficou em " + safe + " — nada foi apagado.");
        } catch (Exception e) {
            res.put("ok", false);
            res.put("error", String.valueOf(e.getMessage()));
            LOG.severe("IMPORTAÇÃO DE BACKUP FALHOU: " + e + " — voltando ao estado anterior.");
            rollback(dir, safe, broughtIn);
            deleteTree(work);
            // a marcação sai de qualquer jeito: sem isso, cada início do Tomcat tentaria de novo
            if (zip.isFile() && !zip.renameTo(new File(dir, "restore-falhou.zip"))) zip.delete();
            new File(dir, JSON).delete();
        }
        appendHistory(res);
    }

    /** Desfaz a troca: tira o que veio do backup e devolve o que estava guardado. */
    private static void rollback(File dir, File safe, List<String> broughtIn) {
        try {
            for (String n : broughtIn) deleteTree(new File(dir, n));
            File[] saved = safe.listFiles();
            if (saved != null) {
                for (File b : saved) Files.move(b.toPath(), new File(dir, b.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            safe.delete();
            LOG.warning("IMPORTAÇÃO DE BACKUP: o estado anterior foi restaurado; o sistema sobe como estava.");
        } catch (IOException | RuntimeException e) {
            LOG.severe("IMPORTAÇÃO DE BACKUP: NÃO foi possível voltar ao estado anterior (" + e + "). O que havia antes está em " + safe + ".");
        }
    }

    /** Tira do zip o banco, os documentos e os arquivos de configuração. Devolve quantos documentos saíram. */
    private static int extract(File archive, File work, boolean withConfig) throws IOException {
        int docs = 0;
        File docsDir = new File(work, "docs");
        try (ZipFile z = new ZipFile(archive)) {
            File pz = new File(work, "physical.zip");
            try (InputStream in = z.getInputStream(z.getEntry("database/physical.zip"))) {
                Files.copy(in, pz.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            boolean found = false;
            try (ZipFile p = new ZipFile(pz)) {
                Enumeration<? extends ZipEntry> en = p.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".mv.db")) continue;
                    try (InputStream in = p.getInputStream(e)) {
                        Files.copy(in, new File(work, "sysattack.mv.db").toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    found = true;
                    break;
                }
            }
            if (!pz.delete()) LOG.warning("Importação: não foi possível apagar " + pz);
            if (!found) throw new IOException("a cópia física do backup não tem o arquivo .mv.db");

            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.startsWith("files/docs/")) {
                    String base = plainName(n.substring("files/docs/".length()));
                    if (base.isEmpty()) continue;
                    docsDir.mkdirs();
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, new File(docsDir, base).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    docs++;
                } else if (n.equals("config/ai.key") || (withConfig && n.equals("config/config.properties"))) {
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, new File(work, n.substring("config/".length())).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (!docsDir.isDirectory() && !docsDir.mkdirs()) throw new IOException("não foi possível criar a pasta de documentos");
        return docs;
    }

    /** Só o nome do arquivo, sem caminho: um zip malicioso não escreve fora da pasta. */
    private static String plainName(String n) {
        String s = n.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        return s.replaceAll("[\\p{Cntrl}]", "").trim();
    }

    private static void appendHistory(Map<String, Object> res) {
        try {
            File dir = BackupService.backupDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            Files.write(new File(dir, HISTORY).toPath(), (GSON.toJson(res) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Importação: não foi possível gravar o histórico: " + e);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
