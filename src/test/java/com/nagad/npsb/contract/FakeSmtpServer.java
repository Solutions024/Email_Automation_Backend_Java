package com.nagad.npsb.contract;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * A minimal SMTP server on a free local port that keeps every message it is given, so the scenario can read the
 * emailed sign-in / reset codes and record what the backend sent. It is outside the backend: the backend is
 * pointed at it through the System Settings API like at any other mail server.
 *
 * Recipients whose address starts with "bad" are refused ("550 5.1.1 ... User unknown"), like the project's own
 * test mail server, to exercise the refused-recipient handling.
 */
final class FakeSmtpServer implements AutoCloseable {

    /** One accepted message: envelope sender, accepted and refused recipients, and the raw message. */
    record Mail(String envelopeFrom, List<String> recipients, List<String> refused, byte[] data) {
        MimeMessage parse() {
            try {
                return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data));
            } catch (Exception e) {
                throw new IllegalStateException("Unreadable message", e);
            }
        }

        String subject() {
            try {
                return parse().getSubject();
            } catch (Exception e) {
                return null;
            }
        }

        List<String> header(Message.RecipientType type) {
            List<String> out = new ArrayList<>();
            try {
                Address[] addresses = parse().getRecipients(type);
                if (addresses != null) for (Address a : addresses) out.add(((InternetAddress) a).getAddress());
            } catch (Exception ignored) {
                // no such header
            }
            return out;
        }

        String from() {
            try {
                Address[] from = parse().getFrom();
                return from == null || from.length == 0 ? null : ((InternetAddress) from[0]).getAddress();
            } catch (Exception e) {
                return null;
            }
        }

        boolean hasHeader(String name) {
            try {
                return parse().getHeader(name) != null;
            } catch (Exception e) {
                return false;
            }
        }

        List<String> attachmentNames() {
            List<String> out = new ArrayList<>();
            try {
                collect(parse(), out);
            } catch (Exception ignored) {
                // not multipart
            }
            return out;
        }

        private static void collect(Part part, List<String> out) throws Exception {
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart child = mp.getBodyPart(i);
                    collect(child, out);
                }
            } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
                out.add(part.getFileName());
            }
        }
    }

    private final ServerSocket server;
    private final List<Mail> mails = new ArrayList<>();
    private int drained = 0;
    private volatile boolean closed = false;

    FakeSmtpServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::acceptLoop, "contract-fake-smtp");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> serve(socket), "contract-fake-smtp-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** One line without its CRLF (ISO-8859-1, byte for byte), or null at the end of the stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        byte[] bytes = b.toByteArray();
        int len = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
    }

    private static String address(String arg) {
        int lt = arg.indexOf('<'), gt = arg.indexOf('>');
        String a = lt >= 0 && gt > lt ? arg.substring(lt + 1, gt) : arg.substring(arg.indexOf(':') + 1);
        return a.strip();
    }

    private void serve(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            reply(out, "220 localhost ESMTP contract test mail server");
            String from = null;
            List<String> rcpt = new ArrayList<>(), refused = new ArrayList<>();
            String line;
            while ((line = readLine(in)) != null) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EHLO")) {
                    reply(out, "250-localhost");
                    reply(out, "250 8BITMIME");
                } else if (upper.startsWith("HELO")) {
                    reply(out, "250 localhost");
                } else if (upper.startsWith("MAIL FROM")) {
                    from = address(line);
                    rcpt = new ArrayList<>();
                    refused = new ArrayList<>();
                    reply(out, "250 2.1.0 Ok");
                } else if (upper.startsWith("RCPT TO")) {
                    String to = address(line);
                    if (to.toLowerCase(Locale.ROOT).startsWith("bad")) {
                        refused.add(to);
                        reply(out, "550 5.1.1 <" + to + ">: Recipient address rejected: User unknown");
                    } else {
                        rcpt.add(to);
                        reply(out, "250 2.1.5 Ok");
                    }
                } else if (upper.equals("DATA")) {
                    if (rcpt.isEmpty()) {
                        reply(out, "554 5.5.1 No valid recipients");
                        continue;
                    }
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    String dataLine;
                    while ((dataLine = readLine(in)) != null && !dataLine.equals(".")) {
                        if (dataLine.startsWith(".")) dataLine = dataLine.substring(1);
                        data.write(dataLine.getBytes(StandardCharsets.ISO_8859_1));
                        data.write('\r');
                        data.write('\n');
                    }
                    synchronized (mails) {  // kept before the 250, so the API call that sent it returns after it is here
                        mails.add(new Mail(from, List.copyOf(rcpt), List.copyOf(refused), data.toByteArray()));
                        mails.notifyAll();
                    }
                    reply(out, "250 2.0.0 Ok: queued");
                } else if (upper.startsWith("RSET")) {
                    from = null;
                    rcpt = new ArrayList<>();
                    refused = new ArrayList<>();
                    reply(out, "250 2.0.0 Ok");
                } else if (upper.startsWith("NOOP")) {
                    reply(out, "250 2.0.0 Ok");
                } else if (upper.startsWith("QUIT")) {
                    reply(out, "221 2.0.0 Bye");
                    return;
                } else {
                    reply(out, "502 5.5.2 Command not recognized");
                }
            }
        } catch (IOException ignored) {
            // the client hung up
        }
    }

    /** Messages received since the previous call (in the order they arrived). */
    List<Mail> drain() {
        synchronized (mails) {
            List<Mail> out = new ArrayList<>(mails.subList(drained, mails.size()));
            drained = mails.size();
            return out;
        }
    }

    /** Wait (up to timeoutMillis) until a not yet drained message matches; null if none arrives. */
    Mail await(Predicate<Mail> wanted, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        synchronized (mails) {
            while (true) {
                for (int i = drained; i < mails.size(); i++) if (wanted.test(mails.get(i))) return mails.get(i);
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                mails.wait(left);
            }
        }
    }

    /** The newest not yet drained message sent to this address, or null. */
    Mail lastTo(String address) {
        synchronized (mails) {
            for (int i = mails.size() - 1; i >= drained; i--) {
                if (mails.get(i).recipients().stream().anyMatch(r -> r.equalsIgnoreCase(address))) return mails.get(i);
            }
            return null;
        }
    }
}
