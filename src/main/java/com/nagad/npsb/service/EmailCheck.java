package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import org.springframework.stereotype.Service;

import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.SocketTimeoutException;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Checks that an email address can really receive mail.
 *
 * Two layers:
 *   1. Here, before an account is created or an email is changed: the address must be well-formed,
 *      not from a throwaway/disposable provider, and its domain must have a mail server (DNS MX record,
 *      or an A/AAAA record as the standard fallback).
 *   2. In Auth: a 6-digit code is emailed to the address and must be entered. That is the only way to
 *      prove the mailbox itself exists and belongs to the person.
 */
@Service
public class EmailCheck {

    private final LiveSettings live;

    public EmailCheck(LiveSettings live) {
        this.live = live;
    }

    /** One dot-separated part of the name before the @ (no quoted names). */
    private static final Pattern LOCAL_ATOM = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+");
    /** One part of the domain: 1-63 letters, digits or -, not starting or ending with -. */
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    /** The last part of the domain (com, bd, ...). */
    private static final Pattern TOP_LEVEL = Pattern.compile("[a-z]{2,63}");

    /**
     * A plain, lower-case address: 6-254 characters, name@label.label...tld, the name made of LOCAL_ATOMs joined by
     * single dots. Checked part by part, so the time and stack it takes don't grow with repeated parts. Same answers as
     * (?=.{6,254}$)[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}
     */
    static boolean wellFormed(String email) {
        if (email.length() < 6 || email.length() > 254) return false;
        int at = email.indexOf('@');
        if (at < 0 || at != email.lastIndexOf('@')) return false;
        for (String atom : email.substring(0, at).split("\\.", -1)) {
            if (!LOCAL_ATOM.matcher(atom).matches()) return false;
        }
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2 || !TOP_LEVEL.matcher(labels[labels.length - 1]).matches()) return false;
        for (int i = 0; i < labels.length - 1; i++) {
            if (!DOMAIN_LABEL.matcher(labels[i]).matches()) return false;
        }
        return true;
    }

    /** Common throwaway inbox providers */
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "mailinator.com", "guerrillamail.com", "guerrillamail.net", "sharklasers.com", "10minutemail.com",
            "10minutemail.net", "tempmail.com", "temp-mail.org", "temp-mail.io", "tempmail.net", "throwawaymail.com",
            "yopmail.com", "yopmail.net", "getnada.com", "nada.email", "trashmail.com", "trashmail.de",
            "dispostable.com", "maildrop.cc", "mailnesia.com", "mintemail.com", "fakeinbox.com", "emailondeck.com",
            "mohmal.com", "mailcatch.com", "spamgourmet.com", "tempinbox.com", "moakt.com", "tempr.email",
            "discard.email", "mailpoof.com", "burnermail.io", "inboxkitten.com", "1secmail.com", "1secmail.net",
            "fakemail.net", "emailfake.com", "tmpmail.org", "tmpmail.net", "minuteinbox.com", "byom.de",
            "example.com", "example.org", "example.net", "test.com");

    private record Cached(long at, boolean ok) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();  // domain -> (checked at, can receive mail)
    private static final long CACHE_NANOS = 3600L * 1_000_000_000L;

    private static class DnsTimeout extends Exception {}

    private static DirContext resolver() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");  // 2 s, 4 s: about 6 s in all (resolver.lifetime = 6)
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        return new InitialDirContext(env);
    }

    /** The records of this type, or null when the domain has none (NoAnswer). false for NXDOMAIN / no answer. */
    private static Attribute lookup(DirContext ctx, String domain, String type) throws NamingException, DnsTimeout {
        try {
            Attributes attrs = ctx.getAttributes("dns:/" + domain, new String[]{type});
            Attribute a = attrs.get(type);
            return a == null || a.size() == 0 ? null : a;
        } catch (CommunicationException e) {
            if (e.getRootCause() instanceof SocketTimeoutException) throw new DnsTimeout();
            throw e;
        }
    }

    /** True if DNS says this domain has somewhere to deliver mail. */
    public boolean domainAcceptsMail(String domain) {
        Cached cached = cache.get(domain);
        if (cached != null && System.nanoTime() - cached.at() < CACHE_NANOS) return cached.ok();
        boolean ok;
        try {
            DirContext ctx = resolver();
            try {
                Attribute mx = lookup(ctx, domain, "MX");
                if (mx != null) {
                    // A "null MX" (a single record pointing at ".") means: this domain never receives mail
                    ok = false;
                    for (int i = 0; i < mx.size(); i++) {
                        String[] parts = String.valueOf(mx.get(i)).trim().split("\\s+");
                        String exchange = parts[parts.length - 1];
                        if (!Py.rstrip(exchange, ".").isEmpty()) ok = true;
                    }
                } else {
                    // No MX record: mail servers fall back to the domain's own address (RFC 5321)
                    ok = lookup(ctx, domain, "A") != null || lookup(ctx, domain, "AAAA") != null;
                }
            } finally {
                ctx.close();
            }
        } catch (NameNotFoundException | ServiceUnavailableException e) {
            ok = false;  // NXDOMAIN / no name server answered
        } catch (DnsTimeout e) {
            throw new ApiException(503, "Could not check the email address right now (DNS timeout). Please try again.");
        } catch (NamingException e) {
            ok = false;
        }
        cache.put(domain, new Cached(System.nanoTime(), ok));
        return ok;
    }

    /** The normalized address, or 400 explaining why it cannot be used. */
    public String validateRealEmail(String email) {
        email = Py.strip(email).toLowerCase();
        if (!wellFormed(email)) throw new ApiException(400, "Enter a valid email address.");
        String domain = email.substring(email.lastIndexOf('@') + 1);
        for (String d : DISPOSABLE_DOMAINS) {
            if (domain.equals(d) || domain.endsWith("." + d)) {
                throw new ApiException(400, "Disposable or example email addresses are not allowed. Use your real email.");
            }
        }
        if (live.emailDnsCheck() && !domainAcceptsMail(domain)) {
            throw new ApiException(400, "The email domain \"" + domain + "\" does not exist or cannot receive email.");
        }
        return email;
    }
}
