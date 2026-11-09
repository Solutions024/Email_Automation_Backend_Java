package com.nagad.npsb.config;

import com.nagad.npsb.service.AuthService;
import com.nagad.npsb.service.CaseIds;
import com.nagad.npsb.service.FollowupService;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.service.Seed;
import com.nagad.npsb.service.ServerSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Once the web server is up: add the standard email templates, apply the System Settings saved in the database,
 * make sure the built-in admin exists, then start the background jobs (automatic follow-ups, mailbox check).
 * The startup probe (/startupz) answers 200 after this (Spring Boot then publishes ACCEPTING_TRAFFIC).
 * The tables were already created / brought up to date with the connection pool (DataSourceConfig).
 */
@Component
@ConditionalOnWebApplication
public class StartupTasks implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    private final NpsbProperties properties;
    private final Seed seed;
    private final ServerSettings serverSettings;
    private final AuthService authService;
    private final FollowupService followups;
    private final Inbox inbox;
    private final CaseIds caseIds;
    /** npsb.jobs.enabled=false: no background jobs (the API tests start the backend that way). */
    private final boolean jobsEnabled;

    public StartupTasks(NpsbProperties properties, Seed seed, ServerSettings serverSettings, AuthService authService,
                        FollowupService followups, Inbox inbox, CaseIds caseIds, @Value("${npsb.jobs.enabled:true}") boolean jobsEnabled) {
        this.jobsEnabled = jobsEnabled;
        this.properties = properties;
        this.seed = seed;
        this.serverSettings = serverSettings;
        this.authService = authService;
        this.followups = followups;
        this.inbox = inbox;
        this.caseIds = caseIds;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("Application log: {}", properties.getAppLogDir());
        if (properties.jwtSecretIsRandom()) {
            LOG.warn("JWT_SECRET is not set in .env - everyone is signed out when the server restarts.");
        }
        seed.seedTemplates();               // the standard email templates
        serverSettings.load();              // settings changed on the System Settings page
        authService.ensureBuiltInAdmin();   // the one admin that creates every account
        caseIds.backfill();                 // case IDs for emails made before they existed
        if (jobsEnabled) {
            followups.start();              // automatic follow-ups
            inbox.start();                  // partner replies from the mailbox
        }
        LOG.info("NPSB Dispute Email Automation started");
    }
}
