package se.flygare;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MagicLinkDelivery implements OneTimeTokenGenerationSuccessHandler {
    private final JavaMailSender mail;
    private final AppProperties properties;
    private final MagicTokens tokens;
    private final RestClient neon;

    public MagicLinkDelivery(JavaMailSender mail, AppProperties properties, MagicTokens tokens,
                             RestClient.Builder builder) {
        this.mail = mail;
        this.properties = properties;
        this.tokens = tokens;
        this.neon = builder.build();
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, OneTimeToken token) throws IOException {
        if (!token.getTokenValue().equals("ignored")) {
            var origin = properties.baseUrl().toString().replaceAll("/$", "");
            var confirmation = origin + "/login/confirm?token=" + token.getTokenValue();
            try {
                if (properties.neonAuthBaseUrl() != null) {
                    // Neon delivers its managed magic link. Its callback contains our independent,
                    // single-use challenge; local verification and all roles remain in Spring Boot.
                    // No auth URL, token, or provider response is ever returned to the requesting browser.
                    neon.post().uri(URI.create(properties.neonAuthBaseUrl().toString().replaceAll("/$", "")
                                    + "/sign-in/magic-link"))
                            .header("Origin", origin)
                            .body(Map.of("email", token.getUsername(), "callbackURL", confirmation,
                                    "newUserCallbackURL", confirmation, "errorCallbackURL", origin + "/login?invalid"))
                            .retrieve().toBodilessEntity();
                } else {
                    var message = new SimpleMailMessage();
                    message.setFrom(properties.mailFrom());
                    message.setTo(token.getUsername());
                    message.setSubject("Sign in to Flygare");
                    message.setText("Open this link and confirm sign-in within 10 minutes:\n" + confirmation
                            + "\n\nIf you did not request this, ignore this email. Do not share this link.");
                    mail.send(message);
                }
            } catch (MailException | RestClientException ex) {
                tokens.discard(token.getTokenValue());
                // Never log a mail exception: provider diagnostics may contain the message or recipient.
                LoggerFactory.getLogger(getClass()).warn("Magic-link delivery failed; check mail service configuration");
                response.sendRedirect("/login?unavailable");
                return;
            }
        }
        response.sendRedirect("/login?sent");
    }
}
