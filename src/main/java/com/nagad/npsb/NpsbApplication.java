package com.nagad.npsb;

import com.nagad.npsb.cli.TestMailServer;
import com.nagad.npsb.util.AppLog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NPSB Dispute Email Automation - backend (Spring Boot).
 *
 *   java -jar npsb-backend.jar                              start the API (127.0.0.1:8000; HOST / PORT in .env)
 *   java -jar npsb-backend.jar --host 0.0.0.0 --port 8000   ... on another address / port
 *   java -jar npsb-backend.jar recover ...                  recovery when mail is broken (see cli.RecoverCommand)
 *   java -jar npsb-backend.jar test-mail-server             fake SMTP server; saves emails to sent_emails/
 *   java -jar npsb-backend.jar export-log [folder]          the application log as CSV and JSON
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NpsbApplication {

    public static void main(String[] args) throws Exception {
        System.setProperty("mail.mime.decodefilename", "true");
        System.setProperty("mail.mime.decodeparameters", "true");
        System.setProperty("mail.mime.address.strict", "false");
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        if (args.length > 0 && args[0].equals("test-mail-server")) {
            TestMailServer.main(rest);
            return;
        }
        if (args.length > 0 && args[0].equals("export-log")) {
            AppLog.main(rest);
            return;
        }
        if (args.length > 0 && args[0].equals("recover")) {
            // no web server and no background jobs: just the database, the settings and the recovery command
            ConfigurableApplicationContext context = new SpringApplicationBuilder(NpsbApplication.class)
                    .web(WebApplicationType.NONE)
                    // only the command's own output (and the application log's [AUTH] lines), no framework start-up logs
                    .properties("npsb.command=recover", "spring.main.log-startup-info=false",
                            "logging.level.root=WARN", "logging.level.npsb=INFO")
                    .run(rest);
            System.exit(SpringApplication.exit(context));
            return;
        }
        SpringApplication.run(NpsbApplication.class, springArgs(args));
    }

    /** "--port 8000" / "--host 0.0.0.0" (as uvicorn took them) become Spring's --server.port=8000 / --server.address=... */
    static String[] springArgs(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) out.add("--server.port=" + args[++i]);
            else if (args[i].equals("--host") && i + 1 < args.length) out.add("--server.address=" + args[++i]);
            else out.add(args[i]);
        }
        return out.toArray(new String[0]);
    }
}
