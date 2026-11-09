package com.nagad.npsb.service.dispatch;

import com.nagad.npsb.service.FollowupService;
import com.nagad.npsb.service.Inbox;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Full names of the users shown on the pages (who prepared, sent, changed...). */
@Component
public class UserNames {
    private final Sql sql;

    public UserNames(Sql sql) {
        this.sql = sql;
    }

    /** username -> full name (the username itself when no name is set). */
    public Map<String, String> userNames(Collection<String> usernames) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String u : usernames) if (!Py.isEmpty(u)) wanted.add(u);
        Map<String, String> out = new HashMap<>();
        if (wanted.isEmpty()) return out;
        out.put(FollowupService.SYSTEM_USER, "Sent automatically");
        out.put(Inbox.INBOX_USER, "Found in the mailbox");
        for (Object[] row : sql.rows("SELECT username, full_name FROM users WHERE username IN " + Sql.in(wanted), wanted.toArray())) {
            out.put((String) row[0], Py.or((String) row[1], (String) row[0]));
        }
        return out;
    }

    /** The answers' variant: the automatic senders are always named, users only when there are any. */
    public Map<String, String> namesOf(Collection<String> usernames) {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String u : usernames) if (!Py.isEmpty(u)) wanted.add(u);
        Map<String, String> out = new HashMap<>();
        out.put("#auto", "Sent automatically");
        out.put("#inbox", "Found in the mailbox");
        if (!wanted.isEmpty()) {
            for (Object[] row : sql.rows("SELECT username, full_name FROM users WHERE username IN " + Sql.in(wanted), wanted.toArray())) {
                out.put((String) row[0], Py.or((String) row[1], (String) row[0]));
            }
        }
        return out;
    }

    public static String name(Map<String, String> names, String username) {
        return names.containsKey(username) ? names.get(username) : username;
    }
}
