package com.nagad.npsb.api;

import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.ChangeRequest;
import com.nagad.npsb.models.EmailVerificationCode;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.Approvals;
import com.nagad.npsb.service.ExcelParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nagad.npsb.api.ApiTestServer.send;
import static com.nagad.npsb.api.ApiTestServer.tokenFor;
import static com.nagad.npsb.api.ApiTestServer.upload;
import static org.junit.jupiter.api.Assertions.*;

/** The backend's HTTP API end to end, against an in-memory database. */
class ApiIntegrationTest {

    static String admin;   // built-in admin
    static String other;   // another admin (not built-in)
    static String user;    // a normal user

    @BeforeAll
    static void accounts() throws Exception {
        ApiTestServer.url();
        admin = tokenFor("admin");
        createAccount("other.admin", "other@nagad.com.bd", "admin");
        createAccount("plain.user", "user@nagad.com.bd", "user");
        other = tokenFor("other.admin");
        user = tokenFor("plain.user");
    }

    static void createAccount(String username, String email, String role) throws Exception {
        try (Db db = Db.open()) {
            if (db.first(User.class, "WHERE t.username = ?", username) != null) return;
        }
        ApiTestServer.Response r = send("POST", "/api/users", tokenFor("admin"), Map.of("full_name", "Test " + username,
                "username", username, "email", email, "password", "Str0ng!Passw0rd#", "role", role));
        assertEquals(201, r.status(), r.body());
    }

    // ---------------------------------------------------------------- health probes

    @Test
    void healthProbesAnswer200WhenStartedAndDatabaseUp() throws Exception {
        assertEquals(200, send("GET", "/livez", null, null).status());
        assertEquals(200, send("GET", "/startupz", null, null).status());
        ApiTestServer.Response ready = send("GET", "/readyz", null, null);
        assertEquals(200, ready.status(), ready.body());
        assertEquals("ok", ready.json().get("database"));
    }

    // ---------------------------------------------------------------- sign-in and accounts

    @Test
    void builtInAdminSignsInWithPasswordOnlyWhenItHasNoEmail() throws Exception {
        ApiTestServer.Response ok = send("POST", "/api/auth/login", null, Map.of("login", "admin", "password", "Admin@1234"));
        assertEquals(200, ok.status(), ok.body());
        assertNotNull(ok.json().get("access_token"));
        @SuppressWarnings("unchecked") Map<String, Object> u = (Map<String, Object>) ok.json().get("user");
        assertEquals(Boolean.TRUE, u.get("is_built_in"));
    }

    @Test
    void wrongPasswordIsRefused() throws Exception {
        assertEquals(401, send("POST", "/api/auth/login", null, Map.of("login", "plain.user", "password", "nope")).status());
        try (Db db = Db.open()) {  // don't leave the account half way to a lock-out for other tests
            db.execute("UPDATE users SET failed_login_count = 0 WHERE username = 'plain.user'");
            db.commit();
        }
    }

    @Test
    void thereIsNoSelfSignUp() throws Exception {
        ApiTestServer.Response r = send("POST", "/api/auth/register", null, Map.of("full_name", "Someone", "username", "someone",
                "email", "someone@nagad.com.bd", "password", "Str0ng!Passw0rd#", "role", "user"));
        assertEquals(403, r.status(), r.body());
        assertEquals(Boolean.FALSE, send("GET", "/api/auth/settings", null, null).json().get("allow_registration"));
    }

    @Test
    void apiNeedsASignIn() throws Exception {
        assertEquals(401, send("GET", "/api/partners", null, null).status());
        assertEquals(401, send("GET", "/api/partners", "not-a-token", null).status());
    }

    @Test
    void onlyTheBuiltInAdminManagesAccounts() throws Exception {
        assertEquals(200, send("GET", "/api/users", admin, null).status());
        assertEquals(403, send("GET", "/api/users", other, null).status());
        assertEquals(403, send("GET", "/api/users", user, null).status());
        assertEquals(403, send("POST", "/api/users", other, Map.of("full_name", "X Y", "username", "xy", "email", "xy@nagad.com.bd",
                "password", "Str0ng!Passw0rd#", "role", "user")).status());
    }

    @Test
    void builtInAdminCanSetAPasswordAndItWorks() throws Exception {
        createAccount("pw.target", "pw@nagad.com.bd", "user");
        int id;
        try (Db db = Db.open()) {
            id = db.first(User.class, "WHERE t.username = 'pw.target'").id;
        }
        ApiTestServer.Response r = send("PATCH", "/api/users/" + id, admin, Map.of("password", "N3w!Passw0rd#xyz"));
        assertEquals(200, r.status(), r.body());
        try (Db db = Db.open()) {
            User u = db.get(User.class, id);
            assertTrue(Auth.verifyPassword("N3w!Passw0rd#xyz", u.passwordHash));
        }
        assertEquals(400, send("PATCH", "/api/users/" + id, admin, Map.of("password", "weak")).status());
    }

    @Test
    void builtInAdminCannotBeDemotedOrDeactivated() throws Exception {
        int id;
        try (Db db = Db.open()) {
            id = db.first(User.class, "WHERE t.username = 'admin'").id;
        }
        assertEquals(400, send("PATCH", "/api/users/" + id, admin, Map.of("is_active", false)).status());
    }

    // ---------------------------------------------------------------- forgot password (emailed code)

