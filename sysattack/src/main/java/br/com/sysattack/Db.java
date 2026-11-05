package br.com.sysattack;

import java.io.File;
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

    private Db() {}

    public static synchronized void init() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        // Pasta dos dados: -Dsysattack.data=/caminho  (padrão: ~/.sysattack)
        String dir = System.getProperty("sysattack.data",
                System.getProperty("user.home") + File.separator + ".sysattack");
        new File(dir).mkdirs();
        dataDir = dir;
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
    }

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

    public static List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (Connection c = get(); PreparedStatement ps = c.prepareStatement(sql)) {
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
        }
    }

    public static Map<String, Object> one(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(String sql, Object... args) throws SQLException {
        try (Connection c = get(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    public static long insert(String sql, Object... args) throws SQLException {
        try (Connection c = get(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    // ---------- dados iniciais ----------

    private static void seed() {
        try {
            if (one("SELECT id FROM projects LIMIT 1") != null) return;
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
