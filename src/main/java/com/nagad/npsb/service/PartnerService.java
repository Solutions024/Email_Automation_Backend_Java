package com.nagad.npsb.service;

import com.nagad.npsb.dto.PartnerRequest;
import com.nagad.npsb.dto.PartnerRequest.PartnerEmailRequest;
import com.nagad.npsb.entity.ChangeRequest;
import com.nagad.npsb.entity.EmailDispatch;
import com.nagad.npsb.entity.Partner;
import com.nagad.npsb.entity.PartnerEmail;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.ChangeRequestRepository;
import com.nagad.npsb.repository.PartnerEmailRepository;
import com.nagad.npsb.repository.PartnerRepository;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nagad.npsb.service.dispatch.TransactionGroups.nameKey;

/**
 * Partners (Bank / MFS / PSP) and their recipient addresses. A partner is known by its NPSB_ORG_CODE (unique; "0103"
 * and "103" are the same code), which file rows are matched by. Anyone signed in changes partners directly: no
 * approval needed (Approvals.DIRECT); every change is still kept in the change history.
 */
@Service
public class PartnerService {
    private final Approvals approvals;
    private final EmailCheck emailCheck;
    private final Sql sql;
    private final EntityManager em;
    private final PartnerRepository partners;
    private final PartnerEmailRepository partnerEmails;
    private final ChangeRequestRepository changeRequests;
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    public PartnerService(Approvals approvals, EmailCheck emailCheck, Sql sql, EntityManager em, PartnerRepository partners,
                          PartnerEmailRepository partnerEmails, ChangeRequestRepository changeRequests) {
        this.approvals = approvals;
        this.emailCheck = emailCheck;
        this.sql = sql;
        this.em = em;
        this.partners = partners;
        this.partnerEmails = partnerEmails;
        this.changeRequests = changeRequests;
    }

    @PostConstruct
    void registerApprovals() {
        approvals.register("partner", this::partnerSnapshot,
                Map.<String, Approvals.Apply>of("create", this::applyPartnerCreate, "update", this::applyPartnerUpdate));
    }

    // ---------------------------------------------------------------- helpers

    /** partner.emails(): the partner's addresses, oldest first. */
    public List<PartnerEmail> emailsOf(Partner p) {
        return p.getId() == null ? new ArrayList<>() : partnerEmails.findByPartnerIdOrderByIdAsc(p.getId());
    }

    public Map<String, Object> partnerToDict(Partner p) {
        List<Object> emails = new ArrayList<>();
        for (PartnerEmail e : emailsOf(p)) {
            emails.add(Py.map("id", e.getId(), "email", e.getEmail(), "is_active", e.getIsActive(), "problem", e.getProblem(),
                    "problem_at", Py.iso(e.getProblemAt())));
        }
        return Py.map("id", p.getId(), "org_code", p.getOrgCode(), "name", p.getName(), "is_active", p.getIsActive(),
                "emails", emails);
    }

    /**
     * New PartnerEmail rows from the form. New addresses must be able to receive mail; a "refused" flag stays on
     * an unchanged address unless the user cleared it.
     */
    List<PartnerEmail> buildPartnerEmails(List<PartnerEmailRequest> emails, List<PartnerEmail> existing) {
        Map<String, PartnerEmail> old = new HashMap<>();
        for (PartnerEmail e : existing) old.put(e.getEmail().toLowerCase(Locale.ROOT), e);
        Set<String> seen = new HashSet<>();
        List<PartnerEmail> result = new ArrayList<>();
        for (PartnerEmailRequest e : emails) {
            if (seen.contains(e.email())) throw new ApiException(400, e.email() + " is listed twice.");
            seen.add(e.email());
            PartnerEmail previous = old.get(e.email());
            if (previous == null) {
                try {
                    emailCheck.validateRealEmail(e.email());
                } catch (ApiException exc) {
                    throw new ApiException(exc.status(), e.email() + ": " + exc.text());
                }
            }
            PartnerEmail row = new PartnerEmail();
            row.setEmail(e.email());
            row.setIsActive(e.isActive());
            if (previous != null && !Py.isEmpty(previous.getProblem()) && !e.clearProblem()) {
                row.setProblem(previous.getProblem());
                row.setProblemAt(previous.getProblemAt());
            }
            result.add(row);
        }
        return result;
    }

    /** The cleaned name, or 400: required and unique (ignoring case). Partners are matched to file rows by code, not by name. */
    String ensureUniquePartnerName(String name, Integer partnerId) {
        name = Py.squash(name);
        if (name.isEmpty()) throw new ApiException(400, "Enter the partner name.");
        for (Partner other : sql.all(Partner.class, "WHERE t.id != ?", partnerId == null ? 0 : partnerId)) {
            if (nameKey(other.getName()).equals(nameKey(name))) {
                throw new ApiException(400, "A partner named \"" + other.getName() + "\" already exists.");
            }
        }
        return name;
    }

