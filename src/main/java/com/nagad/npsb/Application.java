package com.nagad.npsb;

import com.nagad.npsb.api.Answers;
import com.nagad.npsb.api.Auth;
import com.nagad.npsb.api.MainApi;
import com.nagad.npsb.api.Users;
import com.nagad.npsb.db.Database;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.service.Seed;
import com.nagad.npsb.service.ServerSettings;
import com.nagad.npsb.web.Server;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * NPSB Dispute Email Automation - backend.
 *
 *   java -jar npsb-dispute-backend.jar                      start the API (port 8000; HOST / PORT in .env)
 *   java -jar npsb-dispute-backend.jar recover ...          recovery when mail is broken (see Recover)
 *   java -jar npsb-dispute-backend.jar test-mail-server     fake SMTP server; saves emails to sent_emails/
 */
public final class Application {
    private Application() {}

    private static final Logger LOG = Logger.getLogger("npsb");

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s: %5$s%6$s%n");
        System.setProperty("mail.mime.decodefilename", "true");
        System.setProperty("mail.mime.decodeparameters", "true");
        System.setProperty("mail.mime.address.strict", "false");
        if (args.length > 0 && args[0].equals("recover")) {
            AppLog.init();  // recovery codes and mail resets are written to the application log too
            Recover.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("test-mail-server")) {
            TestMailServer.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        int port = Config.PORT;
        String host = Config.HOST;
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
            if (args[i].equals("--host")) host = args[i + 1];
        }
        // Another backend already on this port: stop now, before the mailbox check and follow-up jobs start twice
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.bind(new java.net.InetSocketAddress(host, port));
        } catch (java.io.IOException e) {
            String text = "Port " + port + " on " + host + " is already in use: another backend is probably still running. "
                    + "Stop it first (or use --port), then start again.";
            Logger.getLogger("npsb").severe(text);  // console only: the running backend keeps the log file
            System.err.println("ERROR: " + text);
            System.exit(1);
        }
        startup();
        Server server = new Server();
        com.nagad.npsb.api.Health.routes(server);  // /startupz, /livez, /readyz for Kubernetes
        Auth.routes(server);
        Users.routes(server);
        MainApi.routes(server);
        Answers.routes(server);
        server.start(host, port);
        com.nagad.npsb.api.Health.markStarted();  // the startup probe passes from now on
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            com.nagad.npsb.api.Health.markStopping();  // readiness fails first, so no new traffic comes here
            MainApi.stopFollowups();
            Inbox.stop();
            server.stop();
            Database.close();
        }, "shutdown"));
        server.await();
    }

    /** Create the database and tables if needed, add the standard templates, apply System Settings, start the jobs. */
    static void startup() throws Exception {
        AppLog.init();                       // the application log file (APP_LOG_DIR, default backend/app/logs)
        Database.createDatabaseIfMissing();  // create the MySQL database if needed
        Database.createTables();             // create tables if they don't exist, and bring old ones up to date
        if (Config.JWT_SECRET_IS_RANDOM) {
            System.out.println("WARNING: JWT_SECRET is not set in .env - everyone is signed out when the server restarts.");
        }
        try (Db db = Db.open()) {
            Seed.seedTemplates(db);          // the standard email templates
            ServerSettings.load(db);         // settings changed on the System Settings page
            com.nagad.npsb.api.Auth.ensureBuiltInAdmin(db);  // the one admin that creates every account
            com.nagad.npsb.service.CaseIds.backfill(db);      // case IDs for emails made before they existed
        }
        Thread followups = new Thread(MainApi::autoFollowupLoop, "auto-followups");
        followups.setDaemon(true);
        followups.start();
        Thread inbox = new Thread(Inbox::loop, "inbox-scan");
        inbox.setDaemon(true);
        inbox.start();
        LOG.info("NPSB Dispute Email Automation started");
    }
}
