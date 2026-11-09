package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.ChangeRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.ChangeRequestRepository;
import com.nagad.npsb.service.support.SqlRows;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.util.PyJson;
import com.nagad.npsb.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maker-checker for configuration: users' changes to email templates and template variables wait until an
 * admin approves them. Admins' own changes, and partner changes (DIRECT), are applied at once and only recorded
 * in the history.
 *
 * A user's change is first a request (maker). It is applied only when an admin approves it
 * (checker). Approving fails if the thing was changed in the meantime (the request was made against an
 * older version): the maker then asks again. Every applied change stays in change_requests, so it is also
 * the change history. With maker-checker switched off (System Settings), changes apply at once and are
 * recorded as "applied". System Settings changes are only recorded, never held for approval.
 *
 * The API registers, for each kind of thing, how to read its current state (snapshot) and how to apply
 * each action.
 */
@Service
public class Approvals {

    public static final Map<String, String> ENTITY_LABELS = Map.of("partner", "Partner", "template", "Email template",
            "variable", "Template variable", "settings", "System Settings");
    public static final Map<String, String> ACTION_LABELS = Map.of("create", "New", "update", "Change", "delete", "Delete",
            "default", "Make default");

    @FunctionalInterface
    public interface Snapshot {
        /** The current state (null: it no longer exists). */
        Map<String, Object> of(int id);
    }

    @FunctionalInterface
    public interface Apply {
        Integer apply(Map<String, Object> after, Integer id, String by);
    }

    private record Kind(Snapshot snapshot, Map<String, Apply> apply) {}

    /** Registered by the owners of each kind of thing (at startup, @PostConstruct). */
    private final Map<String, Kind> kinds = new ConcurrentHashMap<>();

    private final LiveSettings live;
    private final ChangeRequestRepository changeRequests;
    private final JdbcClient jdbc;

    public Approvals(LiveSettings live, ChangeRequestRepository changeRequests, JdbcClient jdbc) {
        this.live = live;
        this.changeRequests = changeRequests;
        this.jdbc = jdbc;
    }

    public void register(String entity, Snapshot snapshot, Map<String, Apply> apply) {
        kinds.put(entity, new Kind(snapshot, apply));
    }

    private static String json(Object value) {
        return value == null ? null : PyJson.dumpsSorted(value);
    }

    private static String label(String entity) {
        return ENTITY_LABELS.getOrDefault(entity, entity);
    }

    /** Active admins who could approve a request made by this user. */
    public long otherAdmins(String username) {
        return SqlRows.count(jdbc, "SELECT COUNT(*) FROM users WHERE `role` = 'admin' AND is_active = ? AND username != ?", true, username);
    }

    /** Changed directly by users, never held for approval (still kept in the change history). */
    public static final java.util.Set<String> DIRECT = java.util.Set.of("partner");

    /**
     * A change made by `user`. With maker-checker on it waits for approval, else it is applied now.
     * Partners (DIRECT) are always applied now. The caller has already checked that the change is valid.
     * Committed when it returns (an error rolls everything back).
     */
    @Transactional
    public Map<String, Object> submit(User user, String entity, String action, Integer entityId, String summary,
                                      Map<String, Object> after) {
        Kind kind = kinds.get(entity);
        // applied now: partners, maker-checker off, or an admin's own change (admins' changes need no approval for now)
        boolean direct = DIRECT.contains(entity) || !live.makerChecker() || "admin".equals(user.getRole());
        Map<String, Object> before = entityId != null ? kind.snapshot().of(entityId) : null;
        if (entityId != null && before == null) throw new ApiException(404, label(entity) + " not found.");
        // an older request still waiting can't be approved after a direct change (its "before" no longer matches)
        if (!direct && entityId != null && SqlRows.scalar(jdbc,
                "SELECT t.id FROM change_requests t WHERE t.entity = ? AND t.entity_id = ? AND t.status = 'pending' LIMIT 1", entity, entityId) != null) {
            throw new ApiException(409, "A change to this " + label(entity).toLowerCase(java.util.Locale.ROOT)
                    + " is already waiting for approval. Wait for it to be approved or rejected, or cancel it.");
        }
        ChangeRequest request = new ChangeRequest();
        request.setEntity(entity);
        request.setEntityId(entityId);
        request.setAction(action);
        request.setSummary(Py.cut(summary, 255));
        request.setBefore(json(before));
        request.setAfter(json(after));
        request.setRequestedBy(user.getUsername());
        if (direct) {
            request.setEntityId(kind.apply().get(action).apply(after == null ? new LinkedHashMap<>() : after, entityId, user.getUsername()));
            request.setStatus("applied");
            request.setDecidedBy(user.getUsername());
            request.setDecidedAt(Py.now());
            changeRequests.save(request);
            return Py.map("applied", true, "pending", false, "request_id", request.getId());
        }
        changeRequests.save(request);
        return Py.map("applied", false, "pending", true, "request_id", request.getId(),
                "approvers_available", otherAdmins(user.getUsername()) > 0);
    }

