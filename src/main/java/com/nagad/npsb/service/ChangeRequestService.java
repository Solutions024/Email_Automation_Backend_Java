package com.nagad.npsb.service;

import com.nagad.npsb.entity.ChangeRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.UserNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Approvals (maker-checker) and the change history: the changes waiting for an admin, approve / reject / cancel. */
@Service
public class ChangeRequestService {
    private final Approvals approvals;
    private final UserNames userNames;
    private final Sql sql;

    public ChangeRequestService(Approvals approvals, UserNames userNames, Sql sql) {
        this.approvals = approvals;
        this.userNames = userNames;
        this.sql = sql;
    }

    /** Changes waiting for approval (status "pending"), or the change history ("history"), or both ("all"), newest first. */
    public List<Object> listChangeRequests(User user, String status, String entity, Integer entityId) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM ChangeRequest t WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status.equals("pending")) jpql.append(" AND t.status = 'pending'");
        else if (status.equals("history")) jpql.append(" AND t.status <> 'pending'");
        if (!entity.isEmpty()) {
            args.add(entity);
            jpql.append(" AND t.entity = ?").append(args.size());
        }
        if (entityId != null) {
            args.add(entityId);
            jpql.append(" AND t.entityId = ?").append(args.size());
        }
        jpql.append(" ORDER BY t.id DESC");
        List<ChangeRequest> rows = sql.jpql(ChangeRequest.class, jpql.toString(), 300, args.toArray());
        List<String> who = new ArrayList<>();
        for (ChangeRequest r : rows) {
            who.add(r.getRequestedBy());
            who.add(r.getDecidedBy());
        }
        Map<String, String> names = userNames.userNames(who);
        List<Object> out = new ArrayList<>();
        for (ChangeRequest r : rows) out.add(Approvals.toDict(r, names, user));
        return out;
    }

    Map<String, Object> changeRequestDict(ChangeRequest r, User user) {
        return Approvals.toDict(r, userNames.userNames(Arrays.asList(r.getRequestedBy(), r.getDecidedBy())), user);
    }

    public Map<String, Object> approveChange(User admin, int requestId) {
        return changeRequestDict(approvals.approve(requestId, admin), admin);
    }

    public Map<String, Object> rejectChange(User admin, int requestId, String note) {
        return changeRequestDict(approvals.reject(requestId, admin, note), admin);
    }

    public Map<String, Object> cancelChange(User user, int requestId) {
        return changeRequestDict(approvals.cancel(requestId, user), user);
    }
}
