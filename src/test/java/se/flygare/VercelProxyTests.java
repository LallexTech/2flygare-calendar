package se.flygare;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:proxy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "vercel"})
class VercelProxyTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @MockitoBean JavaMailSender mail;
    @MockitoBean Clock clock;

    @Test
    void vercelForwardedClientAddressAndHttpsAreUsedBehindTheTrustedProxy() throws Exception {
        var instant = Instant.parse("2026-11-02T12:00:00Z");
        when(clock.instant()).thenReturn(instant);
        mvc.perform(get("/login").header("X-Forwarded-For", "198.51.100.20").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk()).andExpect(header().exists("Strict-Transport-Security"));
        mvc.perform(post("/login/link").with(csrf()).header("X-Forwarded-For", "198.51.100.20")
                        .header("X-Forwarded-Proto", "https").param("username", "visitor@proxy.test"))
                .andExpect(status().is3xxRedirection());
        assertThat(db.queryForObject("select bucket from link_rate", String.class))
                .isEqualTo(MagicTokens.digest("198.51.100.20") + ":" + instant.getEpochSecond() / 600);
        verify(mail).send(any(SimpleMailMessage.class));
    }
}
