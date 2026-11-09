package com.nagad.npsb;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A fake SMTP server for testing. No email leaves your computer.
 *
 * Run it in its own terminal (from the backend folder):
 *     java -jar build/libs/npsb-backend.jar test-mail-server
 *
 * Any recipient address that starts with "bad" (e.g. bad1@gmail.com) is refused with
 * "550 5.1.1 ... does not exist", like a real server does for an unknown mailbox. Use it to check that
 * refused recipients are reported on the Emails and Partners pages.
 *
 * Every email the app "sends" is:
 *   * printed in this terminal (From / To / CC / Subject)
 *   * saved as an .html file in the "sent_emails" folder - double-click to open it in your browser
 */
public final class TestMailServer {
    private TestMailServer() {}

    static final String HOST = "localhost";
    static final int PORT = 1025;                 // must match SMTP_HOST / SMTP_PORT in .env
    static final Path OUTPUT_DIR = Paths.get("sent_emails");
    static final String REFUSE_PREFIX = "bad";    // recipients starting with this are refused
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws IOException {
        try (ServerSocket server = new ServerSocket(PORT, 50, InetAddress.getByName(HOST))) {
            System.out.println("Test mail server running on " + HOST + ":" + PORT);
            System.out.println("Emails will be saved in: " + OUTPUT_DIR.toAbsolutePath());
            System.out.println("Recipients starting with '" + REFUSE_PREFIX + "' are refused (to test refused-address handling)");
            System.out.println("Press Ctrl+C to stop.\n");
            while (true) {
                Socket socket = server.accept();
                Thread t = new Thread(() -> serve(socket), "smtp-session");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String now() {
        return LocalDateTime.now().format(CLOCK);
    }

    private static void serve(Socket socket) {
        try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream out = socket.getOutputStream()) {
            reply(out, "220 " + HOST + " ESMTP test mail server");
            List<String> recipients = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
                switch (command) {
                    case "EHLO" -> {
                        reply(out, "250-" + HOST);
                        reply(out, "250-SIZE 33554432");
                        reply(out, "250-8BITMIME");
                        reply(out, "250-SMTPUTF8");
                        reply(out, "250 HELP");
                    }
                    case "HELO" -> reply(out, "250 " + HOST);
                    case "MAIL" -> {
                        recipients.clear();
                        reply(out, "250 OK");
                    }
                    case "RCPT" -> {
                        String address = line.substring(line.indexOf(':') + 1).trim();
                        if (address.startsWith("<")) address = address.substring(1, address.indexOf('>') > 0 ? address.indexOf('>') : address.length());
                        if (address.toLowerCase().startsWith(REFUSE_PREFIX)) {
                            System.out.println("[" + now() + "] Refused recipient " + address);
                            reply(out, "550 5.1.1 The email account that you tried to reach does not exist");
                        } else {
                            recipients.add(address);
                            reply(out, "250 OK");
                        }
                    }
                    case "DATA" -> {
                        if (recipients.isEmpty()) {
                            reply(out, "503 Error: need RCPT command");
                            continue;
                        }
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        ByteArrayOutputStream data = new ByteArrayOutputStream();
                        String l;
                        while ((l = in.readLine()) != null && !l.equals(".")) {
                            if (l.startsWith(".")) l = l.substring(1);
                            data.write((l + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        }
                        save(data.toByteArray(), recipients);
                        reply(out, "250 OK");
                    }
                    case "RSET" -> {
                        recipients.clear();
                        reply(out, "250 OK");
                    }
                    case "NOOP" -> reply(out, "250 OK");
                    case "QUIT" -> {
                        reply(out, "221 Bye");
                        return;
                    }
                    default -> reply(out, "500 Error: command \"" + command.trim() + "\" not recognized");
                }
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
    }

    private static String body(Part part, String wanted) throws Exception {
        if (part.isMimeType("text/" + wanted)) return String.valueOf(part.getContent());
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) continue;
                String found = body(bp, wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void save(byte[] content, List<String> accepted) {
        try {
            MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(content));
            String html = body(msg, "html");
            if (html == null) html = body(msg, "plain");
            if (html == null) html = "";
            String from = msg.getHeader("From", ", "), to = msg.getHeader("To", ", "), cc = msg.getHeader("Cc", ", ");
            String subject = msg.getSubject();
            Files.createDirectories(OUTPUT_DIR);
            Path file = OUTPUT_DIR.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSSSSS")) + ".html");
            String header = "<p><b>From:</b> " + from + "<br><b>To:</b> " + to + "<br>"
                    + "<b>CC:</b> " + (cc == null ? "" : cc) + "<br><b>Subject:</b> " + subject + "</p><hr>";
            Files.writeString(file, header + html, StandardCharsets.UTF_8);
            System.out.println("[" + now() + "] Email received");
            System.out.println("   To:      " + to);
            System.out.println("   Accepted: " + String.join(", ", accepted));
            System.out.println("   CC:      " + cc);
            System.out.println("   Subject: " + subject);
            System.out.println("   Saved:   " + file.toAbsolutePath() + "\n");
        } catch (Exception e) {
            System.out.println("Could not save an email: " + e.getMessage());
        }
    }
}
