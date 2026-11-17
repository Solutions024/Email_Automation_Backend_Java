package com.nagad.npsb.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ServerSettings.Field compares, hashes and prints its options by content, not by array identity. */
class ServerSettingsFieldTest {

    private static ServerSettings.Field provider(String[][] options) {
        return new ServerSettings.Field("MAIL_PROVIDER", "Email system", "choice", "provider", "help", null, null, true, options);
    }

    @Test
    void sameOptionsInDifferentArraysAreEqual() {
        ServerSettings.Field a = provider(new String[][]{{"gmail", "Gmail"}, {"smtp", "Mail server"}});
        ServerSettings.Field b = provider(new String[][]{{"gmail", "Gmail"}, {"smtp", "Mail server"}});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(1, new HashSet<>(List.of(a, b)).size());  // one entry in a set or map key
    }

    @Test
    void differencesAreNoticed() {
        ServerSettings.Field a = provider(new String[][]{{"gmail", "Gmail"}, {"smtp", "Mail server"}});
        assertNotEquals(a, provider(new String[][]{{"gmail", "Gmail"}, {"smtp", "SMTP"}}));
        assertNotEquals(a, provider(new String[][]{{"gmail", "Gmail"}}));
        assertNotEquals(a, new ServerSettings.Field("MAIL_PROVIDER", "Email system", "choice", "provider", "help",
                null, null, false, new String[][]{{"gmail", "Gmail"}, {"smtp", "Mail server"}}));
        assertNotEquals(a, new ServerSettings.Field("SMTP_PORT", "Port", "int", "smtp", "", 1, 65535));
        assertNotEquals(a, null);
        assertEquals(new ServerSettings.Field("SMTP_PORT", "Port", "int", "smtp", "", 1, 65535),
                new ServerSettings.Field("SMTP_PORT", "Port", "int", "smtp", "", 1, 65535));
    }

    @Test
    void printsTheOptions() {
        String text = provider(new String[][]{{"gmail", "Gmail"}, {"smtp", "Mail server"}}).toString();
        assertTrue(text.contains("options=[[gmail, Gmail], [smtp, Mail server]]"), text);
        assertTrue(text.startsWith("Field[name=MAIL_PROVIDER, "), text);
    }
}
