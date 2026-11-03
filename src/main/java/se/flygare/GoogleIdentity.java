package se.flygare;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleIdentity {
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final BootstrapService bootstrap;
    private final Clock clock;
    public GoogleIdentity(JdbcTemplate db, Accounts accounts, BootstrapService bootstrap, Clock clock) {
        this.db=db; this.accounts=accounts; this.bootstrap=bootstrap; this.clock=clock;
    }

    @Transactional
    public String verify(String subject, String address, Boolean verified, String hostedDomain) {
        String email=AppProperties.normalize(address);
        // Gmail ignores dots in the local part of @gmail.com addresses. Canonicalize
        // before both account lookup and bootstrap matching; keep plus aliases distinct.
        email=canonicalGmail(email);
        final String canonicalEmail = email;
        // Gmail and Workspace addresses are authoritative Google identities. Other addresses need a separate proof.
        if (subject==null || subject.isBlank() || subject.length()>255 || !Boolean.TRUE.equals(verified)
                || email==null || email.length()>254 || !email.contains("@")
                || !(email.endsWith("@gmail.com") || (hostedDomain!=null && !hostedDomain.isBlank()))) reject();
        var linked=db.queryForList("select user_id from external_identity where provider='google' and subject=?",Long.class,subject);
        var bootstrapIds = db.query("""
                select u.id, u.email from bootstrap_grant g join app_user u on u.id=g.user_id
                where u.email like '%@gmail.com' or u.email like '%@googlemail.com'
                """, (rs, row) -> new BootstrapEmail(rs.getLong("id"), rs.getString("email"))).stream()
                .filter(candidate -> canonicalGmail(candidate.email()).equals(canonicalEmail)).toList();
        if (bootstrapIds.size() > 1) reject();
        Accounts.Account account;
        if (!linked.isEmpty()) {
            account = accounts.lock(linked.getFirst());
            if (!canonicalGmail(account.email()).equals(canonicalEmail)) reject();
            if (!bootstrapIds.isEmpty() && bootstrapIds.getFirst().id() != account.id()) {
                account = accounts.lock(bootstrapIds.getFirst().id());
                db.update("update external_identity set user_id=? where provider='google' and subject=?",
                        account.id(), subject);
                linked = java.util.List.of(account.id());
            }
        } else {
            if (!bootstrapIds.isEmpty()) {
                account = accounts.lock(bootstrapIds.getFirst().id());
            } else {
                var matchingAccounts = accounts.byEmail(email).stream().toList();
                if (matchingAccounts.isEmpty()) matchingAccounts = gmailAccounts().stream()
                        .filter(candidate -> canonicalGmail(candidate.email()).equals(canonicalEmail)).toList();
                if (matchingAccounts.size() > 1) reject();
                if (matchingAccounts.isEmpty()) {
                    db.update("insert into app_user(email) values (?) on conflict do nothing",email);
                    matchingAccounts = accounts.byEmail(email).stream().toList();
                }
                account = accounts.lock(matchingAccounts.getFirst().id());
            }
        }
        if (!canonicalGmail(account.email()).equals(canonicalEmail)) reject();
        if (!account.enabled()) reject();
        var previous=db.queryForList("select subject from external_identity where provider='google' and user_id=?",String.class,account.id());
        if ((!linked.isEmpty() && linked.getFirst()!=account.id()) || (!previous.isEmpty() && !previous.getFirst().equals(subject))) reject();
        if (linked.isEmpty()) db.update("insert into external_identity(provider,subject,user_id) values ('google',?,?)",subject,account.id());
        db.update("update app_user set email_verified_at=coalesce(email_verified_at,?) where id=?",Timestamp.from(clock.instant()),account.id());
        bootstrap.grantVerified(account.id());
        return email;
    }

    private record BootstrapEmail(long id, String email) {}

    private java.util.List<Accounts.Account> gmailAccounts() {
        return db.query("select * from app_user where email like '%@gmail.com' or email like '%@googlemail.com'",
                (rs, row) -> new Accounts.Account(rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                        Accounts.Role.valueOf(rs.getString("role")), Accounts.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("email_verified_at") == null ? null : rs.getTimestamp("email_verified_at").toInstant(),
                        rs.getBoolean("developer"), rs.getBoolean("enabled")));
    }

    public static String canonicalGmail(String email) {
        email=AppProperties.normalize(email);
        if (email == null) return null;
        int at=email.lastIndexOf('@');
        if (at < 0) return email;
        String domain=email.substring(at+1);
        if (!domain.equals("gmail.com") && !domain.equals("googlemail.com")) return email;
        String local=email.substring(0,at).replace(".","");
        return local+"@gmail.com";
    }
    private static void reject() { throw new OAuth2AuthenticationException("google_identity_unavailable"); }
}
