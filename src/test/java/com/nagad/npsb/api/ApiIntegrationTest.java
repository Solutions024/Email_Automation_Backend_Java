package com.nagad.npsb.api;

import com.nagad.npsb.entity.ChangeRequest;
import com.nagad.npsb.entity.EmailVerificationCode;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.ChangeRequestRepository;
import com.nagad.npsb.repository.EmailVerificationCodeRepository;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.service.Approvals;
import com.nagad.npsb.service.AuthService;
import com.nagad.npsb.service.ExcelParser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nagad.npsb.api.ApiTestServer.bean;
import static com.nagad.npsb.api.ApiTestServer.inTransaction;
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
        if (bean(UserRepository.class).findByUsername(username).isPresent()) return;
        ApiTestServer.Response r = send("POST", "/api/users", tokenFor("admin"), Map.of("full_name", "Test " + username,
                "username", username, "email", email, "password", "Str0ng!Passw0rd#", "role", role));
        assertEquals(201, r.status(), r.body());
    }

    static int userId(String username) {
        return bean(UserRepository.class).findByUsername(username).orElseThrow().getId();
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
        // don't leave the account half way to a lock-out for other tests
        inTransaction(() -> bean(JdbcClient.class).sql("UPDATE users SET failed_login_count = 0 WHERE username = 'plain.user'").update());
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
        int id = userId("pw.target");
        ApiTestServer.Response r = send("PATCH", "/api/users/" + id, admin, Map.of("password", "N3w!Passw0rd#xyz"));
        assertEquals(200, r.status(), r.body());
        User u = bean(UserRepository.class).findById(id).orElseThrow();
        assertTrue(AuthService.verifyPassword("N3w!Passw0rd#xyz", u.getPasswordHash()));
        assertEquals(400, send("PATCH", "/api/users/" + id, admin, Map.of("password", "weak")).status());
    }

    @Test
    void builtInAdminCannotBeDemotedOrDeactivated() throws Exception {
        int id = userId("admin");
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
        inTransaction(() -> {
            User u = bean(UserRepository.class).findByUsername("forgetful").orElseThrow();
            EmailVerificationCode c = bean(EmailVerificationCodeRepository.class).findAll().stream()
                    .filter(x -> x.getUserId().equals(u.getId()) && "reset".equals(x.getPurpose()) && x.getUsedAt() == null)
                    .max(java.util.Comparator.comparing(EmailVerificationCode::getId)).orElse(null);
            assertNotNull(c, "a reset code is stored");
            c.setCodeHash(AuthService.codeHash(u, "reset", "123456"));
            return bean(EmailVerificationCodeRepository.class).save(c);
        });
        Map<String, Object> wrong = Map.of("email", "forgetful@nagad.com.bd", "code", "000000", "new_password", "Br4nd!New#Pass");
        ApiTestServer.Response bad = send("POST", "/api/auth/reset-password", null, wrong);
        assertEquals(400, bad.status());
        assertTrue(bad.body().contains("attempt"), bad.body());

        Map<String, Object> right = Map.of("email", "forgetful@nagad.com.bd", "code", "123456", "new_password", "Br4nd!New#Pass");
        assertEquals(200, send("POST", "/api/auth/reset-password", null, right).status());
        assertTrue(AuthService.verifyPassword("Br4nd!New#Pass",
                bean(UserRepository.class).findByUsername("forgetful").orElseThrow().getPasswordHash()));
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
    void olderPartnersGetTheirCodeFromEarlierUploads() throws Exception {
        String header = "NPSB_TXN_RRN,APPROVAL_DATE,APPROVAL_DATETIME,TXN_TYPE,TXN_ID,FIRST_TXN_STATUS_NAME,TXN_AMOUNT,"
                + "FROMAC_NAME,TOAC_TYPE,ORG_TXN_STAN,KEY_ORG_NM,NPSB_ORG_CODE,TXN_DIRECTION\n";
        String rows = header
                + "810000000001,2026-09-03,,NPSB,L1,APPROVED,100,A,CUSTOMER,1,Legacy Bank,0451,Inward\n"
                + "810000000002,2026-09-03,,NPSB,L2,APPROVED,100,A,CUSTOMER,2, LEGACY BANK ,451,Outward\n"   // same code
                + "810000000003,2026-09-03,,NPSB,T1,APPROVED,100,A,CUSTOMER,3,Twin Bank,0601,Inward\n"
                + "810000000004,2026-09-03,,NPSB,T2,APPROVED,100,A,CUSTOMER,4,Twin Bank,0602,Inward\n"       // two codes
                + "810000000005,2026-09-03,,NPSB,D1,APPROVED,100,A,CUSTOMER,5,Dup One,0701,Inward\n"
                + "810000000006,2026-09-03,,NPSB,D2,APPROVED,100,A,CUSTOMER,6,Dup Two,701,Inward\n"          // one code, two partners
                + "810000000007,2026-09-03,,NPSB,K1,APPROVED,100,A,CUSTOMER,7,Late Bank,0801,Inward\n";      // code already taken
        ApiTestServer.Response up = upload("/api/uploads", user, "legacy.csv", rows.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, up.status(), up.body());

        JdbcClient jdbc = bean(JdbcClient.class);
        inTransaction(() -> {  // partners made before codes existed
            for (String name : List.of("Legacy Bank", "twin bank", "Dup One", "Dup Two", "Late Bank")) {
                jdbc.sql("INSERT INTO partners (name, is_active) VALUES (?, TRUE)").params(name).update();
            }
            jdbc.sql("INSERT INTO partners (org_code, name, is_active) VALUES ('801', 'Has 801', TRUE)").update();
            return null;
        });
        bean(com.nagad.npsb.service.PartnerService.class).fillOrgCodes();

        java.util.function.Function<String, Object> codeOf = name ->
                jdbc.sql("SELECT org_code FROM partners WHERE name = ?").params(name).query().optionalValue().orElse(null);
        assertEquals("451", codeOf.apply("Legacy Bank"));   // 0451 and 451: one code, leading zeros dropped
        assertNull(codeOf.apply("twin bank"));              // two codes in its rows: an admin decides
        assertNull(codeOf.apply("Dup One"));                // the same code for two partners: neither gets it
        assertNull(codeOf.apply("Dup Two"));
        assertNull(codeOf.apply("Late Bank"));              // another partner already has 801
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
        Map<String, Object> applied = inTransaction(() -> {
            User a = bean(UserRepository.class).findByUsername("other.admin").orElseThrow();
            return bean(Approvals.class).submit(a, "variable", "create", null, "{{admin_note}}",
                    Map.of("name", "admin_note", "description", "By an admin", "source", "text", "value", "hi"));
        });
        assertEquals(Boolean.TRUE, applied.get("applied"));
        ChangeRequest cr = bean(ChangeRequestRepository.class).findById(((Number) applied.get("request_id")).intValue()).orElseThrow();
        assertEquals("applied", cr.getStatus());
    }

    @Test
    void anyAdminMayApproveEvenTheirOwnOlderRequest() throws Exception {
        // an older request made by an admin, from before admins' changes applied at once
        ChangeRequest cr = new ChangeRequest();
        cr.setEntity("variable");
        cr.setAction("create");
        cr.setSummary("{{old_admin_note}}");
        cr.setAfter("{\"aggregate\": null, \"column\": null, \"description\": \"Old\", \"name\": \"old_admin_note\", "
                + "\"source\": \"text\", \"value\": \"x\"}");
        cr.setRequestedBy("other.admin");
        int id = inTransaction(() -> bean(ChangeRequestRepository.class).save(cr)).getId();
        ApiTestServer.Response r = send("POST", "/api/change-requests/" + id + "/approve", other, null);
        assertEquals(200, r.status(), r.body());
    }

    // ---------------------------------------------------------------- partners

    @Test
    void usersEditPartnersDirectly() throws Exception {
        ApiTestServer.Response r = send("POST", "/api/partners", user,
                Map.of("org_code", String.valueOf(System.nanoTime() % 1_000_000_000L), "name", "Test Bank " + System.nanoTime(),
                        "is_active", true, "emails", List.of()));
        assertEquals(200, r.status(), r.body());
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) r.json().get("result");
        assertEquals(Boolean.TRUE, result.get("applied"));
        assertNotNull(r.json().get("partner"));
    }
}
