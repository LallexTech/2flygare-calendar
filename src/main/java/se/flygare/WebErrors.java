package se.flygare;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;

@ControllerAdvice(annotations = Controller.class)
public class WebErrors {
    @ExceptionHandler(ResponseStatusException.class)
    String rejected(ResponseStatusException ex, Model model, HttpServletResponse response) {
        response.setStatus(ex.getStatusCode().value());
        model.addAttribute("message", ex.getReason());
        return "problem";
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String validation(Model model) {
        model.addAttribute("message", "Check the form fields and try again.");
        return "problem";
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String conflict(Model model) {
        model.addAttribute("message", "This change could not be saved. Check linked records and try again.");
        return "problem";
    }
}
