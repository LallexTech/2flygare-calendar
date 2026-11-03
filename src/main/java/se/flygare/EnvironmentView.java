package se.flygare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class EnvironmentView {
    private final boolean development;

    public EnvironmentView(@Value("${APP_ENVIRONMENT:production}") String environment,
                           @Value("${VERCEL_ENV:}") String vercelEnvironment) {
        development = !"production".equalsIgnoreCase(vercelEnvironment.strip())
                && ("preview".equalsIgnoreCase(environment.strip())
                    || "development".equalsIgnoreCase(environment.strip()));
    }

    @ModelAttribute("developmentEnvironment")
    public boolean developmentEnvironment() {
        return development;
    }
}
