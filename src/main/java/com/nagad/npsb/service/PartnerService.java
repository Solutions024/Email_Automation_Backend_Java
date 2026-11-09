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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nagad.npsb.service.dispatch.TransactionGroups.nameKey;

/**
 * Partners (Bank / MFS / PSP) and their recipient addresses. Anyone signed in changes partners directly: no approval
 * needed (Approvals.DIRECT); every change is still kept in the change history.
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
        return Py.map("id", p.getId(), "name", p.getName(), "is_active", p.getIsActive(), "emails", emails);
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

    /** The cleaned name, or 400: it must match KEY_ORG_NM in the files and be unique (ignoring case). */
    String ensureUniquePartnerName(String name, Integer partnerId) {
        name = Py.squash(name);
        if (name.isEmpty()) throw new ApiException(400, "Enter the partner name exactly as in the KEY_ORG_NM column.");
        for (Partner other : sql.all(Partner.class, "WHERE t.id != ?", partnerId == null ? 0 : partnerId)) {
            if (nameKey(other.getName()).equals(nameKey(name))) {
                throw new ApiException(400, "A partner named \"" + other.getName() + "\" already exists.");
            }
        }
        return name;
    }

    /** Valid name and addresses (new addresses must accept mail). Nothing is changed. */
    void checkPartner(PartnerRequest data, Partner partner) {
        ensureUniquePartnerName(data.name(), partner != null ? partner.getId() : null);
        buildPartnerEmails(data.emails(), partner != null ? emailsOf(partner) : List.of());
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
        return Py.map("name", p.getName(), "is_active", p.getIsActive(), "emails", emails);
    }

    Integer applyPartnerCreate(Map<String, Object> after, Integer id, String by) {
        PartnerRequest data = PartnerRequest.fromStored(after);
        String name = ensureUniquePartnerName(data.name(), null);
        List<PartnerEmail> emails = buildPartnerEmails(data.emails(), List.of());
        Partner partner = new Partner();
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
        for (Partner p : sql.all(Partner.class, "ORDER BY t.name")) {
            Map<String, Object> m = partnerToDict(p);
            m.put("pending_request_id", pending.get(p.getId()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> createPartner(User user, PartnerRequest data) {
        checkPartner(data, null);
        Map<String, Object> result = approvals.submit(user, "partner", "create", null, Py.squash(data.name()), data.dump());
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
        Map<String, Object> result = approvals.submit(user, "partner", "update", partnerId, Py.squash(data.name()), data.dump());
        // the change was applied to this same (managed) row and committed: it holds what the database holds now
        return Py.map("result", result, "partner", partnerToDict(partner));
    }
}
