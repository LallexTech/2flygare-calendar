package se.flygare;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapService {
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final AppProperties properties;

    public BootstrapService(JdbcTemplate db, Accounts accounts, AppProperties properties) {
        this.db = db;
        this.accounts = accounts;
        this.properties = properties;
    }

    @Bean
    ApplicationRunner reconcileInitialAdministrators(BootstrapService service) {
        return args -> service.reconcile();
    }

    @Transactional
    public void reconcile() {
        // Refuse silent re-binding of an already claimed bootstrap identity.
        for (var identity : properties.bootstrap().identities()) {
            var claimedEmails = db.queryForList("""
                    select u.email from bootstrap_grant g join app_user u on u.id = g.user_id
                    where g.slot = ?
                    """, String.class, identity.slot());
            if (!claimedEmails.isEmpty()) {
                if (!GoogleIdentity.canonicalGmail(claimedEmails.getFirst())
                        .equals(GoogleIdentity.canonicalGmail(identity.email()))) {
                    throw new IllegalStateException("Configured bootstrap identity changed for " + identity.slot()
                            + "; use the existing administration dashboard for later administrator changes");
                }
                // A claimed slot is already reconciled. A second row using a Gmail alias
                // must never be granted the same administrator slot.
                continue;
            }
            accounts.byEmail(identity.email()).ifPresent(user -> grantVerified(user.id()));
        }
    }

    @Transactional
    public void grantVerified(long userId) {
        var user = accounts.lock(userId);
        if (!user.enabled() || user.verifiedAt() == null) return;
        var identity = properties.bootstrap().identities().stream()
                .filter(candidate -> GoogleIdentity.canonicalGmail(candidate.email())
                        .equals(GoogleIdentity.canonicalGmail(user.email()))).findFirst();
        if (identity.isEmpty()) return;
        var configured = identity.get();
        var existing = db.queryForList("select user_id from bootstrap_grant where slot = ?", Long.class, configured.slot());
        if (!existing.isEmpty()) {
            if (existing.getFirst() != userId) throw new IllegalStateException("Bootstrap identity already claimed");
            return;
        }
        db.update("insert into bootstrap_grant(slot, user_id) values (?, ?)", configured.slot(), userId);
        db.update("""
                update app_user set role = 'ADMIN', status = 'APPROVED', display_name = ?, developer = ?
                where id = ? and email_verified_at is not null
                """, configured.name(), configured.developer(), userId);
        db.update("insert into audit_event(actor, action, target_id) values ('BOOTSTRAP', 'INITIAL_ADMIN_GRANTED', ?)", userId);
    }
}
