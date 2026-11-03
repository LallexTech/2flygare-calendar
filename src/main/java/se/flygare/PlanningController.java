package se.flygare;

import java.time.Instant;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/planning")
public class PlanningController {
    private final PlanningService planning; private final Access access;
    public PlanningController(PlanningService planning,Access access) { this.planning=planning;this.access=access; }
    @GetMapping
    Map<String,Object> snapshot(@RequestParam Instant start,@RequestParam Instant end,Authentication auth) {
        var snapshot=planning.snapshot(start,end,auth.getName());boolean canCreate=snapshot.projects().stream().anyMatch(project->project.accessLevel().equals("LEAD"));
        return Map.of("data",snapshot,"canManage",canCreate,"isAdmin",access.admin(auth),"timezone","Europe/Stockholm");
    }
    @PostMapping("/bookings")
    Map<String,Long> create(@Valid @RequestBody PlanningService.BookingInput input,Authentication auth) {
        return Map.of("id",planning.save(auth.getName(),null,input));
    }
    @PostMapping("/bookings/batch")
    Map<String,Object> createBatch(@RequestBody java.util.List<PlanningService.BookingInput> inputs,Authentication auth) {
        return Map.of("ids",planning.saveBatch(auth.getName(),inputs));
    }
    @PutMapping("/bookings/{id}")
    Map<String,Long> update(@PathVariable long id,@Valid @RequestBody PlanningService.BookingInput input,Authentication auth) {
        return Map.of("id",planning.save(auth.getName(),id,input));
    }
    @DeleteMapping("/bookings/{id}")
    void delete(@PathVariable long id,@RequestParam long version,Authentication auth) { planning.delete(auth.getName(),id,version); }
    @PostMapping("/employees")
    Map<String,Long> employee(@Valid @RequestBody PlanningService.EmployeeInput input,Authentication auth) {
        return Map.of("id",planning.saveEmployee(auth.getName(),null,input));
    }
    @PutMapping("/employees/{id}")
    Map<String,Long> employee(@PathVariable long id,@Valid @RequestBody PlanningService.EmployeeInput input,Authentication auth) {
        return Map.of("id",planning.saveEmployee(auth.getName(),id,input));
    }
    @PutMapping("/settings")
    void settings(@Valid @RequestBody PlanningService.SettingsInput input,Authentication auth) { planning.saveSettings(auth.getName(),input); }
    @PostMapping("/projects")
    Map<String,Long> project(@Valid @RequestBody PlanningService.ProjectInput input,Authentication auth) {
        return Map.of("id",planning.createProject(auth.getName(),input));
    }
}