    /**
     * A change that was applied directly (System Settings): history only. Written in the caller's transaction
     * (it is committed with the change it records).
     */
    @Transactional
    public void record(String entity, String action, String summary, Object before, Object after, String username) {
        var now = Py.now();
        ChangeRequest r = new ChangeRequest();
        r.setEntity(entity);
        r.setAction(action);
        r.setSummary(Py.cut(summary, 255));
        r.setBefore(json(before));
        r.setAfter(json(after));
        r.setStatus("applied");
        r.setRequestedBy(username);
        r.setRequestedAt(now);
        r.setDecidedBy(username);
        r.setDecidedAt(now);
        changeRequests.save(r);
    }

    public ChangeRequest getPending(int requestId) {
        ChangeRequest request = changeRequests.findById(requestId).orElse(null);
        if (request == null) throw new ApiException(404, "Request not found.");
        if (!"pending".equals(request.getStatus())) throw new ApiException(409, "This request was already " + request.getStatus() + ".");
        return request;
    }

    /** Committed when it returns (an error rolls everything back). */
    @Transactional
    public ChangeRequest approve(int requestId, User admin) {
        ChangeRequest request = getPending(requestId);
        Kind kind = kinds.get(request.getEntity());
        String what = label(request.getEntity()).toLowerCase(java.util.Locale.ROOT);
        if (request.getEntityId() != null) {
            Map<String, Object> current = kind.snapshot().of(request.getEntityId());
            if (current == null) throw new ApiException(409, "This " + what + " no longer exists. Reject the request.");
            if (!java.util.Objects.equals(json(current), request.getBefore())) {
                throw new ApiException(409, "This " + what + " was changed after the request was made. Reject it and ask for the change again.");
            }
        }
        Map<String, Object> after = Py.isEmpty(request.getAfter()) ? new LinkedHashMap<>() : PyJson.loadsMap(request.getAfter());
        request.setEntityId(kind.apply().get(request.getAction()).apply(after, request.getEntityId(), request.getRequestedBy()));  // may raise 400
        request.setStatus("approved");
        request.setDecidedBy(admin.getUsername());
        request.setDecidedAt(Py.now());
        return request;
    }

    /** Committed when it returns. */
    @Transactional
    public ChangeRequest reject(int requestId, User admin, String note) {
        ChangeRequest request = getPending(requestId);
        request.setStatus("rejected");
        request.setDecidedBy(admin.getUsername());
        request.setDecidedAt(Py.now());
        String n = Py.cut(Py.strip(note), 500);
        request.setNote(n.isEmpty() ? null : n);
        return request;
    }

    /** Committed when it returns. */
    @Transactional
    public ChangeRequest cancel(int requestId, User user) {
        ChangeRequest request = getPending(requestId);
        if (!request.getRequestedBy().equals(user.getUsername())) throw new ApiException(403, "Only the person who asked for this change can cancel it.");
        request.setStatus("cancelled");
        request.setDecidedBy(user.getUsername());
        request.setDecidedAt(Py.now());
        return request;
    }

    public static Map<String, Object> toDict(ChangeRequest r, Map<String, String> names, User me) {
        return Py.map(
                "id", r.getId(), "entity", r.getEntity(), "entity_label", label(r.getEntity()),
                "entity_id", r.getEntityId(), "action", r.getAction(), "action_label", ACTION_LABELS.getOrDefault(r.getAction(), r.getAction()),
                "summary", r.getSummary(), "status", r.getStatus(),
                "before", Py.isEmpty(r.getBefore()) ? null : PyJson.loads(r.getBefore()),
                "after", Py.isEmpty(r.getAfter()) ? null : PyJson.loads(r.getAfter()),
                "requested_by", r.getRequestedBy(), "requested_by_name", names.getOrDefault(r.getRequestedBy(), r.getRequestedBy()),
                "requested_at", Py.iso(r.getRequestedAt()),
                "decided_by_name", r.getDecidedBy() != null ? names.getOrDefault(r.getDecidedBy(), r.getDecidedBy()) : null,
                "decided_at", Py.iso(r.getDecidedAt()), "note", r.getNote(),
                "can_approve", me != null && "admin".equals(me.getRole()) && "pending".equals(r.getStatus()),
                "can_cancel", me != null && "pending".equals(r.getStatus()) && r.getRequestedBy().equals(me.getUsername()));
    }
}
