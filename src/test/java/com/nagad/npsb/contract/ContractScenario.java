package com.nagad.npsb.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagad.npsb.api.ApiTestServer;
import jakarta.mail.Message;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nagad.npsb.contract.ContractRecorder.Resp;

/**
 * The scenario: one fresh backend, driven only over HTTP from sign-in to sent emails and their follow-ups.
 * Every request is a recorded step (see ContractRecorder); ids found in answers feed the later steps.
 *
 * The backend starts with the test settings (mail server 127.0.0.1:9, nothing listens there), so the first
 * sends fail. Half way, an admin points it at FakeSmtpServer through the System Settings API; from then on
 * mail is delivered there, and the emailed sign-in / reset codes are read from it.
 *
 * Only ApiTestServer.url() (start the backend) and ApiTestServer.tokenFor(username) (a session for an account
 * created over the API) touch the backend from inside.
 */
final class ContractScenario {
    private static final String FRONTEND = "http://localhost:5173";
    private static final String PASSWORD = "Str0ng!Passw0rd#";
    private static final String ADMIN_PASSWORD = "Admin@1234";
    private static final String WRONG_PASSWORD = "Wrong#Passw0rd1";
    private static final String LOCK_PASSWORD = "Secur3d!Passw0rd#";  // what the admin sets for lock.user
    private static final Pattern CODE = Pattern.compile("(?<![0-9])(\\d{6})(?![0-9])");

    private final ContractRecorder rec;
    private final FakeSmtpServer smtp;

    // ids and values found along the way
    private int adminId, opsId, makerId, checkerId, lockId;
    private int bkashId, rocketId, tallyId, sureId;
    private int stdInwardId, stdOutwardId, shortId, reminderId, rrnVarId;
    private int upload1, upload2, upload3;
    private int email1, email2, email3;
    private int adminSessionId;
    private byte[] sampleFile;

    ContractScenario(ContractRecorder rec, FakeSmtpServer smtp) {
        this.rec = rec;
        this.smtp = smtp;
    }

    void run() throws Exception {
        publicEndpoints();
        signInBasics();
        accounts();
        signInWhileMailIsDown();
        partners();
        templatesAndVariables();
        uploads();
        preparingEmails();
        sendingWhileMailIsDown();
        mailServerSettings();
        sending();
        sentEmailsAndFollowUps();
        emailSettings();
        moreUploads();
        signInWithCodes();
        profileAndPassword();
        passwordReset();
        lockOutAndRateLimits();
        repliesAndInbox();
        answers();
        overview();
        signOut();
        outbox("mail server: received at the end");
    }

    // ================================================================ helpers

