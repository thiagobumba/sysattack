package br.com.sysattack;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class AppListener implements ServletContextListener {
    private ScheduledExecutorService scheduler;

    @Override
    public void contextInitialized(ServletContextEvent e) {
        Db.init();
        // verificação de tarefas atrasadas (gatilho TASK_LATE): a cada 30 min; o envio é uma vez por regra e tarefa
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sysattack-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(EmailTriggers::scanLate, 2, 30, TimeUnit.MINUTES);
        e.getServletContext().log("SysAttack: banco de dados iniciado.");
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        if (scheduler != null) scheduler.shutdownNow();
        Db.shutdown();
    }
}
