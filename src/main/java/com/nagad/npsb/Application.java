package com.nagad.npsb;

import com.nagad.npsb.api.Answers;
import com.nagad.npsb.api.Auth;
import com.nagad.npsb.api.MainApi;
import com.nagad.npsb.api.Users;
import com.nagad.npsb.db.Database;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.service.Forwarding;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.service.Seed;
import com.nagad.npsb.service.ServerSettings;
import com.nagad.npsb.web.Server;

import java.util.logging.Logger;

/**
 * NPSB Dispute Email Automation - backend.
 *
 *   java -jar npsb-dispute-backend.jar                      start the API (port 8000; HOST / PORT in .env)
 */
public final class Application {
    private Application() {}

    private static final Logger LOG = Logger.getLogger("npsb");

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s: %5$s%6$s%n");
        System.setProperty("mail.mime.decodefilename", "true");
        System.setProperty("mail.mime.decodeparameters", "true");
        System.setProperty("mail.mime.address.strict", "false");
        int port = Config.PORT;
        String host = Config.HOST;
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
            if (args[i].equals("--host")) host = args[i + 1];
        }
        startup();
        Server server = new Server();
        Auth.routes(server);
        Users.routes(server);
        MainApi.routes(server);
        Answers.routes(server);
        server.start(host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MainApi.stopFollowups();
            Inbox.stop();
            server.stop();
            Database.close();
        }, "shutdown"));
        server.await();
    }

    /** Create the database and tables if needed, add the standard templates, apply System Settings, start the jobs. */
    static void startup() throws Exception {
        Database.createDatabaseIfMissing();  // create the MySQL database if needed
        Database.createTables();             // create tables if they don't exist, and bring old ones up to date
        if (Config.JWT_SECRET_IS_RANDOM) {
            System.out.println("WARNING: JWT_SECRET is not set in .env - everyone is signed out when the server restarts.");
        }
        try (Db db = Db.open()) {
            Seed.seedTemplates(db);          // the standard email templates
            ServerSettings.load(db);         // settings changed on the System Settings page
        }
        Thread followups = new Thread(MainApi::autoFollowupLoop, "auto-followups");
        followups.setDaemon(true);
        followups.start();
        Thread inbox = new Thread(Inbox::loop, "inbox-scan");
        inbox.setDaemon(true);
        inbox.start();
        Forwarding.kick();  // replies still waiting to be forwarded when the server stopped
        LOG.info("NPSB Dispute Email Automation started");
    }
}
