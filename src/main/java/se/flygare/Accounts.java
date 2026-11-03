package se.flygare;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Accounts {
    private final JdbcTemplate db;
    public Accounts(JdbcTemplate db) { this.db = db; }

    public enum Role { MEMBER, ADMIN }
    public enum Status { PENDING, APPROVED }
    public record Account(long id, String email, String displayName, Role role, Status status,
                          Instant verifiedAt, boolean developer, boolean enabled) {
        public boolean approved() { return enabled && verifiedAt != null && status == Status.APPROVED; }
        public boolean admin() { return approved() && role == Role.ADMIN; }
    }

    private static final org.springframework.jdbc.core.RowMapper<Account> MAPPER = (rs, n) -> {
        var verified = rs.getTimestamp("email_verified_at");
        return new Account(rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                Role.valueOf(rs.getString("role")), Status.valueOf(rs.getString("status")),
                verified == null ? null : verified.toInstant(), rs.getBoolean("developer"), rs.getBoolean("enabled"));
    };

    public Optional<Account> byEmail(String email) {
        var normalized = AppProperties.normalize(email);
        if (normalized == null) return Optional.empty();
        var canonical = GoogleIdentity.canonicalGmail(normalized);
        if (canonical.endsWith("@gmail.com")) {
            var matches = db.query("select * from app_user where email like '%@gmail.com' or email like '%@googlemail.com'",
                    MAPPER).stream().filter(user -> GoogleIdentity.canonicalGmail(user.email()).equals(canonical)).toList();
            if (matches.size() == 1) return Optional.of(matches.getFirst());
            if (matches.size() > 1) {
                var bootstrapMatches = db.query("""
                        select u.* from bootstrap_grant g join app_user u on u.id=g.user_id
                        where u.email like '%@gmail.com' or u.email like '%@googlemail.com'
                        """, MAPPER).stream()
                        .filter(user -> GoogleIdentity.canonicalGmail(user.email()).equals(canonical)).toList();
                return bootstrapMatches.size() == 1 ? Optional.of(bootstrapMatches.getFirst()) : Optional.empty();
            }
        }
        return db.query("select * from app_user where email = ?", MAPPER, normalized).stream().findFirst();
    }
    public Account lock(long id) {
        return db.queryForObject("select * from app_user where id = ? for update", MAPPER, id);
    }
    public List<Account> all() { return db.query("select * from app_user order by id", MAPPER); }
}