    /** A JSON object with its keys in this order (Map.of has no fixed order, and the snapshot must be stable). */
    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>();
        java.util.Collections.addAll(l, items);
        return l;
    }

    private ContractRecorder.Call step(String name) {
        return rec.step(name);
    }

    /** The 6-digit code in the newest mail to this address. */
    private String codeSentTo(String address) {
        FakeSmtpServer.Mail m = smtp.lastTo(address);
        if (m == null || m.subject() == null) return "000000";
        Matcher matcher = CODE.matcher(m.subject());
        return matcher.find() ? matcher.group(1) : "000000";
    }

    /** A code that is surely wrong. */
    private static String wrongCode(String right) {
        return right.equals("000000") ? "111111" : "000000";
    }

    /** Record what the mail server received since the previous record. */
    private void outbox(String name) {
        ArrayNode mails = JsonNodeFactory.instance.arrayNode();
        for (FakeSmtpServer.Mail m : smtp.drain()) {
            ObjectNode o = mails.addObject();
            o.put("envelope_from", m.envelopeFrom());
            ArrayNode rcpt = o.putArray("accepted_recipients");
            m.recipients().forEach(rcpt::add);
            ArrayNode refused = o.putArray("refused_recipients");
            m.refused().forEach(refused::add);
            o.put("from", m.from());
            ArrayNode to = o.putArray("to");
            m.header(Message.RecipientType.TO).forEach(to::add);
            ArrayNode cc = o.putArray("cc");
            m.header(Message.RecipientType.CC).forEach(cc::add);
            o.put("subject", maskSubject(m.subject()));
            o.put("in_reply_to", m.hasHeader("In-Reply-To"));
            ArrayNode att = o.putArray("attachments");
            m.attachmentNames().forEach(att::add);
        }
        rec.note(name, mails);
    }

    private static String maskSubject(String subject) {
        if (subject == null) return null;
        return subject.startsWith("Your ") ? CODE.matcher(subject).replaceAll("<code>") : subject;
    }

    /** An HS256 access token that expired an hour ago, signed with the test JWT_SECRET (the backend's own format). */
    private static String expiredToken(int userId) throws Exception {
        String secret = System.getenv().getOrDefault("JWT_SECRET", "");
        long now = System.currentTimeMillis() / 1000;
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + userId + "\",\"username\":\"admin\",\"ver\":0,\"type\":\"access\","
                + "\"iss\":\"npsb-email-automation\",\"iat\":" + (now - 7200) + ",\"exp\":" + (now - 3600) + ",\"jti\":\"0000\"}")
                .getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = b64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + sig;
    }

    private static Map<String, Object> account(String fullName, String username, String email, String role) {
        return obj("full_name", fullName, "username", username, "email", email, "password", PASSWORD, "role", role);
    }

    // ================================================================ public endpoints and routing

    private void publicEndpoints() throws Exception {
        step("health: livez").get("/livez").send();
        step("health: startupz").get("/startupz").send();
        step("health: readyz").get("/readyz").send();
        step("docs: swagger page").get("/docs").ignoreBody().send();
        step("docs: openapi.json").get("/openapi.json").ignoreBody().send();
        step("auth: settings (no sign-in needed)").get("/api/auth/settings").send();

        step("routing: unknown path 404").get("/api/does-not-exist").send();
        step("routing: root path 404").get("/").send();
        step("routing: trailing slash redirects 307").get("/api/partners/").send();
        step("routing: trailing slash redirect keeps the query 307").get("/api/sent-emails/?sort=oldest&limit=5").send();
        step("routing: unknown path with a trailing slash 404").get("/api/nothing-here/").send();
        step("routing: wrong method 405").delete("/api/partners").send();
        step("routing: wrong method on a path with an id 405").get("/api/partners/1").send();
        step("routing: POST to a GET-only route 405").post("/api/uploads/1/summary").send();
        step("routing: DELETE on /api/users/events 405").delete("/api/users/events").send();

        step("cors: preflight from the frontend").options("/api/auth/login")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type").send();
        step("cors: preflight from another origin is refused").options("/api/auth/login")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST").send();
        step("cors: request from the frontend").get("/api/auth/settings").header("Origin", FRONTEND).send();
        step("cors: error answer to the frontend keeps the CORS headers").get("/api/partners").header("Origin", FRONTEND).send();
        step("cors: request from another origin gets no CORS headers").get("/api/auth/settings")
                .header("Origin", "http://evil.example").send();
    }

    // ================================================================ sign-in (built-in admin: password only)

    private void signInBasics() throws Exception {
        step("auth: no token 401").get("/api/partners").send();
        step("auth: token that is not a JWT 401").get("/api/partners").authorization("not-a-jwt", "Bearer not-a-token").send();
        step("auth: other scheme than Bearer 401").get("/api/partners").authorization("basic", "Basic YWRtaW46eA==").send();
        step("auth: expired token 401").get("/api/partners").authorization("expired-jwt", "Bearer " + expiredToken(1)).send();

        step("auth: login with a wrong password 401").post("/api/auth/login")
                .json(obj("login", "admin", "password", WRONG_PASSWORD)).send();
        step("auth: login with an unknown account 401").post("/api/auth/login")
                .json(obj("login", "nobody", "password", WRONG_PASSWORD)).send();
        step("auth: login with missing fields 422").post("/api/auth/login").json(obj()).send();
        step("auth: login with wrong types 422").post("/api/auth/login")
                .json(obj("login", 5, "password", false, "remember_me", "maybe")).send();
        step("auth: login body that is not JSON 422").post("/api/auth/login").raw("application/json", "{not json").send();
        step("auth: login body that is a JSON list 422").post("/api/auth/login").raw("application/json", "[1, 2]").send();
        step("auth: login without a body 422").post("/api/auth/login").send();

        Resp login = step("auth: built-in admin signs in with the password only").post("/api/auth/login").browser("admin-browser")
                .json(obj("login", "admin", "password", ADMIN_PASSWORD)).send();
        rec.persona("admin", login.str("/access_token"));
        adminId = login.id("/user/id");
        step("auth: me (admin)").get("/api/auth/me").as("admin").send();

        step("auth: refresh without a cookie 401").post("/api/auth/refresh").send();
        rec.setCookie("stranger-browser", "npsb_refresh", "not-a-real-refresh-token", "/api/auth");
        step("auth: refresh with an unknown cookie 401").post("/api/auth/refresh").browser("stranger-browser").send();
        String firstCookie = rec.cookie("admin-browser", "npsb_refresh");
        Resp refreshed = step("auth: refresh rotates the cookie").post("/api/auth/refresh").browser("admin-browser").send();
        rec.persona("admin", refreshed.str("/access_token"));
        rec.setCookie("admin-second-tab", "npsb_refresh", firstCookie, "/api/auth");
        step("auth: refresh with the cookie another tab just replaced 200").post("/api/auth/refresh").browser("admin-second-tab").send();

        Resp sessions = step("auth: sessions (admin)").get("/api/auth/sessions").as("admin").browser("admin-browser").sortBy("id").send();
        adminSessionId = sessions.id("/0/id");
        step("auth: my activity (admin)").get("/api/auth/activity").as("admin").sortBy("id").send();
        step("auth: sessions need a sign-in 401").get("/api/auth/sessions").send();
    }

    // ================================================================ accounts (built-in admin only)

    private void accounts() throws Exception {
        step("users: list").get("/api/users").as("admin").send();
        opsId = step("users: create an admin").post("/api/users").as("admin")
                .json(account("Ops Admin", "ops.admin", "ops@nagad.com.bd", "admin")).send().id("/id");
        makerId = step("users: create a user").post("/api/users").as("admin")
                .json(account("Maker User", "maker.user", "maker@nagad.com.bd", "user")).send().id("/id");
        checkerId = step("users: create a second user").post("/api/users").as("admin")
                .json(account("Checker User", "Checker.User", "Checker@Nagad.com.bd", "user")).send().id("/id");
        lockId = step("users: create a third user (role defaults to user)").post("/api/users").as("admin")
                .json(obj("full_name", "  Lock   User ", "username", "lock.user", "email", "lock@nagad.com.bd", "password", PASSWORD))
                .send().id("/id");

        step("users: username already taken 409").post("/api/users").as("admin")
                .json(account("Someone Else", "maker.user", "someone@nagad.com.bd", "user")).send();
        step("users: email already taken 409").post("/api/users").as("admin")
                .json(account("Someone Else", "someone.else", "MAKER@nagad.com.bd", "user")).send();
        step("users: weak password 400").post("/api/users").as("admin")
                .json(obj("full_name", "Weak Password", "username", "weak.user", "email", "weak@nagad.com.bd", "password", "weakpass",
                        "role", "user")).send();
        step("users: password containing the username 400").post("/api/users").as("admin")
                .json(obj("full_name", "Name In Password", "username", "rafiq", "email", "r@nagad.com.bd", "password", "Rafiq#2026xyz",
                        "role", "user")).send();
        step("users: disposable email 400").post("/api/users").as("admin")
                .json(account("Throw Away", "throw.away", "someone@mailinator.com", "user")).send();
        step("users: missing fields 422").post("/api/users").as("admin").json(obj()).send();
        step("users: invalid fields 422").post("/api/users").as("admin")
                .json(obj("full_name", "X", "username", "No Spaces!", "email", "not-an-email", "password", 12345, "role", "boss")).send();
        step("users: no body 422").post("/api/users").as("admin").send();

        rec.persona("ops", ApiTestServer.tokenFor("ops.admin"));
        rec.persona("maker", ApiTestServer.tokenFor("maker.user"));
        rec.persona("checker", ApiTestServer.tokenFor("checker.user"));

        step("users: list as another admin 403").get("/api/users").as("ops").send();
        step("users: list as a user 403").get("/api/users").as("maker").send();
        step("users: create as another admin 403").post("/api/users").as("ops")
                .json(account("Not Allowed", "not.allowed", "na@nagad.com.bd", "user")).send();
        step("users: audit log as a user 403").get("/api/users/events").as("maker").send();
        step("users: update as another admin 403").patch("/api/users/" + makerId).as("ops").json(obj("unlock", true)).send();

        step("users: make a user an admin").patch("/api/users/" + checkerId).as("admin").json(obj("role", "admin")).send();
        step("users: make them a user again").patch("/api/users/" + checkerId).as("admin").json(obj("role", "user")).send();
        step("users: unlock when nothing is locked").patch("/api/users/" + checkerId).as("admin").json(obj("unlock", true)).send();
        step("users: deactivate").patch("/api/users/" + lockId).as("admin").json(obj("is_active", false)).send();
        step("users: activate again").patch("/api/users/" + lockId).as("admin").json(obj("is_active", true)).send();
        step("users: set a password for someone").patch("/api/users/" + lockId).as("admin")
                .json(obj("password", LOCK_PASSWORD)).send();
        step("users: set a weak password 400").patch("/api/users/" + lockId).as("admin").json(obj("password", "weak")).send();
        step("users: built-in admin can't be deactivated 400").patch("/api/users/" + adminId).as("admin")
                .json(obj("is_active", false)).send();
        step("users: built-in admin can't become a user 400").patch("/api/users/" + adminId).as("admin")
                .json(obj("role", "user")).send();
        step("users: own password is changed on My Profile 400").patch("/api/users/" + adminId).as("admin")
                .json(obj("password", "N3w!Passw0rd#xyz")).send();
        step("users: change another admin's role").patch("/api/users/" + opsId).as("admin").json(obj("role", "user")).send();
        step("users: and back").patch("/api/users/" + opsId).as("admin").json(obj("role", "admin")).send();
        step("users: unknown user 404").patch("/api/users/9999").as("admin").json(obj("unlock", true)).send();
        step("users: non-integer id 422").patch("/api/users/abc").as("admin").json(obj("unlock", true)).send();
        step("users: PATCH /api/users/events is the {user_id} route 422").patch("/api/users/events").as("admin").json(obj()).send();
        step("users: invalid fields on update 422").patch("/api/users/" + lockId).as("admin")
                .json(obj("role", "boss", "is_active", "maybe", "unlock", 3, "password", 42)).send();
        step("users: update without a body 422").patch("/api/users/" + lockId).as("admin").send();
        step("users: list after the changes").get("/api/users").as("admin").send();
        step("users: audit log (limit 5)").get("/api/users/events?limit=5").as("admin").send();
        step("users: audit log with a limit that is not a number 422").get("/api/users/events?limit=lots").as("admin").send();
    }

    // ================================================================ users sign in with an emailed code: the mail server is down

    private void signInWhileMailIsDown() throws Exception {
        step("sign-in: a user's code can't be emailed (mail server down) 502").post("/api/auth/login")
                .json(obj("login", "maker.user", "password", PASSWORD)).send();
        step("sign-in: no self sign-up 403").post("/api/auth/register")
                .json(obj("full_name", "New Person", "username", "new.person", "email", "new.person@nagad.com.bd",
                        "password", PASSWORD, "role", "user")).send();
        step("sign-in: sign-up with invalid fields 422").post("/api/auth/register")
                .json(obj("full_name", "N", "username", "x", "email", "nope", "role", "root")).send();
    }

    // ================================================================ partners (users change them directly)

    private void partners() throws Exception {
        step("partners: list (none yet)").get("/api/partners").as("maker").send();
        bkashId = step("partners: create bKash").post("/api/partners").as("maker")
                .json(obj("org_code", "22002", "name", "bKash", "is_active", true, "emails", list(
                        obj("email", "dispute@bkash.com"),
                        obj("email", "bad.box@bkash.com", "is_active", true)))).send().id("/partner/id");
        rocketId = step("partners: create Rocket").post("/api/partners").as("maker")
                .json(obj("org_code", "022001", "name", "  Rocket ", "emails", list(obj("email", " Recon@Rocket.com.bd ")))).send()
                .id("/partner/id");
        tallyId = step("partners: create TallyPay (inactive)").post("/api/partners").as("checker")
                .json(obj("org_code", 22004, "name", "TallyPay", "is_active", false, "emails", list(obj("email", "ops@tallypay.com.bd")))).send()
                .id("/partner/id");
        sureId = step("partners: create SureCash (no active address)").post("/api/partners").as("checker")
                .json(obj("org_code", "22005", "name", "SureCash", "emails", list(obj("email", "desk@surecash.net", "is_active", false)))).send()
                .id("/partner/id");
        step("partners: an admin can create partners too").post("/api/partners").as("admin")
                .json(obj("org_code", "999", "name", "Upay Test", "emails", list())).send();

        step("partners: name that exists (other case and spaces) 400").post("/api/partners").as("maker")
                .json(obj("org_code", "777", "name", " BKASH ", "emails", list())).send();
        step("partners: no code 400").post("/api/partners").as("maker").json(obj("name", "Upay", "emails", list())).send();
        step("partners: code that is not a number 400").post("/api/partners").as("maker")
                .json(obj("org_code", "22-003", "name", "Upay", "emails", list())).send();
        step("partners: another partner's code with leading zeros 400").post("/api/partners").as("maker")
                .json(obj("org_code", "0022002", "name", "Upay", "emails", list())).send();
        step("partners: address listed twice 400").post("/api/partners").as("maker")
                .json(obj("org_code", "300", "name", "Upay", "emails", list(obj("email", "a@upay.com.bd"), obj("email", "A@upay.com.bd")))).send();
        step("partners: disposable address 400").post("/api/partners").as("maker")
                .json(obj("org_code", "300", "name", "Upay", "emails", list(obj("email", "x@mailinator.com")))).send();
        step("partners: empty name 400").post("/api/partners").as("maker").json(obj("name", "   ", "emails", list())).send();
        step("partners: invalid fields 422").post("/api/partners").as("maker")
                .json(obj("is_active", "sometimes", "emails", list(obj("email", "not an email"), "x"))).send();
        step("partners: emails is not a list 422").post("/api/partners").as("maker").json(obj("name", "Upay", "emails", "a@b.com")).send();
        step("partners: no body 422").post("/api/partners").as("maker").send();

        step("partners: update Rocket (one more address)").put("/api/partners/" + rocketId).as("maker")
                .json(obj("name", "Rocket", "is_active", true, "emails", list(
                        obj("email", "recon@rocket.com.bd"),
                        obj("email", "claims@rocket.com.bd", "is_active", true, "clear_problem", false)))).send();
        step("partners: rename to another partner's name 400").put("/api/partners/" + rocketId).as("maker")
                .json(obj("name", "bkash", "emails", list())).send();
        step("partners: change to another partner's code 400").put("/api/partners/" + rocketId).as("maker")
                .json(obj("org_code", "22002", "name", "Rocket", "emails", list())).send();
        step("partners: update an unknown partner 404").put("/api/partners/9999").as("maker")
                .json(obj("name", "Nobody", "emails", list())).send();
        step("partners: update with a non-integer id 422").put("/api/partners/abc").as("maker")
                .json(obj("name", "Nobody", "emails", list())).send();
        step("partners: list").get("/api/partners").as("checker").send();
        step("change requests: partner history (applied at once)").get("/api/change-requests?status=history&entity=partner").as("maker").send();
    }

    // ================================================================ templates and variables (users' changes wait for an admin)

    private Map<String, Object> shortTemplate(String description, boolean isDefault) {
        return obj("name", "Inward - Short notice", "mail_type", "inward", "purpose", "initial", "description", description,
                "subject", "Settlement pending - {{partner_name}} - {{date}}",
                "body_html", "<p>Dear Concern,</p><p>{{txn_count}} transaction(s), BDT {{total_amount}}: {{rrn_list}}.</p>",
                "is_default", isDefault);
    }

    private int requestId(Resp r) {
        return r.id("/result/request_id");
    }

    private void templatesAndVariables() throws Exception {
        Resp list = step("templates: list").get("/api/email-templates").as("maker").send();
        stdInwardId = list.idWhere("/templates", "name", "Inward - Not found in OIC Settlement File");
        stdOutwardId = list.idWhere("/templates", "name", "Outward - Claim confirmation");

        step("templates: admins don't edit templates 403").post("/api/email-templates").as("admin")
                .json(shortTemplate("", false)).send();
        step("templates: unknown placeholder 400").post("/api/email-templates").as("maker")
                .json(obj("name", "Inward - Typo", "mail_type", "inward", "subject", "Settlement - {{partner_name}}",
                        "body_html", "<p>Dear Concern,</p><p>{{partner}}</p>")).send();
        step("templates: invalid fields 422").post("/api/email-templates").as("maker")
                .json(obj("name", "  ", "mail_type", "sideways", "purpose", "later", "subject", "",
                        "body_html", "<p>&nbsp;</p>", "is_default", "maybe")).send();
        step("templates: no body 422").post("/api/email-templates").as("maker").send();

        int reqRrn = requestId(step("variables: create rrn_list (waits for approval)").post("/api/template-variables").as("maker")
                .json(obj("name", "{{rrn_list}}", "description", "RRNs of the email", "source", "column", "column", "NPSB_TXN_RRN",
                        "aggregate", "list")).send());
        int reqTeam = requestId(step("variables: create team_name (waits for approval)").post("/api/template-variables").as("maker")
                .json(obj("name", "team_name", "description", "Our team", "source", "text", "value", "Reconciliation Team")).send());
        int reqAmount = requestId(step("variables: create amount_total (waits for approval)").post("/api/template-variables").as("maker")
                .json(obj("name", "amount_total", "source", "column", "column", "TXN_AMOUNT", "aggregate", "sum")).send());
        step("variables: a column variable needs a column 400").post("/api/template-variables").as("maker")
                .json(obj("name", "no_column", "source", "column", "aggregate", "list")).send();
        step("variables: only TXN_AMOUNT can be added up 400").post("/api/template-variables").as("maker")
                .json(obj("name", "bad_sum", "source", "column", "column", "TXN_ID", "aggregate", "sum")).send();
        step("variables: the same name from another user waits too 201")
                .post("/api/template-variables").as("checker")
                .json(obj("name", "team_name", "source", "text", "value", "Other team")).send();
        step("variables: invalid name and source 422").post("/api/template-variables").as("maker")
                .json(obj("name", "1bad name", "source", "formula", "value", 7)).send();
        step("variables: built-in placeholder name 422").post("/api/template-variables").as("maker")
                .json(obj("name", "{{partner_name}}", "source", "text")).send();
        step("variables: admins don't edit variables 403").post("/api/template-variables").as("admin")
                .json(obj("name", "admin_var", "source", "text", "value", "x")).send();

        Resp pending = step("change requests: pending (admin)").get("/api/change-requests").as("admin").send();
        int reqTeamOther = -1;
        for (JsonNode r : pending.json() == null ? JsonNodeFactory.instance.arrayNode() : pending.json()) {
            if ("checker.user".equals(r.path("requested_by").asText())) reqTeamOther = r.path("id").asInt(-1);
        }
        step("change requests: pending (maker)").get("/api/change-requests").as("maker").send();
        step("change requests: invalid status filter 422").get("/api/change-requests?status=everything").as("admin").send();
        step("attention: admin sees the approvals").get("/api/attention").as("admin").send();
        step("attention: a user sees their own requests").get("/api/attention").as("maker").send();
        step("templates: list shows the pending changes").get("/api/email-templates").as("maker").send();

        step("change requests: a user can't approve 403").post("/api/change-requests/" + reqRrn + "/approve").as("maker").send();
        rrnVarId = step("change requests: another admin approves rrn_list").post("/api/change-requests/" + reqRrn + "/approve").as("ops")
                .send().id("/entity_id");
        step("change requests: approve twice 409").post("/api/change-requests/" + reqRrn + "/approve").as("admin").send();
        step("change requests: reject team_name with a note").post("/api/change-requests/" + reqTeam + "/reject").as("admin")
                .json(obj("note", "  Use the signature instead.  ")).send();
        step("change requests: approve the checker's team_name").post("/api/change-requests/" + reqTeamOther + "/approve").as("admin").send();
        step("change requests: reject without a body 422").post("/api/change-requests/" + reqAmount + "/reject").as("admin").send();
        step("change requests: someone else can't cancel 403").post("/api/change-requests/" + reqAmount + "/cancel").as("checker").send();
        step("change requests: the maker cancels amount_total").post("/api/change-requests/" + reqAmount + "/cancel").as("maker").send();
        step("change requests: cancel twice 409").post("/api/change-requests/" + reqAmount + "/cancel").as("maker").send();
        step("change requests: unknown request 404").post("/api/change-requests/9999/approve").as("admin").send();
        step("change requests: non-integer id 422").post("/api/change-requests/abc/approve").as("admin").send();

        int reqShort = requestId(step("templates: create Inward - Short notice (waits)").post("/api/email-templates").as("maker")
                .json(shortTemplate("Short inward wording", false)).send());
        int reqReminder = requestId(step("templates: create Outward - Reminder (waits)").post("/api/email-templates").as("maker")
                .json(obj("name", "Outward - Reminder", "mail_type", "outward", "purpose", "reminder",
                        "subject", "Reminder {{followup_number}}: {{partner_name}} - {{mail_type}}",
                        "body_html", "<p>Dear Concern,</p><p>Our email of {{sent_date}} about {{txn_count}} transaction(s) is still open.</p>"))
                .send());
        step("templates: a name that exists 400").post("/api/email-templates").as("maker")
                .json(obj("name", "outward - claim CONFIRMATION", "mail_type", "outward", "subject", "x", "body_html", "<p>x</p>")).send();
        shortId = step("change requests: approve Inward - Short notice").post("/api/change-requests/" + reqShort + "/approve").as("admin")
                .send().id("/entity_id");
        reminderId = step("change requests: approve Outward - Reminder").post("/api/change-requests/" + reqReminder + "/approve").as("admin")
                .send().id("/entity_id");

        int reqDefault = requestId(step("templates: make Inward - Short notice the default (waits)")
                .post("/api/email-templates/" + shortId + "/default").as("maker").send());
        step("change requests: approve the new default").post("/api/change-requests/" + reqDefault + "/approve").as("admin").send();
        int reqUpdate = requestId(step("templates: change Inward - Short notice (waits)").put("/api/email-templates/" + shortId).as("maker")
                .json(shortTemplate("Short inward wording (v2)", true)).send());
        step("templates: a second change while one waits 409").put("/api/email-templates/" + shortId).as("maker")
                .json(shortTemplate("Short inward wording (v3)", true)).send();
        step("change requests: approve the change").post("/api/change-requests/" + reqUpdate + "/approve").as("admin").send();
        step("templates: the default can't be deleted 400").delete("/api/email-templates/" + shortId).as("maker").send();
        step("templates: the default must stay the default 400").put("/api/email-templates/" + shortId).as("maker")
                .json(shortTemplate("Short inward wording (v2)", false)).send();
        step("templates: the default can't change its mail type 400").put("/api/email-templates/" + shortId).as("maker")
                .json(obj("name", "Inward - Short notice", "mail_type", "outward", "subject", "x", "body_html", "<p>x</p>",
                        "is_default", true)).send();
        int reqDelete = requestId(step("templates: delete the standard inward template (waits)")
                .delete("/api/email-templates/" + stdInwardId).as("maker").send());
        step("change requests: approve the delete").post("/api/change-requests/" + reqDelete + "/approve").as("admin").send();
        step("templates: change an unknown template 404").put("/api/email-templates/9999").as("maker").json(shortTemplate("", false)).send();
        step("templates: delete an unknown template 404").delete("/api/email-templates/9999").as("maker").send();
        step("templates: default with a non-integer id 422").post("/api/email-templates/abc/default").as("maker").send();

        step("variables: rename a variable a template uses 400").put("/api/template-variables/" + rrnVarId).as("maker")
                .json(obj("name", "rrns", "source", "column", "column", "NPSB_TXN_RRN", "aggregate", "list")).send();
        int reqVar = requestId(step("variables: change rrn_list (waits)").put("/api/template-variables/" + rrnVarId).as("maker")
                .json(obj("name", "rrn_list", "description", "All RRNs of the email", "source", "column", "column", "NPSB_TXN_RRN",
                        "aggregate", "list")).send());
        step("change requests: approve the variable change").post("/api/change-requests/" + reqVar + "/approve").as("admin").send();
        step("variables: delete a variable a template uses 400").delete("/api/template-variables/" + rrnVarId).as("maker").send();
        step("variables: change an unknown variable 404").put("/api/template-variables/9999").as("maker")
                .json(obj("name", "nothing", "source", "text")).send();
        step("variables: delete an unknown variable 404").delete("/api/template-variables/9999").as("maker").send();

        step("server settings: switch maker-checker off").put("/api/server-settings").as("admin")
                .json(obj("values", obj("MAKER_CHECKER", false))).send();
        Resp applied = step("variables: create signature_note (applied at once)").post("/api/template-variables").as("maker")
                .json(obj("name", "signature_note", "source", "text", "value", "Nagad Reconciliation")).send();
        int sigId = applied.idWhere("/variables", "name", "signature_note");
        step("variables: delete signature_note (applied at once)").delete("/api/template-variables/" + sigId).as("maker").send();
        step("server settings: switch maker-checker on again").put("/api/server-settings").as("admin")
                .json(obj("values", obj("MAKER_CHECKER", true))).send();

        step("templates: list after the changes").get("/api/email-templates").as("checker").send();
        step("change requests: history").get("/api/change-requests?status=history").as("admin").send();
        step("change requests: everything about one template").get("/api/change-requests?status=all&entity=template&entity_id=" + shortId)
                .as("maker").send();
        step("change requests: entity_id that is not a number 422").get("/api/change-requests?entity_id=one").as("maker").send();
    }

    // ================================================================ uploads

    static final String CSV_HEADER = "NPSB_TXN_RRN,APPROVAL_DATE,APPROVAL_DATETIME,TXN_TYPE,TXN_ID,FIRST_TXN_STATUS_NAME,TXN_AMOUNT,"
            + "FROMAC_NAME,TOAC_TYPE,ORG_TXN_STAN,KEY_ORG_NM,NPSB_ORG_CODE,TXN_DIRECTION\n";

    /** Rows for most of what the parser and the email preparation check (fixed dates: nothing from the clock). */
    static final String CSV_ROWS = CSV_HEADER
            + "700000000001,2026-09-03,2026-09-03 10:15:00,NPSB - NAGAD to MFS,T0001,APPROVED,\"1,250.50\",Ana Rahman,CUSTOMER,12345,Upay,22003,Outward\n"
            + "700000000002,2026-09-03,2026-09-03 11:00:00,NPSB - MFS to NAGAD,T0002,APPROVED,300,Ben Ali,CUSTOMER,123457,TallyPay,022004,Inward\n"
            + "700000000003,2026-09-04,,NPSB - MFS to NAGAD,T0003,APPROVED,99.99,Cat Hossain,MERCHANT,123458,SureCash,022005,Inward Transaction\n"
            + "700000000004,2026-09-04,04-SEP-26 09.30.00.000000 AM,NPSB - NAGAD to MFS,T0004,APPROVED,450,Dan Karim,CUSTOMER,123459, bkash ,022002,OUTWARD\n"
            + "624515085655,2026-09-02,02-SEP-26 03.56.37.586000 PM,NPSB - MFS to NAGAD,00043BOS,APPROVED,20500,bKash,CUSTOMER,087542,bKash,022002,Inward\n"
            + "700000000005,2026-09-05,2026-09-05 08:00:00,NPSB - NAGAD to MFS,T0005,APPROVED,-5,Eva,CUSTOMER,123460,Rocket,022001,Outward\n"
            + "700000000006,not-a-date,31-13-2026 99:00,NPSB,T0006,APPROVED,abc,Fay,CUSTOMER,123461,Rocket,022001,Sideways\n"
            + "700000000001,2026-09-03,2026-09-03 10:15:00,NPSB - NAGAD to MFS,T0001,APPROVED,10,Ana Rahman,CUSTOMER,12345,Upay,22003,Outward\n"
            + ",2026-09-03,,NPSB,,APPROVED,,Gus,,,,,\n";

    private void uploads() throws Exception {
        Resp template = step("uploads: sample file").get("/api/upload-template").as("maker").send();
        sampleFile = template.raw;
        step("uploads: sample file needs a sign-in 401").get("/api/upload-template").send();
        step("uploads: list (none yet)").get("/api/uploads").as("maker").send();

        upload1 = step("uploads: upload the sample file").post("/api/uploads").as("maker")
                .multipart("file", "sample.xlsx", sampleFile).send().id("/upload_id");
        upload2 = step("uploads: the same rows again (all repeats)").post("/api/uploads").as("checker")
                .multipart("file", "sample-copy.xlsx", sampleFile).send().id("/upload_id");
        upload3 = step("uploads: a CSV with faulty and repeated rows").post("/api/uploads").as("maker")
                .multipart("file", "more-transactions.csv", CSV_ROWS.getBytes(StandardCharsets.UTF_8)).send().id("/upload_id");

        step("uploads: file type not allowed 400").post("/api/uploads").as("maker")
                .multipart("file", "notes.html", "<p>hello</p>".getBytes(StandardCharsets.UTF_8)).send();
        step("uploads: CSV without the required columns 400").post("/api/uploads").as("maker")
                .multipart("file", "short.csv", "NPSB_TXN_RRN,TXN_AMOUNT\n1,2\n".getBytes(StandardCharsets.UTF_8)).send();
        step("uploads: CSV with only the headers 400").post("/api/uploads").as("maker")
                .multipart("file", "empty.csv", CSV_HEADER.getBytes(StandardCharsets.UTF_8)).send();
        step("uploads: CSV that is not UTF-8 400").post("/api/uploads").as("maker")
                .multipart("file", "latin1.csv", new byte[]{'R', 'R', 'N', ',', (byte) 0xE9, '\n'}).send();
        step("uploads: .xlsx that is not a workbook 400").post("/api/uploads").as("maker")
                .multipart("file", "broken.xlsx", "not a zip file".getBytes(StandardCharsets.UTF_8)).send();
        step("uploads: form without the file field 422").post("/api/uploads").as("maker")
                .multipart("attachment", "sample.xlsx", sampleFile).send();
        step("uploads: JSON instead of a form 422").post("/api/uploads").as("maker").json(obj("file", "sample.xlsx")).send();
        step("uploads: file over 25 MB 413").post("/api/uploads").as("maker")
                .multipart("file", "big.csv", new byte[26 * 1024 * 1024]).send();

        step("uploads: list").get("/api/uploads").as("checker").send();
        step("uploads: summary of the sample upload").get("/api/uploads/" + upload1 + "/summary").as("maker").send();
        step("uploads: summary of the repeated upload").get("/api/uploads/" + upload2 + "/summary").as("maker").send();
        step("uploads: summary of the CSV upload").get("/api/uploads/" + upload3 + "/summary").as("maker").send();
        step("uploads: groups of the sample upload").get("/api/uploads/" + upload1).as("maker").send();
        step("uploads: groups of the CSV upload").get("/api/uploads/" + upload3).as("maker").send();
        step("uploads: valid rows as .xlsx").get("/api/uploads/" + upload3 + "/rows/valid").as("maker").send();
        step("uploads: faulty rows as .xlsx").get("/api/uploads/" + upload3 + "/rows/faulty").as("maker").send();
        step("uploads: unknown row kind 404").get("/api/uploads/" + upload3 + "/rows/other").as("maker").send();
        step("uploads: rows of an unknown upload 404").get("/api/uploads/9999/rows/valid").as("maker").send();
        step("uploads: summary of an unknown upload 404").get("/api/uploads/9999/summary").as("maker").send();
        step("uploads: groups of an unknown upload 404").get("/api/uploads/9999").as("maker").send();
        step("uploads: non-integer id 422").get("/api/uploads/abc").as("maker").send();
        step("uploads: non-integer id without a sign-in is 401 first").get("/api/uploads/abc").send();
    }

    // ================================================================ preparing emails

    private void preparingEmails() throws Exception {
        String prepare = "/api/uploads/" + upload1 + "/prepare-emails";
        step("emails: none before preparing").get("/api/uploads/" + upload1 + "/emails").as("maker").send();
        step("emails: prepare with an invalid templates choice 422").post(prepare).as("maker")
                .json(obj("templates", obj("sideways", 1, "inward", "first"))).send();
        step("emails: prepare with templates that is not an object 422").post(prepare).as("maker").json(obj("templates", list(1))).send();
        step("emails: prepare with a template of another mail type 400").post(prepare).as("maker")
                .json(obj("templates", obj("inward", reminderId))).send();
        step("emails: prepare an unknown upload 404").post("/api/uploads/9999/prepare-emails").as("maker").send();
        Resp prepared = step("emails: prepare the sample upload").post(prepare).as("maker").send();
        email1 = prepared.idWhere("/emails", "partner_name", "bKash");
        email2 = prepared.idWhere("/emails", "partner_name", "Rocket");

        step("emails: attachment (.xlsx)").get("/api/emails/" + email1 + "/attachment").as("maker").send();
        step("emails: attachment of an unknown email 404").get("/api/emails/9999/attachment").as("maker").send();
        step("emails: edit").put("/api/emails/" + email2).as("maker")
                .json(obj("subject", "  Claim confirmation - Rocket  ", "body_html", "<p>Dear Concern,</p><p>Please confirm.</p>")).send();
        step("emails: edit with an empty subject 422").put("/api/emails/" + email2).as("maker")
                .json(obj("subject", "   ", "body_html", 5)).send();
        step("emails: edit an unknown email 404").put("/api/emails/9999").as("maker")
                .json(obj("subject", "x", "body_html", "<p>x</p>")).send();
        step("emails: preparing again keeps the edit").post(prepare).as("maker").send();
        step("emails: discard the edit").delete("/api/emails/" + email2 + "/edits").as("maker").send();
        step("emails: prepare again with a chosen template").post(prepare).as("maker")
                .json(obj("templates", obj("outward", stdOutwardId))).send();
        step("emails: rebuild from a template").put("/api/emails/" + email2 + "/template").as("maker")
                .json(obj("template_id", stdOutwardId)).send();
        step("emails: rebuild from a template of another mail type 400").put("/api/emails/" + email2 + "/template").as("maker")
                .json(obj("template_id", shortId)).send();
        step("emails: rebuild from an unknown template 404").put("/api/emails/" + email2 + "/template").as("maker")
                .json(obj("template_id", 9999)).send();
        step("emails: rebuild without a template id 422").put("/api/emails/" + email2 + "/template").as("maker").json(obj()).send();
        step("emails: list").get("/api/uploads/" + upload1 + "/emails").as("checker").send();
        step("emails: list of an unknown upload 404").get("/api/uploads/9999/emails").as("checker").send();

        step("emails: submit for approval").post("/api/uploads/" + upload1 + "/submit").as("maker").send();
        step("emails: submit again (nothing prepared) 400").post("/api/uploads/" + upload1 + "/submit").as("maker").send();
        step("emails: submit an unknown upload 404").post("/api/uploads/9999/submit").as("maker").send();
        step("emails: a user can't edit a pending email 403").put("/api/emails/" + email2).as("maker")
                .json(obj("subject", "x", "body_html", "<p>x</p>")).send();
        step("emails: a user can't discard edits of a pending email 403").delete("/api/emails/" + email2 + "/edits").as("maker").send();
        step("emails: an admin edits a pending email").put("/api/emails/" + email2).as("admin")
                .json(obj("subject", "Claim confirmation needed - Rocket", "body_html", "<p>Dear Concern,</p><p>Please confirm the attached transaction.</p>"))
                .send();
        step("emails: an admin discards the edit (rebuilt at once)").delete("/api/emails/" + email2 + "/edits").as("admin").send();
        step("pending: list").get("/api/pending-emails").as("checker").send();
        step("pending: export (.xlsx)").get("/api/pending-emails/export").as("checker").send();
    }

    // ================================================================ sending while the mail server is down

    private void sendingWhileMailIsDown() throws Exception {
        step("pending: a user can't send 403").post("/api/pending-emails/send").as("maker").json(obj("ids", list(email1, email2))).send();
        step("pending: send nothing 400").post("/api/pending-emails/send").as("admin").json(obj("ids", list())).send();
        step("pending: send with invalid ids 422").post("/api/pending-emails/send").as("admin")
                .json(obj("ids", list("one", 2.5, true))).send();
        step("pending: send without ids 422").post("/api/pending-emails/send").as("admin").json(obj()).send();
        step("pending: send while the mail server is down (both fail)").post("/api/pending-emails/send").as("admin")
                .json(obj("ids", list(email1, email2, 9999, email1))).send();
        step("pending: failed emails stay in the list").get("/api/pending-emails").as("admin").send();
        step("attention: admin sees the failed emails and the mail error").get("/api/attention").as("admin").send();
        step("emails: history of a failed email").get("/api/emails/" + email1 + "/events").as("maker").send();
        step("emails: history of an unknown email 404").get("/api/emails/9999/events").as("maker").send();
        step("sent: reply status of an email that wasn't sent 404").put("/api/emails/" + email1 + "/reply-status").as("admin")
                .json(obj("status", "received")).send();
        step("sent: follow-up draft of an email that wasn't sent 404").get("/api/emails/" + email1 + "/followup-draft").as("maker").send();
        step("email settings: test email while the mail server is down 502").post("/api/email-settings/test-email").as("admin")
                .json(obj("to", "ops@nagad.com.bd")).send();
    }

    // ================================================================ System Settings: point the backend at the fake mail server

    private void mailServerSettings() throws Exception {
        int port = smtp.port();
        step("server settings: get").get("/api/server-settings").as("admin").send();
        step("server settings: users can't read them 403").get("/api/server-settings").as("maker").send();
        step("server settings: other admins can read them").get("/api/server-settings").as("ops").send();
        step("server settings: unknown setting 400").put("/api/server-settings").as("admin")
                .json(obj("values", obj("NOT_A_SETTING", 1))).send();
        step("server settings: invalid server name 400").put("/api/server-settings").as("admin")
                .json(obj("values", obj("SMTP_HOST", "not a host!"))).send();
        step("server settings: port out of range 400").put("/api/server-settings").as("admin")
                .json(obj("values", obj("SMTP_PORT", 70000))).send();
        step("server settings: invalid body 422").put("/api/server-settings").as("admin")
                .json(obj("values", "all", "reset", "SMTP_PORT", "force", "please")).send();
        step("server settings: nothing to change").put("/api/server-settings").as("admin").json(obj("values", obj())).send();
        step("server settings: a mail server that can't be reached is not saved 409").put("/api/server-settings").as("admin")
                .json(obj("values", obj("SMTP_PORT", 10))).send();
        step("server settings: users can't change them 403").put("/api/server-settings").as("maker")
                .json(obj("values", obj("SMTP_PORT", port))).send();
        step("server settings: test the fake mail server").post("/api/server-settings/test").as("admin")
                .json(obj("target", "smtp", "values", obj("SMTP_PORT", port))).send();
        step("server settings: test the mailbox (not set up) 502").post("/api/server-settings/test").as("admin")
                .json(obj("target", "imap", "values", obj())).send();
        step("server settings: test Gmail (not set up) 502").post("/api/server-settings/test").as("admin")
                .json(obj("target", "gmail")).send();
        step("server settings: test an unknown target 422").post("/api/server-settings/test").as("admin")
                .json(obj("target", "pop3")).send();
        step("server settings: test as a user 403").post("/api/server-settings/test").as("maker").json(obj("target", "smtp")).send();
        step("server settings: use the fake mail server").put("/api/server-settings").as("admin")
                .json(obj("values", obj("SMTP_PORT", port))).send();
        step("config: the mail server in effect").get("/api/config").as("maker").send();

        step("email settings: send a test email").post("/api/email-settings/test-email").as("admin").json(obj("to", " ops@nagad.com.bd ")).send();
        step("email settings: test email to an invalid address 422").post("/api/email-settings/test-email").as("admin")
                .json(obj("to", "nobody")).send();
        step("email settings: test email refused by the mail server 502").post("/api/email-settings/test-email").as("admin")
                .json(obj("to", "bad.inbox@nagad.com.bd")).send();
        step("email settings: test email as a user 403").post("/api/email-settings/test-email").as("maker")
                .json(obj("to", "ops@nagad.com.bd")).send();
        outbox("mail server: test email");
    }

    // ================================================================ sending

    private void sending() throws Exception {
        step("pending: send a failed email again (one address refused)").post("/api/pending-emails/send").as("admin")
                .json(obj("ids", list(email1))).send();
        step("pending: send both (one already sent)").post("/api/pending-emails/send").as("admin")
                .json(obj("ids", list(email2, email1))).send();
        step("pending: send an email that was already sent 409").post("/api/pending-emails/send").as("admin")
                .json(obj("ids", list(email1))).send();
        outbox("mail server: partner emails");
        step("pending: list is empty").get("/api/pending-emails").as("admin").send();
        step("partners: the refused address is flagged").get("/api/partners").as("maker").send();
        step("emails: an admin can't edit a sent email 400").put("/api/emails/" + email1).as("admin")
                .json(obj("subject", "x", "body_html", "<p>x</p>")).send();
        step("emails: list after sending").get("/api/uploads/" + upload1 + "/emails").as("maker").send();
        step("uploads: list shows the email counts").get("/api/uploads").as("maker").send();
    }

    // ================================================================ sent emails and follow-ups

    private void sentEmailsAndFollowUps() throws Exception {
        String e1 = "/api/emails/" + email1, e2 = "/api/emails/" + email2;
        step("sent: list").get("/api/sent-emails").as("checker").send();
        step("sent: search").get("/api/sent-emails?q=rocket").as("checker").send();
        step("sent: search with several words").get("/api/sent-emails?q=Maker%20sample.xlsx").as("checker").send();
        step("sent: one partner, oldest first").get("/api/sent-emails?partner_id=" + bkashId + "&sort=oldest&limit=10").as("checker").send();
        step("sent: date range").get("/api/sent-emails?date_from=2000-01-01&date_to=2099-12-31").as("checker").send();
        step("sent: date range with nothing in it").get("/api/sent-emails?date_from=2000-01-01&date_to=2000-01-02").as("checker").send();
        step("sent: 'from' after 'to' 400").get("/api/sent-emails?date_from=2099-01-02&date_to=2099-01-01").as("checker").send();
        step("sent: invalid sort 422").get("/api/sent-emails?sort=sideways").as("checker").send();
        step("sent: invalid date 422").get("/api/sent-emails?date_from=yesterday").as("checker").send();
        step("sent: invalid partner id 422").get("/api/sent-emails?partner_id=x").as("checker").send();
        step("sent: invalid reply filter 422").get("/api/sent-emails?reply=maybe").as("checker").send();
        step("sent: follow-ups due").get("/api/sent-emails?reply=due").as("checker").send();
        step("sent: summary").get("/api/sent-emails/summary").as("checker").send();
        step("sent: summary of a search").get("/api/sent-emails/summary?q=bkash&date_from=2000-01-01").as("checker").send();
        step("sent: export (.xlsx)").get("/api/sent-emails/export?reply=not_received").as("checker").send();
        step("sent: export with an invalid filter 422").get("/api/sent-emails/export?reply=maybe").as("checker").send();
        step("sent: history").get(e1 + "/events").as("maker").send();

        Resp draft1 = step("sent: follow-up draft (the refused address is left out)").get(e1 + "/followup-draft").as("maker").send();
        Resp draft2 = step("sent: follow-up draft from the reminder template").get(e2 + "/followup-draft").as("maker").send();
        Map<String, Object> followup1 = obj("to", list("dispute@bkash.com", "DISPUTE@bkash.com", " "), "cc", list("recon.team@nagad.com.bd"),
                "subject", draft1.str("/subject"), "body_html", draft1.str("/body_html"));
        step("sent: send a follow-up").post(e1 + "/followups").as("maker").json(followup1).send();
        step("sent: a second follow-up right away 409").post(e1 + "/followups").as("maker").json(followup1).send();
        step("sent: follow-up with invalid fields 422").post(e1 + "/followups").as("maker")
                .json(obj("to", list("not-an-address"), "cc", "x", "subject", "", "body_html", "<p>x</p>")).send();
        step("sent: follow-up without recipients 400").post(e1 + "/followups").as("maker")
                .json(obj("to", list(), "subject", "Re: x", "body_html", "<p>x</p>")).send();
        step("sent: follow-up with an empty body 400").post(e1 + "/followups").as("maker")
                .json(obj("to", list("dispute@bkash.com"), "subject", "Re: x", "body_html", "   ")).send();
        step("sent: follow-up for an unknown email 404").post("/api/emails/9999/followups").as("maker")
                .json(obj("to", list("dispute@bkash.com"), "subject", "Re: x", "body_html", "<p>x</p>")).send();
        step("sent: follow-up refused by the mail server 502").post(e2 + "/followups").as("maker")
                .json(obj("to", list("bad.claims@rocket.com.bd"), "subject", draft2.str("/subject"), "body_html", draft2.str("/body_html")))
                .send();
        step("sent: follow-up after the refused one").post(e2 + "/followups").as("admin")
                .json(obj("to", draft2.at("/to"), "cc", draft2.at("/cc"), "subject", draft2.str("/subject"),
                        "body_html", draft2.str("/body_html"))).send();
        outbox("mail server: follow-ups");

        step("sent: mark replied").put(e1 + "/reply-status").as("admin")
                .json(obj("status", "received", "note", " Partner replied by phone. ")).send();
        step("sent: same status again (no change)").put(e1 + "/reply-status").as("admin").json(obj("status", "received")).send();
        step("sent: users can't set the reply status 403").put(e1 + "/reply-status").as("maker").json(obj("status", "received")).send();
        step("sent: invalid reply status 422").put(e1 + "/reply-status").as("admin").json(obj("status", "maybe", "note", 5)).send();
        step("sent: follow-up draft after a reply").get(e1 + "/followup-draft").as("maker").send();
        step("sent: add a note").post(e1 + "/notes").as("admin").json(obj("note", "Called the partner.")).send();
        step("sent: empty note 422").post(e1 + "/notes").as("admin").json(obj("note", "   ")).send();
        step("sent: users can't add notes 403").post(e1 + "/notes").as("maker").json(obj("note", "x")).send();
        step("sent: note on an unknown email 404").post("/api/emails/9999/notes").as("admin").json(obj("note", "x")).send();
        step("sent: set remarks").put(e1 + "/remarks").as("admin").json(obj("remarks", "Waiting for settlement.")).send();
        step("sent: clear remarks").put(e1 + "/remarks").as("admin").json(obj("remarks", "")).send();
        step("sent: remarks too long 422").put(e1 + "/remarks").as("admin").json(obj("remarks", "x".repeat(501))).send();
        step("sent: users can't set remarks 403").put(e1 + "/remarks").as("maker").json(obj("remarks", "x")).send();
        step("sent: remarks for Rocket").put(e2 + "/remarks").as("admin").json(obj("remarks", "Claim checked by Ops")).send();
        step("sent: reconcile").put(e1 + "/reply-status").as("admin").json(obj("status", "reconciled")).send();
        step("sent: follow-up draft of a reconciled email 409").get(e1 + "/followup-draft").as("maker").send();
        step("sent: follow-up of a reconciled email 409").post(e1 + "/followups").as("maker").json(followup1).send();
        step("sent: history after the follow-ups").get(e1 + "/events").as("maker").send();
        step("sent: history of Rocket").get(e2 + "/events").as("maker").send();
        step("sent: conversation").get(e1 + "/conversation").as("maker").send();
        step("sent: conversation of Rocket").get(e2 + "/conversation").as("maker").send();
        step("sent: conversation of an unknown email 404").get("/api/emails/9999/conversation").as("maker").send();
        step("sent: list after the follow-ups").get("/api/sent-emails?sort=oldest").as("admin").send();
        step("sent: search in remarks").get("/api/sent-emails?q=ops").as("admin").send();
        step("sent: reconciled only").get("/api/sent-emails?reply=reconciled").as("admin").send();
        step("sent: summary after the follow-ups").get("/api/sent-emails/summary").as("admin").send();
    }

    // ================================================================ Email Configuration

    private void emailSettings() throws Exception {
        step("email settings: get").get("/api/email-settings").as("maker").send();
        step("email settings: users can't change them 403").put("/api/email-settings").as("maker")
                .json(obj("followup_due_days", 3, "auto_followup_enabled", false, "auto_followup_max", 3, "cc_emails", list())).send();
        step("email settings: invalid values 422").put("/api/email-settings").as("admin")
                .json(obj("followup_due_days", 0, "auto_followup_enabled", "sometimes", "auto_followup_max", 21,
                        "cc_emails", list("not-an-address"))).send();
        step("email settings: missing fields 422").put("/api/email-settings").as("admin").json(obj()).send();
        step("email settings: save (automatic follow-ups on, CC)").put("/api/email-settings").as("admin")
                .json(obj("followup_due_days", 5, "auto_followup_enabled", true, "auto_followup_max", 2,
                        "cc_emails", list("Recon.Team@nagad.com.bd", "recon.team@nagad.com.bd", " "))).send();
        step("email settings: look for due follow-ups now (none due)").post("/api/email-settings/run-followups").as("admin").send();
        step("email settings: run follow-ups as a user 403").post("/api/email-settings/run-followups").as("maker").send();
        step("email settings: automatic follow-ups off again").put("/api/email-settings").as("admin")
                .json(obj("followup_due_days", 3, "auto_followup_enabled", false, "auto_followup_max", 3,
                        "cc_emails", list("recon.team@nagad.com.bd"))).send();
        step("sent: summary with the new settings").get("/api/sent-emails/summary").as("maker").send();
    }

    // ================================================================ the other uploads

    private void moreUploads() throws Exception {
        step("emails: prepare the repeated upload (nothing to send)").post("/api/uploads/" + upload2 + "/prepare-emails").as("maker").send();
        step("emails: submit the repeated upload 400").post("/api/uploads/" + upload2 + "/submit").as("maker").send();
        Resp prepared = step("emails: prepare the CSV upload (some partners get no email)")
                .post("/api/uploads/" + upload3 + "/prepare-emails").as("maker").json(obj("templates", obj())).send();
        email3 = prepared.idWhere("/emails", "partner_name", "bKash");
        step("partners: clear the refused flag").put("/api/partners/" + bkashId).as("maker")
                .json(obj("name", "bKash", "is_active", true, "emails", list(
                        obj("email", "dispute@bkash.com"),
                        obj("email", "bad.box@bkash.com", "clear_problem", true)))).send();
        step("emails: the draft goes to the cleared address too").get("/api/uploads/" + upload3 + "/emails").as("maker").send();
        step("emails: submit the CSV upload").post("/api/uploads/" + upload3 + "/submit").as("maker").send();
        step("pending: one email waiting").get("/api/pending-emails").as("checker").send();
        step("pending: export with one email (.xlsx)").get("/api/pending-emails/export").as("checker").send();
        step("uploads: list with every upload").get("/api/uploads").as("checker").send();
    }

    // ================================================================ sign-in with an emailed code (mail works now)

    private void signInWithCodes() throws Exception {
        step("sign-in: password right, code emailed").post("/api/auth/login").browser("maker-browser")
                .json(obj("login", "maker.user", "password", PASSWORD)).send();
        String code = codeSentTo("maker@nagad.com.bd");
        step("sign-in: another code too soon 429").post("/api/auth/resend-code").json(obj("login", "maker.user")).send();
        step("sign-in: wrong code 400").post("/api/auth/verify-code")
                .json(obj("login", "maker.user", "code", wrongCode(code))).send();
        Resp verified = step("sign-in: right code, keep me signed in").post("/api/auth/verify-code").browser("maker-browser")
                .json(obj("login", " Maker.User ", "code", code, "remember_me", true)).send();
        rec.persona("maker", verified.str("/access_token"));
        step("sign-in: a code works once 400").post("/api/auth/verify-code").json(obj("login", "maker.user", "code", code)).send();
        // the last code was used, so another one may be sent at once (no 60-second wait)
        step("sign-in: another code after the last one was used").post("/api/auth/resend-code").json(obj("login", "maker.user")).send();
        step("sign-in: trusted browser signs in without a code").post("/api/auth/login").browser("maker-browser")
                .json(obj("login", "maker@nagad.com.bd", "password", PASSWORD, "remember_me", true)).send();
        // a code went out less than a minute ago and is still unused: no new email, the page asks for that code
        step("sign-in: unticking keep me signed in forgets the browser").post("/api/auth/login").browser("maker-browser")
                .json(obj("login", "maker.user", "password", PASSWORD)).send();
        outbox("mail server: sign-in codes");

        step("sign-in: another code for an unknown account 400").post("/api/auth/resend-code").json(obj("login", "nobody")).send();
        step("sign-in: code for an unknown account 400").post("/api/auth/verify-code").json(obj("login", "nobody", "code", "123456")).send();
        step("sign-in: code for a sign-up that doesn't exist 400").post("/api/auth/verify-code")
                .json(obj("login", "nobody", "code", "123456", "registration", true)).send();
        step("sign-in: another code for a sign-up that doesn't exist 400").post("/api/auth/resend-code")
                .json(obj("login", "nobody", "registration", true)).send();
        step("sign-in: code with invalid fields 422").post("/api/auth/verify-code")
                .json(obj("login", null, "code", 123456, "remember_me", "x")).send();

        Resp sessions = step("auth: sessions (user)").get("/api/auth/sessions").as("maker").browser("maker-browser").sortBy("id").send();
        int otherSession = -1;
        if (sessions.json() != null) {
            for (JsonNode s : sessions.json()) if (!s.path("current").asBoolean()) otherSession = s.path("id").asInt(-1);
        }
        step("auth: sign out another session").delete("/api/auth/sessions/" + otherSession).as("maker").send();
        step("auth: sign out someone else's session 404").delete("/api/auth/sessions/" + adminSessionId).as("maker").send();
        step("auth: sign out a session with a non-integer id 422").delete("/api/auth/sessions/abc").as("maker").send();
        step("auth: sessions after signing one out").get("/api/auth/sessions").as("maker").browser("maker-browser").sortBy("id").send();
        step("auth: my activity (user)").get("/api/auth/activity").as("maker").sortBy("id").send();
    }

    // ================================================================ My Profile

    private void profileAndPassword() throws Exception {
        step("profile: new name and email (code emailed)").put("/api/auth/me").as("maker")
                .json(obj("full_name", "  Maker   Person ", "email", "Maker.New@nagad.com.bd")).send();
        String code = codeSentTo("maker.new@nagad.com.bd");
        step("profile: another code too soon 429").post("/api/auth/me/resend-email-code").as("maker").send();
        step("profile: wrong code 400").post("/api/auth/me/confirm-email").as("maker").json(obj("code", wrongCode(code))).send();
        step("profile: confirm without a code 422").post("/api/auth/me/confirm-email").as("maker").json(obj()).send();
        step("profile: confirm the new email").post("/api/auth/me/confirm-email").as("maker").json(obj("code", code)).send();
        step("profile: confirm with nothing waiting 400").post("/api/auth/me/confirm-email").as("maker").json(obj("code", "123456")).send();
        step("profile: resend with nothing waiting 400").post("/api/auth/me/resend-email-code").as("maker").send();
        step("profile: start another change").put("/api/auth/me").as("maker")
                .json(obj("full_name", "Maker Person", "email", "maker.other@nagad.com.bd")).send();
        step("profile: cancel it").delete("/api/auth/me/pending-email").as("maker").send();
        step("profile: an email another account uses 409").put("/api/auth/me").as("maker")
                .json(obj("full_name", "Maker Person", "email", "checker@nagad.com.bd")).send();
        step("profile: invalid fields 422").put("/api/auth/me").as("maker").json(obj("full_name", "M", "email", "nope")).send();
        step("profile: me").get("/api/auth/me").as("maker").send();
        outbox("mail server: email change codes");

        String newPassword = "An0ther!Passw0rd";
        step("password: wrong current password 400").post("/api/auth/change-password").as("maker").browser("maker-browser")
                .json(obj("current_password", WRONG_PASSWORD, "new_password", newPassword)).send();
        step("password: same as the current one 400").post("/api/auth/change-password").as("maker").browser("maker-browser")
                .json(obj("current_password", PASSWORD, "new_password", PASSWORD)).send();
        step("password: too weak 400").post("/api/auth/change-password").as("maker").browser("maker-browser")
                .json(obj("current_password", PASSWORD, "new_password", "short")).send();
        step("password: missing fields 422").post("/api/auth/change-password").as("maker").json(obj()).send();
        Resp changed = step("password: change it").post("/api/auth/change-password").as("maker").browser("maker-browser")
                .json(obj("current_password", PASSWORD, "new_password", newPassword)).send();
        rec.persona("maker-old-token", rec.token("maker"));
        rec.persona("maker", changed.str("/access_token"));
        step("password: the old token no longer works 401").get("/api/auth/me").as("maker-old-token").send();
        step("password: the new token works").get("/api/auth/me").as("maker").send();
        step("auth: refresh with the cookie of the new session").post("/api/auth/refresh").browser("maker-browser").send();
    }

    // ================================================================ forgot password

    private void passwordReset() throws Exception {
        String newPassword = "Res3t!Passw0rd#";
        step("password reset: ask for a code").post("/api/auth/forgot-password").json(obj("email", " Checker@Nagad.com.bd ")).send();
        // the code is emailed after the answer (a background task): wait for it
        smtp.await(m -> m.recipients().contains("checker@nagad.com.bd") && m.subject() != null
                && m.subject().contains("password reset code"), 20_000);
        String code = codeSentTo("checker@nagad.com.bd");
        step("password reset: unknown address gets the same answer").post("/api/auth/forgot-password")
                .json(obj("email", "nobody@nagad.com.bd")).send();
        step("password reset: missing email 422").post("/api/auth/forgot-password").json(obj()).send();
        step("password reset: too weak 400").post("/api/auth/reset-password")
                .json(obj("email", "checker@nagad.com.bd", "code", code, "new_password", "weak")).send();
        step("password reset: wrong code 400").post("/api/auth/reset-password")
                .json(obj("email", "checker@nagad.com.bd", "code", wrongCode(code), "new_password", newPassword)).send();
        step("password reset: unknown address 400").post("/api/auth/reset-password")
                .json(obj("email", "nobody@nagad.com.bd", "code", "123456", "new_password", newPassword)).send();
        step("password reset: set the new password").post("/api/auth/reset-password")
                .json(obj("email", "checker@nagad.com.bd", "code", code, "new_password", newPassword)).send();
        step("password reset: a code works once 400").post("/api/auth/reset-password")
                .json(obj("email", "checker@nagad.com.bd", "code", code, "new_password", newPassword)).send();
        step("password reset: missing fields 422").post("/api/auth/reset-password").json(obj("email", "checker@nagad.com.bd")).send();
        step("password reset: the old session ended 401").get("/api/auth/me").as("checker").send();
        step("sign-in: the new password is accepted (code emailed)").post("/api/auth/login")
                .json(obj("login", "checker@nagad.com.bd", "password", newPassword)).send();
        outbox("mail server: reset and sign-in codes");
        rec.persona("checker", ApiTestServer.tokenFor("checker.user"));
    }

    // ================================================================ lock-out and rate limits (from addresses of their own)

    private void lockOutAndRateLimits() throws Exception {
        String attacker = "203.0.113.50";
        for (int i = 1; i <= 5; i++) {
            step("lock-out: wrong password " + i + " of 5").post("/api/auth/login").header("X-Forwarded-For", attacker)
                    .json(obj("login", "lock.user", "password", WRONG_PASSWORD)).send();
        }
        step("lock-out: locked even with the right password 423").post("/api/auth/login").header("X-Forwarded-For", attacker)
                .json(obj("login", "lock.user", "password", LOCK_PASSWORD)).send();
        step("lock-out: users list shows the lock").get("/api/users").as("admin").send();
        step("lock-out: the admin unlocks").patch("/api/users/" + lockId).as("admin").json(obj("unlock", true)).send();
        step("lock-out: deactivate the account").patch("/api/users/" + lockId).as("admin").json(obj("is_active", false)).send();
        step("lock-out: a deactivated account can't sign in 403").post("/api/auth/login").header("X-Forwarded-For", attacker)
                .json(obj("login", "lock.user", "password", LOCK_PASSWORD)).send();
        step("lock-out: activate again").patch("/api/users/" + lockId).as("admin").json(obj("is_active", true)).send();

        String flood = "203.0.113.99";
        Map<String, Object> signUp = obj("full_name", "Flood Person", "username", "flood.person", "email", "flood@nagad.com.bd",
                "password", PASSWORD, "role", "user");
        for (int i = 1; i <= 5; i++) {
            step("rate limit: sign-up attempt " + i + " (refused, no sign-up) 403").post("/api/auth/register")
                    .header("X-Forwarded-For", flood).json(signUp).send();
        }
        step("rate limit: sixth sign-up attempt within a minute 429").post("/api/auth/register").header("X-Forwarded-For", flood)
                .json(signUp).send();
    }

    // ================================================================ partner replies and the mailbox (no mailbox in the tests)

    private void repliesAndInbox() throws Exception {
        step("replies: summary").get("/api/replies/summary").as("maker").send();
        step("replies: list").get("/api/replies").as("maker").send();
        step("replies: other partner mail, unread, search").get("/api/replies?category=partner_mail&show=unread&q=bkash&partner_id=" + bkashId)
                .as("maker").send();
        step("replies: to review").get("/api/replies?category=review").as("admin").send();
        step("replies: invalid category 422").get("/api/replies?category=spam").as("maker").send();
        step("replies: invalid show 422").get("/api/replies?show=some").as("maker").send();
        step("replies: unknown message 404").get("/api/replies/9999").as("maker").send();
        step("replies: non-integer id 422").get("/api/replies/abc").as("maker").send();
        step("replies: mark an unknown message read 404").put("/api/replies/9999/read").as("maker").json(obj("read", true)).send();
        step("replies: mark read without a body 422").put("/api/replies/9999/read").as("maker").send();
        // Not recorded: {"read": "perhaps"} answers 500 today (MoreApi.setReplyRead unboxes the missing value before
        // reporting the 422), which no reimplementation should copy.
        step("replies: mark all read").post("/api/replies/read-all").as("maker").send();
        step("replies: mark one list read").post("/api/replies/read-all?category=review").as("maker").send();
        step("replies: mark an invalid list read 422").post("/api/replies/read-all?category=spam").as("maker").send();
        step("replies: link an unknown message 404").post("/api/replies/9999/link").as("admin").json(obj("email_id", email1)).send();
        step("replies: link without an email id 422").post("/api/replies/9999/link").as("admin").json(obj()).send();
        step("replies: users can't link messages 403").post("/api/replies/9999/link").as("maker").json(obj("email_id", email1)).send();
        step("replies: dismiss an unknown message 404").post("/api/replies/9999/dismiss").as("admin").send();
        step("replies: users can't dismiss messages 403").post("/api/replies/9999/dismiss").as("maker").send();
        step("replies: unknown attachment 404").get("/api/replies/9999/attachments/1").as("maker").send();
        step("replies: attachment with a non-integer id 422").get("/api/replies/9999/attachments/abc").as("maker").send();
        step("inbox: issues").get("/api/inbox-issues").as("admin").send();
        step("inbox: issues are for admins 403").get("/api/inbox-issues").as("maker").send();
        step("inbox: retry an unknown issue 502").post("/api/inbox-issues/9999/retry").as("admin").send();
        step("inbox: dismiss an unknown issue 404").post("/api/inbox-issues/9999/dismiss").as("admin").send();
        step("inbox: check now without a mailbox 502").post("/api/replies/check-now").as("admin").send();
        step("inbox: check now as a user 403").post("/api/replies/check-now").as("maker").send();
        step("replies: summary after the failed check").get("/api/replies/summary").as("admin").send();
    }

    // ================================================================ answers to partner replies (no replies without a mailbox)

    private void answers() throws Exception {
        Map<String, Object> answer = obj("to", list("dispute@bkash.com"), "cc", list(), "subject", "Re: Settlement pending",
                "text", "Thank you.\n\nWe will check and come back.");
        step("answers: list (admin)").get("/api/answers").as("admin").send();
        step("answers: list (user)").get("/api/answers").as("maker").send();
        step("answers: answer an unknown message 404").post("/api/replies/9999/answers").as("maker").json(answer).send();
        step("answers: invalid answer 422").post("/api/replies/9999/answers").as("maker")
                .json(obj("to", list(), "cc", list("nope"), "subject", " ", "text", "")).send();
        step("answers: change an unknown answer 404").put("/api/answers/9999").as("maker").json(answer).send();
        step("answers: cancel an unknown answer 404").post("/api/answers/9999/cancel").as("maker").send();
        step("answers: reject an unknown answer 404").post("/api/answers/9999/reject").as("admin").json(obj("note", "No")).send();
        step("answers: approve an unknown answer 404").post("/api/answers/9999/approve").as("admin").send();
        step("answers: users can't approve 403").post("/api/answers/9999/approve").as("maker").send();
        step("answers: users can't reject 403").post("/api/answers/9999/reject").as("maker").json(obj("note", "No")).send();
        step("answers: non-integer id 422").post("/api/answers/abc/cancel").as("maker").send();
    }

    // ================================================================ overview pages

    private void overview() throws Exception {
        step("overview: config").get("/api/config").as("admin").send();
        step("overview: attention (built-in admin)").get("/api/attention").as("admin").send();
        step("overview: attention (other admin)").get("/api/attention").as("ops").send();
        step("overview: attention (user)").get("/api/attention").as("maker").send();
        step("overview: activity fingerprint").get("/api/activity").as("maker").send();
        step("overview: activity needs a sign-in 401").get("/api/activity").send();
        step("overview: email settings").get("/api/email-settings").as("admin").send();
        step("overview: server settings").get("/api/server-settings").as("admin").send();
        step("overview: change requests, everything").get("/api/change-requests?status=all").as("admin").send();
        step("overview: users").get("/api/users").as("admin").send();
        step("overview: audit log").get("/api/users/events?limit=500").as("admin").send();
        step("overview: partners").get("/api/partners").as("ops").send();
        step("overview: templates").get("/api/email-templates").as("ops").send();
    }

    // ================================================================ sign out

    private void signOut() throws Exception {
        String cookie = rec.cookie("admin-browser", "npsb_refresh");
        step("auth: sign out").post("/api/auth/logout").browser("admin-browser").send();
        rec.setCookie("signed-out-tab", "npsb_refresh", cookie, "/api/auth");
        step("auth: refresh after signing out 401").post("/api/auth/refresh").browser("signed-out-tab").send();
        step("auth: sign out without a cookie").post("/api/auth/logout").send();
        step("auth: sign out everywhere").post("/api/auth/logout-all").as("checker").send();
        step("auth: the token no longer works after signing out everywhere 401").get("/api/auth/me").as("checker").send();
        step("auth: sign out everywhere needs a sign-in 401").post("/api/auth/logout-all").send();
    }
}
