package se.flygare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class EnvironmentView {
    private final boolean development;

    public EnvironmentView(@Value("${VERCEL_ENV:production}") String environment) {
        development = "preview".equalsIgnoreCase(environment.strip())
                || "development".equalsIgnoreCase(environment.strip());
    }

    @ModelAttribute("developmentEnvironment")
    public boolean developmentEnvironment() {
        return development;
    }
}