    /**
     * The partner's NPSB_ORG_CODE without leading zeros, or 400: a whole number, not another partner's code
     * ("0103" and "103" are the same). Left out on an update: the partner keeps its code.
     */
    String ensureUniqueOrgCode(String raw, Partner partner) {
        if (raw == null || raw.isBlank()) {
            if (partner != null && partner.getOrgCode() != null) return partner.getOrgCode();
            throw new ApiException(400, "Enter the partner's NPSB_ORG_CODE (a number, e.g. 103).");
        }
        String code = OrgCodes.normalize(raw);
        if (code == null) {
            throw new ApiException(400, "NPSB_ORG_CODE must be a number of up to " + OrgCodes.MAX_DIGITS + " digits (e.g. 103), not '"
                    + Py.strip(raw) + "'.");
        }
        Partner other = partners.findByOrgCode(code).orElse(null);
        if (other != null && (partner == null || !other.getId().equals(partner.getId()))) {
            throw new ApiException(400, "Partner \"" + other.getName() + "\" already has the code " + code
                    + " (leading zeros don't matter: 0" + code + " is the same code).");
        }
        return code;
    }

    /** Valid name, code and addresses (new addresses must accept mail). Nothing is changed. */
    void checkPartner(PartnerRequest data, Partner partner) {
        ensureUniquePartnerName(data.name(), partner != null ? partner.getId() : null);
        ensureUniqueOrgCode(data.orgCode(), partner);
        buildPartnerEmails(data.emails(), partner != null ? emailsOf(partner) : List.of());
    }

    /** "103 · bKash": how a partner is named in the change history. */
    static String label(String code, String name) {
        String n = Py.squash(name);
        return code == null ? n : code + " · " + n;
    }

    /**
     * partner.replaceEmails(rows): removed addresses are deleted, the new rows added (inserted now; the deletes are
     * written at the next flush, after the inserts, as before).
     */
    private void replaceEmails(Partner partner, List<PartnerEmail> rows, List<PartnerEmail> existing) {
        for (PartnerEmail old : existing) {
            if (!rows.contains(old)) partnerEmails.delete(old);
        }
        for (PartnerEmail row : rows) {
            row.setPartnerId(partner.getId());
            partnerEmails.save(row);
        }
    }

    // ---------------------------------------------------------------- maker-checker (Approvals)

    /** What a partner change compares against (not the refused flags: those change by themselves). */
    Map<String, Object> partnerSnapshot(int partnerId) {
        Partner p = partners.findById(partnerId).orElse(null);
        if (p == null) return null;
        List<Object> emails = new ArrayList<>();
        for (PartnerEmail e : emailsOf(p)) emails.add(Py.map("email", e.getEmail(), "is_active", e.getIsActive()));
        return Py.map("org_code", p.getOrgCode(), "name", p.getName(), "is_active", p.getIsActive(), "emails", emails);
    }

    Integer applyPartnerCreate(Map<String, Object> after, Integer id, String by) {
        PartnerRequest data = PartnerRequest.fromStored(after);
        String name = ensureUniquePartnerName(data.name(), null);
        String code = ensureUniqueOrgCode(data.orgCode(), null);
        List<PartnerEmail> emails = buildPartnerEmails(data.emails(), List.of());
        Partner partner = new Partner();
        partner.setOrgCode(code);
        partner.setName(name);
        partner.setIsActive(data.isActive());
        partners.save(partner);
        em.flush();
        replaceEmails(partner, emails, List.of());
        em.flush();
        return partner.getId();
    }

    Integer applyPartnerUpdate(Map<String, Object> after, Integer partnerId, String by) {
        Partner partner = partners.findById(partnerId).orElse(null);
        if (partner == null) throw new ApiException(404, "Partner not found");
        PartnerRequest data = PartnerRequest.fromStored(after);
        partner.setName(ensureUniquePartnerName(data.name(), partnerId));
        partner.setOrgCode(ensureUniqueOrgCode(data.orgCode(), partner));
        partner.setIsActive(data.isActive());
        // replace the email list with what the UI sent (known problems stay with unchanged addresses)
        List<PartnerEmail> existing = emailsOf(partner);
        List<PartnerEmail> rows = buildPartnerEmails(data.emails(), existing);
        replaceEmails(partner, rows, existing);
        // emails waiting to be sent go to the new addresses
        List<String> toList = new ArrayList<>();
        for (PartnerEmail e : rows) if (Boolean.TRUE.equals(e.getIsActive()) && Py.isEmpty(e.getProblem())) toList.add(e.getEmail());
        if (!toList.isEmpty()) {
            for (EmailDispatch d : sql.all(EmailDispatch.class, "WHERE t.partner_id = ? AND t.status IN ('PREPARED', 'PENDING', 'FAILED')",
                    partner.getId())) {
                d.setToEmails(String.join(",", toList));
            }
        }
        em.flush();
        return partner.getId();
    }

