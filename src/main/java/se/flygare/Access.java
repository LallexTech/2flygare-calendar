package se.flygare;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("access")
public class Access {
    private final Accounts accounts;
    public Access(Accounts accounts) { this.accounts = accounts; }
    public boolean admin(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && accounts.byEmail(auth.getName()).map(Accounts.Account::admin).orElse(false);
    }
    public boolean approved(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && accounts.byEmail(auth.getName()).map(Accounts.Account::approved).orElse(false);
    }
}