    @Test
    void forgotPasswordCodeResetsThePassword() throws Exception {
        createAccount("forgetful", "forgetful@nagad.com.bd", "user");
        ApiTestServer.Response asked = send("POST", "/api/auth/forgot-password", null, Map.of("email", "forgetful@nagad.com.bd"));
        assertEquals(200, asked.status(), asked.body());
        // the same answer for an address without an account
        assertEquals(200, send("POST", "/api/auth/forgot-password", null, Map.of("email", "nobody@nagad.com.bd")).status());

        // the code is emailed (no mail server here): put a known code in its place
        try (Db db = Db.open()) {
            User u = db.first(User.class, "WHERE t.username = 'forgetful'");
            EmailVerificationCode c = db.first(EmailVerificationCode.class,
                    "WHERE t.user_id = ? AND t.purpose = 'reset' AND t.used_at IS NULL ORDER BY t.id DESC", u.id);
            assertNotNull(c, "a reset code is stored");
            c.codeHash = Auth.codeHash(u, "reset", "123456");
            db.commit();
        }
        Map<String, Object> wrong = Map.of("email", "forgetful@nagad.com.bd", "code", "000000", "new_password", "Br4nd!New#Pass");
        ApiTestServer.Response bad = send("POST", "/api/auth/reset-password", null, wrong);
        assertEquals(400, bad.status());
        assertTrue(bad.body().contains("attempt"), bad.body());

        Map<String, Object> right = Map.of("email", "forgetful@nagad.com.bd", "code", "123456", "new_password", "Br4nd!New#Pass");
        assertEquals(200, send("POST", "/api/auth/reset-password", null, right).status());
        try (Db db = Db.open()) {
            assertTrue(Auth.verifyPassword("Br4nd!New#Pass", db.first(User.class, "WHERE t.username = 'forgetful'").passwordHash));
        }
        // a code works once
        assertEquals(400, send("POST", "/api/auth/reset-password", null, right).status());
    }

    // ---------------------------------------------------------------- uploads

    @Test
    void sameRrnInALaterFileIsLeftOut() throws Exception {
        byte[] file = ExcelParser.buildTemplate();  // the sample file: valid example rows
        ApiTestServer.Response first = upload("/api/uploads", user, "first.xlsx", file);
        assertEquals(200, first.status(), first.body());
        int valid = ((Number) first.json().get("valid_rows")).intValue();
        assertTrue(valid > 0, "the sample file has valid rows");
        assertEquals(0, ((Number) first.json().get("duplicate_rows")).intValue());

        ApiTestServer.Response again = upload("/api/uploads", user, "second-copy.xlsx", file);
        assertEquals(200, again.status(), again.body());
        assertEquals(valid, ((Number) again.json().get("duplicate_rows")).intValue(), "every row was in the first file");
        @SuppressWarnings("unchecked") List<Map<String, Object>> dups = (List<Map<String, Object>>) again.json().get("duplicates");
        assertEquals("first.xlsx", dups.get(0).get("first_upload"));
    }

    @Test
    void onlyXlsxAndCsvAreAccepted() throws Exception {
        assertEquals(400, upload("/api/uploads", user, "evil.html", "<script>alert(1)</script>".getBytes()).status());
    }

    @Test
    void oversizedUploadIsRefusedWith413() throws Exception {
        byte[] big = new byte[26 * 1024 * 1024];
        ApiTestServer.Response r = upload("/api/uploads", user, "big.csv", big);
        assertEquals(413, r.status(), r.body());
    }

    // ---------------------------------------------------------------- approvals

    @Test
    void usersChangesWaitForApprovalButAdminsApplyAtOnce() throws Exception {
        // a user's new template variable waits (maker-checker is on by default)
        ApiTestServer.Response r = send("POST", "/api/template-variables", user,
                Map.of("name", "test_note", "description", "Test", "source", "text", "value", "hello"));
        assertEquals(201, r.status(), r.body());  // 201 Created
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) r.json().get("result");
        assertEquals(Boolean.TRUE, result.get("pending"));

        // the same kind of change made by an admin is applied straight away
        try (Db db = Db.open()) {
            User a = db.first(User.class, "WHERE t.username = 'other.admin'");
            Map<String, Object> applied = Approvals.submit(db, a, "variable", "create", null, "{{admin_note}}",
                    Map.of("name", "admin_note", "description", "By an admin", "source", "text", "value", "hi"));
            assertEquals(Boolean.TRUE, applied.get("applied"));
            ChangeRequest cr = db.get(ChangeRequest.class, applied.get("request_id"));
            assertEquals("applied", cr.status);
        }
    }

    @Test
    void anyAdminMayApproveEvenTheirOwnOlderRequest() throws Exception {
        int id;
        try (Db db = Db.open()) {  // an older request made by an admin, from before admins' changes applied at once
            ChangeRequest cr = new ChangeRequest();
            cr.entity = "variable";
            cr.action = "create";
            cr.summary = "{{old_admin_note}}";
            cr.after = "{\"aggregate\": null, \"column\": null, \"description\": \"Old\", \"name\": \"old_admin_note\", "
                    + "\"source\": \"text\", \"value\": \"x\"}";
            cr.requestedBy = "other.admin";
            db.add(cr);
            db.commit();
            id = cr.id;
        }
        ApiTestServer.Response r = send("POST", "/api/change-requests/" + id + "/approve", other, null);
        assertEquals(200, r.status(), r.body());
    }

    // ---------------------------------------------------------------- partners

    @Test
    void usersEditPartnersDirectly() throws Exception {
        ApiTestServer.Response r = send("POST", "/api/partners", user,
                Map.of("name", "Test Bank " + System.nanoTime(), "is_active", true, "emails", List.of()));
        assertEquals(200, r.status(), r.body());
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) r.json().get("result");
        assertEquals(Boolean.TRUE, result.get("applied"));
        assertNotNull(r.json().get("partner"));
    }
}
