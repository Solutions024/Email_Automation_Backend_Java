package com.nagad.npsb.service;

import com.nagad.npsb.Config;
import com.nagad.npsb.Py;
import com.nagad.npsb.PyJson;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.ChangeRequest;
import com.nagad.npsb.models.User;
import com.nagad.npsb.web.ApiError;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maker-checker for configuration: partners, email templates and template variables.
 *
 * A change is first a request (maker). It is applied only when an admin who did not make it approves it
 * (checker). Approving fails if the thing was changed in the meantime (the request was made against an
 * older version): the maker then asks again. Every applied change stays in change_requests, so it is also
 * the change history. With maker-checker switched off (System Settings), changes apply at once and are
 * recorded as "applied". System Settings changes are only recorded, never held for approval.
 *
 * The API registers, for each kind of thing, how to read its current state (snapshot) and how to apply
 * each action.
 */
public final class Approvals {
    private Approvals() {}

    public static final Map<String, String> ENTITY_LABELS = Map.of("partner", "Partner", "template", "Email template",
            "variable", "Template variable", "settings", "System Settings");
    public static final Map<String, String> ACTION_LABELS = Map.of("create", "New", "update", "Change", "delete", "Delete",
            "default", "Make default");

    @FunctionalInterface
    public interface Snapshot {
        /** The current state (null: it no longer exists). */
        Map<String, Object> of(Db db, int id);
    }

    @FunctionalInterface
    public interface Apply {
        Integer apply(Db db, Map<String, Object> after, Integer id, String by);
    }

    private record Kind(Snapshot snapshot, Map<String, Apply> apply) {}

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    public static void register(String entity, Snapshot snapshot, Map<String, Apply> apply) {
        KINDS.put(entity, new Kind(snapshot, apply));
    }

    private static String json(Object value) {
        return value == null ? null : PyJson.dumpsSorted(value);
    }

    private static String label(String entity) {
        return ENTITY_LABELS.getOrDefault(entity, entity);
    }

    /** Active admins who could approve a request made by this user. */
    public static long otherAdmins(Db db, String username) {
        return db.count("SELECT COUNT(*) FROM users WHERE `role` = 'admin' AND is_active = ? AND username != ?", true, username);
    }

    /**
     * A change made by `user`. With maker-checker on it waits for approval, else it is applied now.
     * The caller has already checked that the change is valid.
     */
    public static Map<String, Object> submit(Db db, User user, String entity, String action, Integer entityId, String summary,
                                             Map<String, Object> after) {
        Kind kind = KINDS.get(entity);
        Map<String, Object> before = entityId != null ? kind.snapshot().of(db, entityId) : null;
        if (entityId != null && before == null) throw new ApiError(404, label(entity) + " not found.");
        if (entityId != null && db.first(ChangeRequest.class, "WHERE t.entity = ? AND t.entity_id = ? AND t.status = 'pending'",
                entity, entityId) != null) {
            throw new ApiError(409, "A change to this " + label(entity).toLowerCase(java.util.Locale.ROOT)
                    + " is already waiting for approval. Wait for it to be approved or rejected, or cancel it.");
        }
        ChangeRequest request = new ChangeRequest();
        request.entity = entity;
        request.entityId = entityId;
        request.action = action;
        request.summary = Py.cut(summary, 255);
        request.before = json(before);
        request.after = json(after);
        request.requestedBy = user.username;
        if (!Config.MAKER_CHECKER()) {
            request.entityId = kind.apply().get(action).apply(db, after == null ? new LinkedHashMap<>() : after, entityId, user.username);
            request.status = "applied";
            request.decidedBy = user.username;
            request.decidedAt = Py.now();
            db.add(request);
            db.commit();
            return Py.map("applied", true, "pending", false, "request_id", request.id);
        }
        db.add(request);
        db.commit();
        return Py.map("applied", false, "pending", true, "request_id", request.id,
                "approvers_available", otherAdmins(db, user.username) > 0);
    }

