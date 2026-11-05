package br.com.sysattack;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class AppListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent e) {
        Db.init();
        e.getServletContext().log("SysAttack: banco de dados iniciado.");
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        Db.shutdown();
    }
}
