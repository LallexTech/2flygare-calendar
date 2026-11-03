package se.flygare;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=PlanningController.class)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class PlanningErrors {
    @ExceptionHandler(PlanningService.Rejected.class)
    ResponseEntity<?> rejected(PlanningService.Rejected error) {
        return ResponseEntity.status(error.status).body(Map.of("code",error.code,"message",error.getMessage(),"conflicts",error.conflicts));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation() { return ResponseEntity.badRequest().body(Map.of("message","Kontrollera bokningens fält och försök igen.")); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database() { return ResponseEntity.status(409).body(Map.of("message","Ändringen kunde inte sparas. Uppdatera vyn och försök igen.")); }
}
