package se.flygare;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PageController {
    private final Accounts accounts;
    private final Administration administration;

    public PageController(Accounts accounts, Administration administration) {
        this.accounts = accounts;
        this.administration = administration;
    }

    @GetMapping("/login")
    String login(Model model, @org.springframework.beans.factory.annotation.Value("${app.legacy-test-login:false}") boolean legacy) {
        model.addAttribute("legacyTestLogin",legacy);
        return "login";
    }

    @GetMapping("/login/confirm")
    String confirm(@RequestParam(defaultValue = "") String token, Model model) {
        // GET never consumes a token. Mail scanners cannot activate an account by following a link.
        model.addAttribute("token", token.matches("[A-Za-z0-9_-]{43}") ? token : "");
        return "confirm";
    }

    @GetMapping("/")
    String home(Authentication authentication, Model model) {
        var account = accounts.byEmail(authentication.getName()).orElseThrow();
        if (account.approved()) return "redirect:/app";
        model.addAttribute("account", account);
        return "home";
    }

    @GetMapping("/app")
    String application(Model model, Authentication authentication) {
        model.addAttribute("account", accounts.byEmail(authentication.getName()).orElseThrow());
        return "activities";
    }
}
