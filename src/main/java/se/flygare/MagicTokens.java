package se.flygare;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MagicTokens implements OneTimeTokenService {
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final BootstrapService bootstrap;
    private final Validator validator;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public MagicTokens(JdbcTemplate db, Accounts accounts, BootstrapService bootstrap, Validator validator, Clock clock) {
        this.db = db;
        this.accounts = accounts;
        this.bootstrap = bootstrap;
        this.validator = validator;
        this.clock = clock;
    }

    private record Address(@NotBlank @Email @Size(max = 254) String email) { }

    @Transactional
    public boolean allowRequest(String remoteAddress) {
        var now = clock.instant();
        // Database bucket works across application instances; never trust client-supplied forwarding headers.
        var bucket = digest(remoteAddress) + ":" + now.getEpochSecond() / 600;
        db.update("insert into link_rate(bucket, expires_at) values (?, ?) on conflict do nothing",
                bucket, Timestamp.from(now.plusSeconds(1200)));
        return db.update("update link_rate set attempts = attempts + 1 where bucket = ? and attempts < 20", bucket) == 1;
    }

    @Override
    @Transactional
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        var now = clock.instant();
        var email = AppProperties.normalize(request.getUsername());
        if (!validator.validate(new Address(email)).isEmpty()) return ignored(now);
        db.update("insert into app_user(email) values (?) on conflict do nothing", email);
        var user = accounts.lock(accounts.byEmail(email).orElseThrow().id());
        var last = db.queryForObject("select last_link_at from app_user where id = ?", Timestamp.class, user.id());
        if (!user.enabled() || (last != null && last.toInstant().isAfter(now.minusSeconds(60)))) return ignored(now);
        db.update("update app_user set last_link_at = ? where id = ?", Timestamp.from(now), user.id());
        db.update("delete from magic_token where user_id = ?", user.id());
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expires = now.plusSeconds(600);
        db.update("insert into magic_token(digest, user_id, expires_at) values (?, ?, ?)",
                digest(raw), user.id(), Timestamp.from(expires));
        return new DefaultOneTimeToken(raw, email, expires);
    }

    private OneTimeToken ignored(Instant now) {
        // Framework token objects require nonempty values. This sentinel is never stored or delivered.
        return new DefaultOneTimeToken("ignored", "ignored", now);
    }

    @Override
    @Transactional
    public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        var raw = authenticationToken.getTokenValue();
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        var hash = digest(raw);
        var ids = db.queryForList("select user_id from magic_token where digest = ?", Long.class, hash);
        if (ids.isEmpty()) return null;
        // All operations for an identity lock the account first. The conditional delete wins exactly once.
        var user = accounts.lock(ids.getFirst());
        int consumed = db.update("delete from magic_token where digest = ? and expires_at > ?",
                hash, Timestamp.from(clock.instant()));
        if (consumed != 1 || !user.enabled()) return null;
        db.update("update app_user set email_verified_at = coalesce(email_verified_at, ?) where id = ?",
                Timestamp.from(clock.instant()), user.id());
        bootstrap.grantVerified(user.id());
        return new DefaultOneTimeToken(raw, user.email(), clock.instant().plusSeconds(1));
    }

    public void discard(String raw) { db.update("delete from magic_token where digest = ?", digest(raw)); }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
