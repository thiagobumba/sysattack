package br.com.sysattack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Configuração em arquivo: valores padrão embutidos no .war (WEB-INF/classes/config.properties, sem segredos, como o
 * app.url) e, por cima, o config.properties da pasta de dados, que tem prioridade e é o lugar certo para senhas.
 * Lido a cada chamada, então alterar o arquivo da pasta de dados vale sem reiniciar o Tomcat.
 */
final class AppConfig {
    private AppConfig() {}

    private static final Logger LOG = Logger.getLogger("sysattack");

    static Properties load() {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) { p.load(r); }
        } catch (IOException e) {
            LOG.warning("Não foi possível ler o config.properties embutido: " + e.getMessage());
        }
        File f = new File(Db.dataDir(), "config.properties");
        if (f.isFile()) {
            Properties o = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                o.load(r);
                p.putAll(o);
            } catch (IOException e) {
                LOG.warning("Não foi possível ler " + f + ": " + e.getMessage());
            }
        }
        return p;
    }
}
