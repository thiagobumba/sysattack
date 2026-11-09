package br.com.sysattack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Acesso ao banco H2 em arquivo local + criação do esquema e dados de demonstração. */
public final class Db {
    private static String url;
    private static String dataDir;
    private static String dataSource = "";
    private static String dataId = "";
    private static String h2Version = "";
    /** Versão do esquema: ao mudar, o arquivo do banco é copiado (pre-update) antes de qualquer migração. */
    private static final int SCHEMA_VERSION = 43;

    private Db() {}

    public static synchronized void init() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        // Pasta dos dados: -Dsysattack.data=/caminho, variável SYSATTACK_DATA ou, por último, ~/.sysattack
        String dir = System.getProperty("sysattack.data");
        dataSource = "propriedade -Dsysattack.data";
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("SYSATTACK_DATA");
            dataSource = "variável de ambiente SYSATTACK_DATA";
        }
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home") + File.separator + ".sysattack";
            dataSource = "padrão (perfil do usuário que roda o Tomcat)";
        }
        dir = new File(dir.trim()).getAbsolutePath();
        new File(dir).mkdirs();
        dataDir = dir;
        restrict(new File(dir));
        java.util.logging.Logger.getLogger("sysattack").info("Pasta de dados: " + dir + " [" + dataSource + "]");
        preUpdateCopy();
        url = "jdbc:h2:file:" + new File(dir, "sysattack").getAbsolutePath() + ";DB_CLOSE_DELAY=-1";

        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "username VARCHAR(60) NOT NULL UNIQUE,"
                    + "name VARCHAR(120) NOT NULL,"
                    + "password_hash VARCHAR(200) NOT NULL,"
                    + "salt VARCHAR(60) NOT NULL,"
                    + "role VARCHAR(20) NOT NULL DEFAULT 'USER')");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS job_title VARCHAR(80) DEFAULT '' NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS invite_pending BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret_enc VARCHAR(400) DEFAULT '' NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_required BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_last_step BIGINT DEFAULT 0 NOT NULL");
            s.execute("CREATE TABLE IF NOT EXISTS recovery_codes ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                    + "code_hash VARCHAR(64) NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_recovery_user ON recovery_codes(user_id)");
            s.execute("CREATE TABLE IF NOT EXISTS user_tokens ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                    + "token_hash VARCHAR(64) NOT NULL UNIQUE,"
                    + "expires_at TIMESTAMP NOT NULL,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS projects ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "name VARCHAR(160) NOT NULL,"
                    + "scope VARCHAR(4000) NOT NULL DEFAULT '',"
                    + "owner VARCHAR(120) NOT NULL DEFAULT '',"
                    + "status VARCHAR(20) NOT NULL DEFAULT 'PLANEJAMENTO',"
                    + "start_date DATE NOT NULL,"
                    + "end_date DATE NOT NULL,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS tasks ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,"
                    + "name VARCHAR(160) NOT NULL,"
                    + "description VARCHAR(4000) NOT NULL DEFAULT '',"
                    + "responsible VARCHAR(120) NOT NULL DEFAULT '',"
                    + "start_date DATE NOT NULL,"
                    + "end_date DATE NOT NULL,"
                    + "progress INT NOT NULL DEFAULT 0,"
                    + "status VARCHAR(20) NOT NULL DEFAULT 'A_FAZER',"
                    + "depends_on BIGINT REFERENCES tasks(id) ON DELETE SET NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tasks_project ON tasks(project_id)");
            // evolução do esquema (idempotente): tarefas pai/filha, marcos, orçamento
            s.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS parent_id BIGINT");
            s.execute("ALTER TABLE tasks ADD CONSTRAINT IF NOT EXISTS fk_tasks_parent FOREIGN KEY (parent_id) REFERENCES tasks(id) ON DELETE CASCADE");
            s.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS is_milestone BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS budget DECIMAL(14,2) DEFAULT 0 NOT NULL");
            s.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS priority VARCHAR(10) DEFAULT 'MEDIA' NOT NULL");
            s.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS company_goal BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("CREATE TABLE IF NOT EXISTS finance_entries ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,"
                    + "kind VARCHAR(20) NOT NULL,"
                    + "number VARCHAR(60) NOT NULL DEFAULT '',"
                    + "party VARCHAR(160) NOT NULL DEFAULT '',"
                    + "description VARCHAR(500) NOT NULL DEFAULT '',"
                    + "issue_date DATE NOT NULL,"
                    + "due_date DATE,"
                    + "amount DECIMAL(14,2) NOT NULL,"
                    + "status VARCHAR(10) NOT NULL DEFAULT 'PENDENTE',"
                    + "paid_date DATE,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_fin_project ON finance_entries(project_id)");
            s.execute("CREATE TABLE IF NOT EXISTS documents ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,"
                    + "title VARCHAR(160) NOT NULL,"
                    + "category VARCHAR(20) NOT NULL DEFAULT 'OUTRO',"
                    + "description VARCHAR(500) NOT NULL DEFAULT '',"
                    + "file_name VARCHAR(255) NOT NULL,"
                    + "stored_name VARCHAR(60) NOT NULL,"
                    + "size BIGINT NOT NULL,"
                    + "uploaded_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "uploaded_by_id BIGINT,"
                    + "uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_doc_project ON documents(project_id)");
            // ---- Projetos Spot (ágeis, por horas) ----
            s.execute("CREATE TABLE IF NOT EXISTS spot_projects ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "name VARCHAR(160) NOT NULL,"
                    + "client VARCHAR(160) NOT NULL DEFAULT '',"
                    + "provider VARCHAR(160) NOT NULL DEFAULT '',"
                    + "description VARCHAR(2000) NOT NULL DEFAULT '',"
                    + "hours_model VARCHAR(10) NOT NULL DEFAULT 'MENSAL',"
                    + "contracted_hours DECIMAL(8,2) NOT NULL DEFAULT 0,"
                    + "start_date DATE NOT NULL,"
                    + "status VARCHAR(12) NOT NULL DEFAULT 'ATIVO',"
                    + "participants VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "recipients VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "billing_note VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("ALTER TABLE spot_projects ADD COLUMN IF NOT EXISTS end_date DATE");
            s.execute("ALTER TABLE spot_projects ADD COLUMN IF NOT EXISTS recurring BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE spot_projects ADD COLUMN IF NOT EXISTS closed_scope BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("CREATE TABLE IF NOT EXISTS spot_files ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL REFERENCES spot_projects(id) ON DELETE CASCADE,"
                    + "title VARCHAR(160) NOT NULL,"
                    + "description VARCHAR(500) NOT NULL DEFAULT '',"
                    + "file_name VARCHAR(200) NOT NULL,"
                    + "size BIGINT NOT NULL,"
                    + "content BLOB NOT NULL,"
                    + "uploaded_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_spot_files ON spot_files(spot_id)");
            s.execute("ALTER TABLE spot_projects ADD COLUMN IF NOT EXISTS project_value DECIMAL(14,2) DEFAULT 0 NOT NULL");
            s.execute("CREATE TABLE IF NOT EXISTS spot_invoices ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL REFERENCES spot_projects(id) ON DELETE CASCADE,"
                    + "number VARCHAR(60) NOT NULL,"
                    + "issue_date DATE NOT NULL,"
                    + "due_date DATE,"
                    + "amount DECIMAL(14,2) NOT NULL,"
                    + "status VARCHAR(10) NOT NULL DEFAULT 'EMITIDA',"
                    + "description VARCHAR(500) NOT NULL DEFAULT '',"
                    + "file_name VARCHAR(200) NOT NULL DEFAULT '',"
                    + "file_size BIGINT NOT NULL DEFAULT 0,"
                    + "file_content BLOB,"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_spot_invoices ON spot_invoices(spot_id, issue_date)");
            s.execute("CREATE TABLE IF NOT EXISTS spot_demands ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL REFERENCES spot_projects(id) ON DELETE CASCADE,"
                    + "name VARCHAR(200) NOT NULL,"
                    + "status VARCHAR(20) NOT NULL DEFAULT 'EM_ANALISE',"
                    + "next_step VARCHAR(500) NOT NULL DEFAULT '',"
                    + "approved_hours DECIMAL(8,2) NOT NULL DEFAULT 0,"
                    + "notes VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS spot_hours ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL REFERENCES spot_projects(id) ON DELETE CASCADE,"
                    + "work_date DATE NOT NULL,"
                    + "front VARCHAR(80) NOT NULL DEFAULT '',"
                    + "activity VARCHAR(300) NOT NULL,"
                    + "demand_id BIGINT REFERENCES spot_demands(id) ON DELETE SET NULL,"
                    + "hours DECIMAL(6,2) NOT NULL,"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_spot_hours ON spot_hours(spot_id, work_date)");
            s.execute("CREATE TABLE IF NOT EXISTS spot_actions ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL REFERENCES spot_projects(id) ON DELETE CASCADE,"
                    + "kind VARCHAR(10) NOT NULL DEFAULT 'ACAO',"
                    + "description VARCHAR(500) NOT NULL,"
                    + "responsible VARCHAR(120) NOT NULL DEFAULT '',"
                    + "status VARCHAR(12) NOT NULL DEFAULT 'PENDENTE',"
                    + "note VARCHAR(500) NOT NULL DEFAULT '',"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS spot_audit ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL,"
                    + "spot_name VARCHAR(200) NOT NULL DEFAULT '',"
                    + "user_name VARCHAR(120) NOT NULL DEFAULT '',"
                    + "action VARCHAR(200) NOT NULL,"
                    + "justification VARCHAR(1000) NOT NULL,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_spot_audit ON spot_audit(spot_id, created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS spot_reports ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "spot_id BIGINT NOT NULL REFERENCES spot_projects(id) ON DELETE CASCADE,"
                    + "title VARCHAR(200) NOT NULL,"
                    + "ref_date DATE NOT NULL,"
                    + "snapshot VARCHAR(1000000) NOT NULL,"
                    + "narrative VARCHAR(200000) NOT NULL,"
                    + "model VARCHAR(80) NOT NULL DEFAULT '',"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "sent_at TIMESTAMP,"
                    + "sent_to VARCHAR(1000),"
                    + "sent_by VARCHAR(120))");
            s.execute("CREATE TABLE IF NOT EXISTS ai_settings ("
                    + "id INT PRIMARY KEY,"
                    + "api_key_enc VARCHAR(2000) NOT NULL,"
                    + "key_hint VARCHAR(60) NOT NULL,"
                    + "model VARCHAR(80) NOT NULL,"
                    + "workspace_id VARCHAR(80) NOT NULL DEFAULT '',"
                    + "connected_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "connected_by_id BIGINT,"
                    + "connected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "last_test_at TIMESTAMP,"
                    + "last_test_ok BOOLEAN,"
                    + "last_test_msg VARCHAR(300))");
            s.execute("CREATE TABLE IF NOT EXISTS email_settings ("
                    + "id INT PRIMARY KEY,"
                    + "host VARCHAR(200) NOT NULL,"
                    + "port INT NOT NULL DEFAULT 587,"
                    + "security VARCHAR(10) NOT NULL DEFAULT 'starttls',"
                    + "username VARCHAR(200) NOT NULL DEFAULT '',"
                    + "password_enc VARCHAR(2000) NOT NULL DEFAULT '',"
                    + "from_email VARCHAR(200) NOT NULL DEFAULT '',"
                    + "from_name VARCHAR(120) NOT NULL DEFAULT 'SysAttack',"
                    + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "updated_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "last_test_at TIMESTAMP,"
                    + "last_test_ok BOOLEAN,"
                    + "last_test_msg VARCHAR(300))");
            s.execute("CREATE TABLE IF NOT EXISTS email_rules ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE,"
                    + "event VARCHAR(30) NOT NULL,"
                    + "recipients VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "include_responsible BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "subject VARCHAR(200) NOT NULL DEFAULT '',"
                    + "message VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS email_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "rule_id BIGINT,"
                    + "project_id BIGINT,"
                    + "event VARCHAR(30) NOT NULL,"
                    + "ref_key VARCHAR(80) NOT NULL DEFAULT '',"
                    + "recipients VARCHAR(1000) NOT NULL DEFAULT '',"
                    + "subject VARCHAR(300) NOT NULL DEFAULT '',"
                    + "status VARCHAR(10) NOT NULL,"
                    + "error VARCHAR(300) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_email_log_rule ON email_log(rule_id, ref_key)");
            s.execute("CREATE TABLE IF NOT EXISTS email_test_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "recipient VARCHAR(200) NOT NULL DEFAULT '',"
                    + "ok BOOLEAN NOT NULL,"
                    + "summary VARCHAR(500) NOT NULL DEFAULT '',"
                    + "file_name VARCHAR(120) NOT NULL DEFAULT '',"
                    + "log VARCHAR(500000) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS project_changes ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,"
                    + "title VARCHAR(200) NOT NULL,"
                    + "reason VARCHAR(2000) NOT NULL DEFAULT '',"
                    + "requested_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "change_date DATE NOT NULL,"
                    + "impact_days INT NOT NULL DEFAULT 0,"
                    + "impact_cost DECIMAL(14,2) NOT NULL DEFAULT 0,"
                    + "status VARCHAR(12) NOT NULL DEFAULT 'PENDENTE',"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_changes_project ON project_changes(project_id)");
            s.execute("CREATE TABLE IF NOT EXISTS ishikawa ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,"
                    + "problem VARCHAR(300) NOT NULL,"
                    + "created_by VARCHAR(120) NOT NULL DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_ishikawa_project ON ishikawa(project_id)");
            s.execute("CREATE TABLE IF NOT EXISTS ishikawa_causes ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ishikawa_id BIGINT NOT NULL REFERENCES ishikawa(id) ON DELETE CASCADE,"
                    + "category VARCHAR(20) NOT NULL,"
                    + "text VARCHAR(200) NOT NULL,"
                    + "parent_id BIGINT REFERENCES ishikawa_causes(id) ON DELETE CASCADE)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_ishikawa_causes ON ishikawa_causes(ishikawa_id)");
            s.execute("CREATE TABLE IF NOT EXISTS app_meta (k VARCHAR(60) PRIMARY KEY, v VARCHAR(400) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS audit_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                    + "actor VARCHAR(120) NOT NULL DEFAULT '',"
                    + "action VARCHAR(40) NOT NULL,"
                    + "detail VARCHAR(400) NOT NULL DEFAULT '',"
                    + "ip VARCHAR(60) NOT NULL DEFAULT '',"
                    + "ok BOOLEAN DEFAULT TRUE NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_log(at)");
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao iniciar o banco de dados", e);
        }
        try {
            // versão anterior criava um usuário "admin" com senha fixa: removido (o administrador é criado no primeiro acesso)
            update("DELETE FROM users WHERE username NOT LIKE '%@%'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        seed();
        try {
            Map<String, Object> id = one("SELECT v FROM app_meta WHERE k = 'data_id'");
            if (id == null) {
                dataId = java.util.UUID.randomUUID().toString();
                update("INSERT INTO app_meta(k, v) VALUES('data_id', ?)", dataId);
            } else {
                dataId = (String) id.get("v");
            }
            h2Version = String.valueOf(one("SELECT H2VERSION() v").get("v"));
            if (one("SELECT id FROM users LIMIT 1") != null) markInitialized(); // instalações antigas
            Files.write(new File(dataDir, "schema.version").toPath(), String.valueOf(SCHEMA_VERSION).getBytes(StandardCharsets.UTF_8));
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Falha ao registrar os metadados do banco", e);
        }
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("SET WRITE_DELAY 0"); // grava no disco a cada confirmação (sem janela de perda em queda de energia)
        } catch (SQLException ignored) {
            // configuração opcional
        }
    }

    /** Antes de migrar o esquema numa versão nova, guarda uma cópia do arquivo do banco (ainda fechado = consistente). */
    private static void preUpdateCopy() {
        try {
            File mv = new File(dataDir, "sysattack.mv.db");
            if (!mv.isFile()) return;
            File ver = new File(dataDir, "schema.version");
            String cur = ver.isFile() ? new String(Files.readAllBytes(ver.toPath()), StandardCharsets.UTF_8).trim() : "";
            if (cur.equals(String.valueOf(SCHEMA_VERSION))) return;
            File bd = BackupService.backupDir();
            bd.mkdirs();
            File copy = new File(bd, "pre-update-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-v" + (cur.isEmpty() ? "0" : cur) + ".mv.db");
            Files.copy(mv.toPath(), copy.toPath());
            File[] old = bd.listFiles((d, n) -> n.startsWith("pre-update-") && n.endsWith(".mv.db"));
            if (old != null && old.length > 5) {
                java.util.Arrays.sort(old);
                for (int i = 0; i < old.length - 5; i++) old[i].delete();
            }
            java.util.logging.Logger.getLogger("sysattack").info("Cópia antes da atualização: " + copy);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("sysattack").warning("Não foi possível copiar o banco antes da atualização: " + e);
        }
    }

    /** Marca que o administrador já foi criado: depois disso o "primeiro acesso" não é mais aceito, mesmo com o banco vazio. */
    public static void markInitialized() {
        try {
            File f = new File(dataDir, ".initialized");
            if (!f.exists()) Files.write(f.toPath(), java.time.LocalDateTime.now().toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            java.util.logging.Logger.getLogger("sysattack").warning("Não foi possível gravar o marcador de instalação: " + e);
        }
    }

    public static boolean wasInitialized() {
        return new File(dataDir, ".initialized").isFile();
    }

    /** Só o dono lê o arquivo/pasta (POSIX; no Windows herda as permissões do local — veja o README). */
    static void restrict(File f) {
        try {
            String perm = f.isDirectory() ? "rwx------" : "rw-------";
            Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString(perm));
        } catch (Exception ignored) {
            // sistema de arquivos sem POSIX
        }
    }

    public static String dataSource() { return dataSource; }
    public static String dataId() { return dataId; }
    public static String h2Version() { return h2Version; }

    public static String dataDir() {
        return dataDir;
    }

    public static synchronized void shutdown() {
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // já encerrado
        }
    }

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    // ---------- helpers ----------

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof LocalDate) a = Date.valueOf((LocalDate) a);
            ps.setObject(i + 1, a);
        }
    }

    // ---------- transação: tudo ou nada ----------
    private static final ThreadLocal<Connection> TX = new ThreadLocal<>();

    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    private static final ThreadLocal<List<Runnable>> AFTER_COMMIT = new ThreadLocal<>(), AFTER_ROLLBACK = new ThreadLocal<>();

    /** Executa depois da confirmação da transação (ou já, se não houver transação). Para e-mails e exclusão de arquivos. */
    public static void afterCommit(Runnable r) {
        if (TX.get() == null) { r.run(); return; }
        if (AFTER_COMMIT.get() == null) AFTER_COMMIT.set(new ArrayList<>());
        AFTER_COMMIT.get().add(r);
    }

    /** Executa se a transação for desfeita (ex.: apagar um arquivo gravado antes do erro). */
    public static void onRollback(Runnable r) {
        if (TX.get() == null) return;
        if (AFTER_ROLLBACK.get() == null) AFTER_ROLLBACK.set(new ArrayList<>());
        AFTER_ROLLBACK.get().add(r);
    }

    private static void runAll(ThreadLocal<List<Runnable>> tl) {
        List<Runnable> l = tl.get();
        tl.remove();
        if (l != null) for (Runnable r : l) {
            try { r.run(); } catch (RuntimeException e) { java.util.logging.Logger.getLogger("sysattack").warning("Tarefa pós-transação falhou: " + e); }
        }
    }

    /** Executa o trabalho numa única transação: se algo falhar, nada é gravado. Chamadas aninhadas reaproveitam a transação. */
    public static <T> T tx(Work<T> work) throws Exception {
        if (TX.get() != null) return work.run();
        try (Connection c = get()) {
            c.setAutoCommit(false);
            TX.set(c);
            try {
                T r = work.run();
                c.commit();
                TX.remove();
                AFTER_ROLLBACK.remove();
                runAll(AFTER_COMMIT);
                return r;
            } catch (Exception | Error e) {
                try { c.rollback(); } catch (SQLException ignored) { /* conexão já inválida */ }
                TX.remove();
                AFTER_COMMIT.remove();
                runAll(AFTER_ROLLBACK);
                throw e;
            } finally {
                TX.remove();
            }
        }
    }

    /** Conexão da transação em curso (não é fechada pelo chamador) ou uma nova. */
    private static Connection acquire() throws SQLException {
        Connection t = TX.get();
        return t != null ? t : get();
    }

    private static void release(Connection c) throws SQLException {
        if (c != TX.get()) c.close();
    }

    public static List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object v = rs.getObject(i);
                        if (v != null && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String)) {
                            v = v.toString(); // datas -> "yyyy-MM-dd"
                        }
                        row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), v);
                    }
                    rows.add(row);
                }
                return rows;
            }
        } finally {
            release(c);
        }
    }

    public static Map<String, Object> one(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(String sql, Object... args) throws SQLException {
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } finally {
            release(c);
        }
    }

    public static long insert(String sql, Object... args) throws SQLException {
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        } finally {
            release(c);
        }
    }

    // ---------- dados iniciais ----------

    private static void seed() {
        try {
            // Os projetos de exemplo entram UMA única vez, só em banco novo (sem projetos e sem usuários). Antes, a regra era
            // "tabela de projetos vazia", então quem apagava todos os projetos os via voltar a cada reinício do Tomcat.
            if (one("SELECT v FROM app_meta WHERE k = 'seeded'") != null) return;
            boolean fresh = one("SELECT id FROM projects LIMIT 1") == null && one("SELECT id FROM users LIMIT 1") == null;
            update("INSERT INTO app_meta(k, v) VALUES('seeded', ?)", fresh ? "demo" : "no");
            if (!fresh) return;
            LocalDate t = LocalDate.now();

            long p1 = insert("INSERT INTO projects(name,scope,owner,status,start_date,end_date,budget) VALUES(?,?,?,?,?,?,?)",
                    "Implantação da linha de dosagem",
                    "Instalar, comissionar e entregar a nova linha de dosagem automática no cliente, "
                  + "incluindo projeto, compra de componentes, montagem em campo, testes e treinamento da equipe.\n\n"
                  + "Fora do escopo: adequações civis e obras de infraestrutura do cliente.",
                    "Adilson", "EM_ANDAMENTO", t.minusDays(20), t.plusDays(75), new java.math.BigDecimal("180000.00"));
            long eng = task(p1, "Engenharia", "Projeto e aprovação.", "Letícia", t.minusDays(20), t.plusDays(3), 0, "A_FAZER", null, null, false);
            long levant = task(p1, "Levantamento de requisitos", "Reuniões e visita técnica.", "Adilson", t.minusDays(20), t.minusDays(10), 100, "CONCLUIDA", null, eng, false);
            task(p1, "Projeto elétrico e mecânico", "Desenhos, lista de materiais e aprovação.", "Letícia", t.minusDays(9), t.minusDays(1), 70, "EM_ANDAMENTO", levant, eng, false);
            task(p1, "Projeto aprovado pelo cliente", "", "Adilson", t.plusDays(3), t.plusDays(3), 0, "A_FAZER", null, eng, true);
            long sup = task(p1, "Suprimentos", "Compras e recebimento.", "Saimon", t.plusDays(4), t.plusDays(25), 0, "A_FAZER", null, null, false);
            long compra = task(p1, "Compra de componentes", "Cotação e pedidos aos fornecedores.", "Saimon", t.plusDays(4), t.plusDays(18), 0, "A_FAZER", null, sup, false);
            task(p1, "Recebimento de materiais", "", "Saimon", t.plusDays(19), t.plusDays(25), 0, "A_FAZER", compra, sup, false);
            long exe = task(p1, "Execução", "Montagem e testes.", "Adilson", t.plusDays(26), t.plusDays(60), 0, "A_FAZER", null, null, false);
            long mont = task(p1, "Montagem em campo", "", "Adilson", t.plusDays(26), t.plusDays(45), 0, "A_FAZER", null, exe, false);
            task(p1, "Comissionamento e testes", "", "Letícia", t.plusDays(46), t.plusDays(60), 0, "A_FAZER", mont, exe, false);
            task(p1, "GO LIVE · entrega ao cliente", "", "Adilson", t.plusDays(61), t.plusDays(61), 0, "A_FAZER", null, null, true);
            task(p1, "Treinamento da equipe", "", "Saimon", t.plusDays(62), t.plusDays(75), 0, "A_FAZER", null, null, false);
            for (long p : new long[] {eng, sup, exe}) TaskRules.rollup(p);

            fin(p1, "NF_SERVICO", "NF 1523", "Eletro Projetos Ltda", "Projeto elétrico", t.minusDays(12), t.minusDays(2), "18500.00", "PAGO", t.minusDays(3));
            fin(p1, "FATURA", "FAT-0092", "Locadora de Guindastes", "Locação de guindaste (montagem)", t.minusDays(1), t.plusDays(10), "7200.00", "PENDENTE", null);
            fin(p1, "NF_SERVICO", "NF 1601", "Consultoria PLC", "Programação do CLP", t.minusDays(8), t.minusDays(3), "12000.00", "PENDENTE", null);

            long p2 = insert("INSERT INTO projects(name,scope,owner,status,start_date,end_date,budget) VALUES(?,?,?,?,?,?,?)",
                    "Migração do portal de compras",
                    "Migrar o portal atual para a nova plataforma, mantendo o histórico de solicitações.",
                    "Letícia", "PLANEJAMENTO", t.plusDays(5), t.plusDays(60), new java.math.BigDecimal("45000.00"));
            long f = task(p2, "Mapeamento de fluxos", "", "Letícia", t.plusDays(5), t.plusDays(15), 0, "A_FAZER", null, null, false);
            long g = task(p2, "Prototipação das telas", "", "Saimon", t.plusDays(16), t.plusDays(30), 0, "A_FAZER", f, null, false);
            long h = task(p2, "Desenvolvimento", "", "Adilson", t.plusDays(31), t.plusDays(52), 0, "A_FAZER", g, null, false);
            long hom = task(p2, "Homologação", "", "Letícia", t.plusDays(53), t.plusDays(58), 0, "A_FAZER", h, null, false);
            task(p2, "Portal em produção", "", "Letícia", t.plusDays(60), t.plusDays(60), 0, "A_FAZER", hom, null, true);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao criar dados iniciais", e);
        }
    }

    private static long task(long project, String name, String desc, String who, LocalDate s, LocalDate e,
                             int progress, String status, Long dep, Long parent, boolean milestone) throws SQLException {
        return insert("INSERT INTO tasks(project_id,name,description,responsible,start_date,end_date,progress,status,depends_on,parent_id,is_milestone)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)", project, name, desc, who, s, e, progress, status, dep, parent, milestone);
    }

    private static void fin(long project, String kind, String number, String party, String desc, LocalDate issue, LocalDate due,
                            String amount, String status, LocalDate paid) throws SQLException {
        insert("INSERT INTO finance_entries(project_id,kind,number,party,description,issue_date,due_date,amount,status,paid_date)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)", project, kind, number, party, desc, issue, due, new java.math.BigDecimal(amount), status, paid);
    }
}
