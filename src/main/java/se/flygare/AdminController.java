package se.flygare;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final Accounts accounts;
    private final Administration administration;
    private final PlanningService planning;

    public AdminController(Accounts accounts, Administration administration,PlanningService planning) {
        this.accounts = accounts;
        this.administration = administration;
        this.planning=planning;
    }

    @GetMapping({"", "/"})
    String dashboard(Authentication authentication, Model model) {
        model.addAttribute("account", accounts.byEmail(authentication.getName()).orElseThrow());
        model.addAttribute("users", accounts.all());
        var groups=administration.resources(Administration.Kind.GROUP);
        var calendars=administration.resources(Administration.Kind.CALENDAR);
        calendars.forEach(calendar->calendar.put("group_name",groups.stream()
                .filter(group->Objects.equals(group.get("id"),calendar.get("group_id")))
                .map(group->group.get("name")).findFirst().orElse("Ingen projektgrupp")));
        model.addAttribute("groups", groups);
        model.addAttribute("calendars", calendars);
        model.addAttribute("activities", administration.resources(Administration.Kind.ACTIVITY));
        model.addAttribute("memberships", administration.memberships());
        model.addAttribute("employees",planning.employees());
        model.addAttribute("projectAccess",administration.projectAccess());
        return "admin";
    }

    public record UserForm(@Size(max = 120) String displayName, @NotNull Accounts.Role role,
                           @NotNull Accounts.Status status, boolean enabled) { }

    @PostMapping("/users/{id}")
    String saveUser(Authentication authentication, @PathVariable long id, @Valid @ModelAttribute UserForm form) {
        administration.saveUser(authentication.getName(), id,
                form.displayName() == null ? "" : form.displayName().strip(), form.role(), form.status(), form.enabled());
        return "redirect:/admin#users";
    }

    @PostMapping("/users/{id}/delete")
    String deleteUser(Authentication authentication, @PathVariable long id) {
        administration.deleteUser(authentication.getName(), id);
        return "redirect:/admin#users";
    }

    public record ResourceForm(Long id, @NotBlank @Size(max = 120) String name,
                               @Size(max = 2000) String description, Long parentId,
                               String startsAt, String endsAt) { }

    @PostMapping("/resources/{kind}")
    String saveResource(Authentication authentication, @PathVariable Administration.Kind kind,
                        @Valid @ModelAttribute ResourceForm form) {
        administration.saveResource(authentication.getName(), kind, form.id(), form.name().strip(),
                form.description() == null ? "" : form.description().strip(), form.parentId(), form.startsAt(), form.endsAt());
        return "redirect:/admin#" + switch (kind) {
            case CALENDAR -> "calendars";
            case ACTIVITY -> "activities";
            case GROUP -> "groups";
        };
    }

    @PostMapping("/resources/{kind}/{id}/delete")
    String deleteResource(Authentication authentication, @PathVariable Administration.Kind kind, @PathVariable long id,
                          @RequestParam(required = false) Long expectedActivityCount) {
        if (kind == Administration.Kind.CALENDAR) {
            if (expectedActivityCount == null || expectedActivityCount < 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "A project activity count is required.");
            }
            administration.deleteCalendar(authentication.getName(), id, expectedActivityCount);
        } else {
            administration.deleteResource(authentication.getName(), kind, id);
        }
        return "redirect:/admin#" + switch (kind) {
            case CALENDAR -> "calendars";
            case ACTIVITY -> "activities";
            case GROUP -> "groups";
        };
    }

    @PostMapping("/memberships")
    String membership(Authentication authentication, @RequestParam long groupId, @RequestParam long userId,
                      @RequestParam boolean add) {
        administration.membership(authentication.getName(), groupId, userId, add);
        return "redirect:/admin#groups";
    }

    public record EmployeeLinkForm(@NotNull Long userId,Long employeeId) { }
    @PostMapping("/employee-links")
    String employeeLink(Authentication authentication,@Valid @ModelAttribute EmployeeLinkForm form) {
        administration.linkEmployee(authentication.getName(),form.userId(),form.employeeId());
        return "redirect:/admin#project-access";
    }

    public record ProjectAccessForm(@NotNull Long userId,@NotNull Long calendarId,@NotBlank String level,Boolean canMoveOwn) { }
    @PostMapping("/project-access")
    String projectAccess(Authentication authentication,@Valid @ModelAttribute ProjectAccessForm form) {
        administration.saveProjectAccess(authentication.getName(),form.userId(),form.calendarId(),form.level(),Boolean.TRUE.equals(form.canMoveOwn()));
        return "redirect:/admin#project-access";
    }

    @PostMapping("/maintenance")
    String maintenance(Authentication authentication) {
        administration.cleanExpired(authentication.getName());
        return "redirect:/admin";
    }
}