    // ---------------------------------------------------------------- endpoints

    public List<Object> listPartners() {
        Map<Integer, Integer> pending = new HashMap<>();
        for (ChangeRequest r : sql.jpql(ChangeRequest.class,
                "SELECT t FROM ChangeRequest t WHERE t.entity = 'partner' AND t.status = 'pending'", 0)) {
            if (r.getEntityId() != null) pending.put(r.getEntityId(), r.getId());
        }
        List<Object> out = new ArrayList<>();
        // by code (7 before 103); partners still without a code first: they need one
        List<Partner> all = new ArrayList<>(sql.all(Partner.class, "ORDER BY t.name"));
        all.sort(Comparator.comparing((Partner p) -> p.getOrgCode() != null)
                .thenComparingInt(p -> p.getOrgCode() == null ? 0 : p.getOrgCode().length())
                .thenComparing(p -> p.getOrgCode() == null ? "" : p.getOrgCode()));
        for (Partner p : all) {
            Map<String, Object> m = partnerToDict(p);
            m.put("pending_request_id", pending.get(p.getId()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> createPartner(User user, PartnerRequest data) {
        checkPartner(data, null);
        Map<String, Object> result = approvals.submit(user, "partner", "create", null,
                label(OrgCodes.normalize(data.orgCode()), data.name()), data.dump());
        Partner partner = null;
        if (Boolean.TRUE.equals(result.get("applied"))) {  // maker-checker off: created now
            ChangeRequest request = changeRequests.findById(((Number) result.get("request_id")).intValue()).orElse(null);
            partner = request == null || request.getEntityId() == null ? null : partners.findById(request.getEntityId()).orElse(null);
        }
        return Py.map("result", result, "partner", partner != null ? partnerToDict(partner) : null);
    }

    public Map<String, Object> updatePartner(User user, int partnerId, PartnerRequest data) {
        Partner partner = partners.findById(partnerId).orElse(null);
        if (partner == null) throw new ApiException(404, "Partner not found");
        checkPartner(data, partner);
        Map<String, Object> result = approvals.submit(user, "partner", "update", partnerId,
                label(ensureUniqueOrgCode(data.orgCode(), partner), data.name()), data.dump());
        // the change was applied to this same (managed) row and committed: it holds what the database holds now
        return Py.map("result", result, "partner", partnerToDict(partner));
    }

    // ---------------------------------------------------------------- startup

    /**
     * Partners made before codes existed get their NPSB_ORG_CODE from their earlier uploads: the code the file rows
     * with their name carried, when that is one code that no other partner has. The others stay without a code
     * (shown as "code missing"; no emails are prepared for them) until someone enters it.
     */
    @Transactional
    public void fillOrgCodes() {
        List<Partner> missing = sql.all(Partner.class, "WHERE t.org_code IS NULL ORDER BY t.id");
        if (missing.isEmpty()) return;
        Map<String, Set<String>> codesByName = new HashMap<>();
        for (Object[] row : sql.rows("SELECT DISTINCT dest_partner_name, dest_institution_code FROM transactions "
                + "WHERE dest_institution_code IS NOT NULL")) {
            String code = OrgCodes.normalize(row[1]);
            if (code != null && row[0] != null) codesByName.computeIfAbsent(nameKey((String) row[0]), k -> new HashSet<>()).add(code);
        }
        Set<String> taken = new HashSet<>();
        for (Partner p : sql.all(Partner.class, "WHERE t.org_code IS NOT NULL")) taken.add(p.getOrgCode());
        Map<String, Integer> claims = new HashMap<>();  // a code two partners would get: neither gets it
        for (Partner p : missing) {
            Set<String> codes = codesByName.getOrDefault(nameKey(p.getName()), Set.of());
            if (codes.size() == 1) claims.merge(codes.iterator().next(), 1, Integer::sum);
        }
        int filled = 0;
        for (Partner p : missing) {
            Set<String> codes = codesByName.getOrDefault(nameKey(p.getName()), Set.of());
            if (codes.size() != 1) continue;
            String code = codes.iterator().next();
            if (taken.contains(code) || claims.get(code) > 1) continue;
            p.setOrgCode(code);
            partners.save(p);
            taken.add(code);
            filled++;
        }
        int left = missing.size() - filled;
        if (filled > 0) LOG.info("NPSB_ORG_CODE filled in from earlier uploads for {} partner(s)", filled);
        if (left > 0) LOG.warn("{} partner(s) have no NPSB_ORG_CODE yet: enter it on the Partners page (no emails are prepared for them until then)", left);
    }
}
