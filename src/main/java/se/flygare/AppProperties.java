package se.flygare;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotNull URI baseUrl, @Email String mailFrom,
                            @NotNull @Valid Bootstrap bootstrap, URI neonAuthBaseUrl) {
    public AppProperties(URI baseUrl, String mailFrom, Bootstrap bootstrap) {
        this(baseUrl, mailFrom, bootstrap, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AppProperties {
        if (neonAuthBaseUrl != null && (!"https".equals(neonAuthBaseUrl.getScheme())
                || neonAuthBaseUrl.getHost() == null || neonAuthBaseUrl.getUserInfo() != null
                || neonAuthBaseUrl.getQuery() != null || neonAuthBaseUrl.getFragment() != null)) {
            throw new IllegalArgumentException("Neon Auth delivery must use a trusted HTTPS service URL");
        }
        if (baseUrl != null && (baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"))
                || !(baseUrl.getScheme().equals("https") || (baseUrl.getScheme().equals("http")
                && List.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost()))))) {
            throw new IllegalArgumentException("APP_BASE_URL must be an HTTPS origin (HTTP only on loopback)");
        }
    }

    public record Bootstrap(@Email String tobiasEmail, @Email String tonyEmail,
                            @Email String alexanderEmail) {
        public Bootstrap {
            tobiasEmail = configured(tobiasEmail);
            tonyEmail = configured(tonyEmail);
            alexanderEmail = configured(alexanderEmail);
            var configured = java.util.stream.Stream.of(tobiasEmail, tonyEmail, alexanderEmail)
                    .filter(java.util.Objects::nonNull).toList();
            if (configured.isEmpty()) {
                throw new IllegalArgumentException("Configure at least one initial administrator email");
            }
            if (configured.stream().distinct().count() != configured.size()) {
                throw new IllegalArgumentException("Initial administrator emails must be distinct");
            }
        }

        private static String configured(String email) {
            var normalized = normalize(email);
            return normalized == null || normalized.isEmpty() ? null : normalized;
        }

        public List<Identity> identities() {
            return List.of(new Identity("tobias", tobiasEmail, "Tobias Flygare", false),
                    new Identity("tony", tonyEmail, "Tony Flygare", false),
                    new Identity("alexander", alexanderEmail, "Alexander Sjögren", true))
                    .stream().filter(identity -> identity.email() != null).toList();
        }
    }

    public record Identity(String slot, String email, String name, boolean developer) { }

    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