    /** A change that was applied directly (System Settings): history only. */
    public static void record(Db db, String entity, String action, String summary, Object before, Object after, String username) {
        var now = Py.now();
        ChangeRequest r = new ChangeRequest();
        r.entity = entity;
        r.action = action;
        r.summary = Py.cut(summary, 255);
        r.before = json(before);
        r.after = json(after);
        r.status = "applied";
        r.requestedBy = username;
        r.requestedAt = now;
        r.decidedBy = username;
        r.decidedAt = now;
        db.add(r);
    }

    public static ChangeRequest getPending(Db db, int requestId) {
        ChangeRequest request = db.get(ChangeRequest.class, requestId);
        if (request == null) throw new ApiError(404, "Request not found.");
        if (!"pending".equals(request.status)) throw new ApiError(409, "This request was already " + request.status + ".");
        return request;
    }

    public static ChangeRequest approve(Db db, int requestId, User admin) {
        ChangeRequest request = getPending(db, requestId);
        if (request.requestedBy.equals(admin.username)) {
            throw new ApiError(403, "You made this change, so another admin has to approve it.");
        }
        Kind kind = KINDS.get(request.entity);
        String what = label(request.entity).toLowerCase(java.util.Locale.ROOT);
        if (request.entityId != null) {
            Map<String, Object> current = kind.snapshot().of(db, request.entityId);
            if (current == null) throw new ApiError(409, "This " + what + " no longer exists. Reject the request.");
            if (!java.util.Objects.equals(json(current), request.before)) {
                throw new ApiError(409, "This " + what + " was changed after the request was made. Reject it and ask for the change again.");
            }
        }
        Map<String, Object> after = Py.isEmpty(request.after) ? new LinkedHashMap<>() : PyJson.loadsMap(request.after);
        request.entityId = kind.apply().get(request.action).apply(db, after, request.entityId, request.requestedBy);  // may raise 400
        request.status = "approved";
        request.decidedBy = admin.username;
        request.decidedAt = Py.now();
        db.commit();
        return request;
    }

    public static ChangeRequest reject(Db db, int requestId, User admin, String note) {
        ChangeRequest request = getPending(db, requestId);
        request.status = "rejected";
        request.decidedBy = admin.username;
        request.decidedAt = Py.now();
        String n = Py.cut(Py.strip(note), 500);
        request.note = n.isEmpty() ? null : n;
        db.commit();
        return request;
    }

    public static ChangeRequest cancel(Db db, int requestId, User user) {
        ChangeRequest request = getPending(db, requestId);
        if (!request.requestedBy.equals(user.username)) throw new ApiError(403, "Only the person who asked for this change can cancel it.");
        request.status = "cancelled";
        request.decidedBy = user.username;
        request.decidedAt = Py.now();
        db.commit();
        return request;
    }

    public static Map<String, Object> toDict(ChangeRequest r, Map<String, String> names, User me) {
        return Py.map(
                "id", r.id, "entity", r.entity, "entity_label", label(r.entity),
                "entity_id", r.entityId, "action", r.action, "action_label", ACTION_LABELS.getOrDefault(r.action, r.action),
                "summary", r.summary, "status", r.status,
                "before", Py.isEmpty(r.before) ? null : PyJson.loads(r.before), "after", Py.isEmpty(r.after) ? null : PyJson.loads(r.after),
                "requested_by", r.requestedBy, "requested_by_name", names.getOrDefault(r.requestedBy, r.requestedBy),
                "requested_at", Py.iso(r.requestedAt),
                "decided_by_name", r.decidedBy != null ? names.getOrDefault(r.decidedBy, r.decidedBy) : null,
                "decided_at", Py.iso(r.decidedAt), "note", r.note,
                "can_approve", me != null && "admin".equals(me.role) && "pending".equals(r.status) && !r.requestedBy.equals(me.username),
                "can_cancel", me != null && "pending".equals(r.status) && r.requestedBy.equals(me.username));
    }
}
