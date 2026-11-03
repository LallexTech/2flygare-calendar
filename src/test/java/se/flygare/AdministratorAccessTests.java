package se.flygare;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdministratorAccessTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired Accounts accounts;
    @Autowired MagicTokens tokens;
    @Autowired BootstrapService bootstrap;
    @MockitoBean JavaMailSender mail;
    @MockitoBean Clock clock;
    private final Instant now = Instant.parse("2026-11-02T12:00:00Z");

    @BeforeEach
    void clean() {
        for (var table : List.of("spring_session_attributes", "spring_session", "audit_event", "magic_token",
                "link_rate", "bootstrap_grant", "group_member", "activity", "calendar", "activity_group", "app_user")) {
            db.update("delete from " + table);
        }
        when(clock.instant()).thenReturn(now);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tobias", "tony", "alexander"})
    void eachAdministratorCanVerifySignInAndUseEveryManagementCapability(String name) throws Exception {
        var email = name + "@bootstrap.test";
        var browser = signIn(email);
        var account = accounts.byEmail(email).orElseThrow();
        assertThat(account.admin()).isTrue();
        assertThat(account.developer()).isEqualTo(name.equals("alexander"));
        assertThat(account.displayName()).isEqualTo(switch (name) {
            case "tobias" -> "Tobias Flygare";
            case "tony" -> "Tony Flygare";
            default -> "Alexander Sjögren";
        });
        var dashboard = browser.get("/admin");
        assertThat(dashboard.getResponse().getStatus()).isEqualTo(200);
        assertThat(dashboard.getResponse().getContentAsString()).contains("Administration", "Users, roles", "Calendars", "Activities");

        // Ordinary registration remains pending after proof of mailbox ownership.
        var pendingBrowser = signIn("member@registration.test");
        var member = accounts.byEmail("member@registration.test").orElseThrow();
        assertThat(member.status()).isEqualTo(Accounts.Status.PENDING);
        assertThat(pendingBrowser.get("/app").getResponse().getStatus()).isEqualTo(403);
        browser.get("/admin");
        assertThat(browser.post("/admin/users/" + member.id(), Map.of("displayName", "Member", "role", "MEMBER",
                "status", "APPROVED", "enabled", "true")).getResponse().getStatus()).isEqualTo(302);
        assertThat(pendingBrowser.get("/app").getResponse().getStatus()).isEqualTo(200);
        assertThat(browser.post("/admin/users/" + member.id(), Map.of("role", "ADMIN", "status", "APPROVED", "enabled", "true"))
                .getResponse().getStatus()).isEqualTo(302);
        assertThat(pendingBrowser.get("/admin").getResponse().getStatus()).isEqualTo(200);
        browser.get("/admin");
        browser.post("/admin/users/" + member.id(), Map.of("role", "MEMBER", "status", "APPROVED", "enabled", "true"));
        assertThat(pendingBrowser.get("/admin").getResponse().getStatus()).isEqualTo(403);

        save(browser, "GROUP", Map.of("name", "Group", "description", "Description"));
        long group = id("activity_group");
        save(browser, "GROUP", Map.of("id", "" + group, "name", "Updated group"));
        assertThat(browser.post("/admin/memberships", Map.of("groupId", "" + group, "userId", "" + member.id(), "add", "true"))
                .getResponse().getStatus()).isEqualTo(302);
        save(browser, "CALENDAR", Map.of("name", "Calendar", "parentId", "" + group));
        long calendar = id("calendar");
        save(browser, "CALENDAR", Map.of("id", "" + calendar, "name", "Updated calendar", "parentId", "" + group));
        save(browser, "ACTIVITY", Map.of("name", "Activity", "parentId", "" + calendar,
                "startsAt", "2026-11-03T09:00:00+01:00", "endsAt", "2026-11-03T10:00:00+01:00"));
        long activity = id("activity");
        save(browser, "ACTIVITY", Map.of("id", "" + activity, "name", "Updated activity", "parentId", "" + calendar,
                "startsAt", "2026-11-03T10:00:00Z", "endsAt", "2026-11-03T11:00:00Z"));
        var populatedDashboard = browser.get("/admin");
        assertThat(populatedDashboard.getResponse().getStatus()).isEqualTo(200);
        assertThat(populatedDashboard.getResponse().getContentAsString()).contains("Updated group", "Updated calendar", "Updated activity");
        assertThat(browser.post("/admin/resources/CALENDAR/" + calendar + "/delete", Map.of()).getResponse().getStatus()).isEqualTo(400);
        for (var entry : Map.of("ACTIVITY", activity, "CALENDAR", calendar, "GROUP", group).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            // Alphabetical order is ACTIVITY, CALENDAR, GROUP, respecting foreign keys.
            assertThat(browser.post("/admin/resources/" + entry.getKey() + "/" + entry.getValue() + "/delete", Map.of())
                    .getResponse().getStatus()).isEqualTo(302);
        }
        assertThat(browser.post("/admin/users/" + member.id() + "/delete", Map.of()).getResponse().getStatus()).isEqualTo(302);
        assertThat(accounts.byEmail(member.email())).isEmpty();
        assertThat(count("audit_event")).isGreaterThan(10);
    }

    @Test
    void requestAndGetConfirmationCannotVerifyOrBootstrapAndTokensAreHashed() throws Exception {
        var browser = new Browser();
        browser.get("/login");
        var result = browser.post("/login/link", Map.of("username", "  TOBIAS@BOOTSTRAP.TEST  ", "role", "ADMIN", "developer", "true"));
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?sent");
        var user = accounts.byEmail("tobias@bootstrap.test").orElseThrow();
        assertThat(user.role()).isEqualTo(Accounts.Role.MEMBER);
        assertThat(user.status()).isEqualTo(Accounts.Status.PENDING);
        assertThat(user.verifiedAt()).isNull();
        assertThat(count("bootstrap_grant")).isZero();
        var raw = lastRawToken();
        assertThat(db.queryForObject("select digest from magic_token", String.class)).isEqualTo(MagicTokens.digest(raw)).isNotEqualTo(raw);
        var page = browser.get("/login/confirm?token=" + raw);
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(page.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(count("bootstrap_grant")).isZero();
        assertThat(accounts.byEmail(user.email()).orElseThrow().verifiedAt()).isNull();
        assertThat(browser.post("/login/consume", Map.of("token", raw)).getResponse().getRedirectedUrl()).isEqualTo("/");
        browser.get("/app");
        assertThat(browser.post("/login/consume", Map.of("token", raw)).getResponse().getRedirectedUrl()).isEqualTo("/login?invalid");
    }

    @Test
    void onlyExactConfiguredVerifiedEmailsReceiveBootstrapAndDeveloperIsNotARole() throws Exception {
        for (String email : List.of("alexander+alias@bootstrap.test", "alexander@other.test", "imposter@registration.test")) {
            signIn(email);
            var user = accounts.byEmail(email).orElseThrow();
            assertThat(user.status()).isEqualTo(Accounts.Status.PENDING);
            assertThat(user.role()).isEqualTo(Accounts.Role.MEMBER);
            assertThat(user.developer()).isFalse();
        }
        // Even a stored informational label cannot authorize an ordinary pending user.
        db.update("update app_user set developer = true where email = ?", "imposter@registration.test");
        mvc.perform(get("/admin").with(user("imposter@registration.test").roles("ADMIN", "DEVELOPER"))).andExpect(status().isForbidden());
        assertThat(List.of(Accounts.Role.values())).containsExactly(Accounts.Role.MEMBER, Accounts.Role.ADMIN);
        assertThat(count("bootstrap_grant")).isZero();
    }

    @Test
    void reconcileIsIdempotentSkipsUnverifiedAndDoesNotUndoRevocations() throws Exception {
        tokens.generate(new GenerateOneTimeTokenRequest("tony@bootstrap.test"));
        bootstrap.reconcile();
        assertThat(count("bootstrap_grant")).isZero();
        signIn("tobias@bootstrap.test");
        for (int i = 0; i < 3; i++) bootstrap.reconcile();
        assertThat(count("bootstrap_grant")).isOne();
        assertThat(db.queryForObject("select count(*) from audit_event where action = 'INITIAL_ADMIN_GRANTED'", Long.class)).isOne();
        db.update("update app_user set role = 'MEMBER' where email = ?", "tobias@bootstrap.test");
        bootstrap.reconcile();
        db.update("update app_user set last_link_at = null");
        var browser = signIn("tobias@bootstrap.test");
        assertThat(browser.get("/admin").getResponse().getStatus()).isEqualTo(403);
        assertThat(accounts.byEmail("tobias@bootstrap.test").orElseThrow().role()).isEqualTo(Accounts.Role.MEMBER);
        assertThat(count("bootstrap_grant")).isOne();
    }

    @Test
    void startupCanBootstrapAnAlreadyVerifiedConfiguredAccountAndRejectsRebinding() {
        tokens.generate(new GenerateOneTimeTokenRequest("tony@bootstrap.test"));
        db.update("update app_user set email_verified_at = ?", Timestamp.from(now));
        bootstrap.reconcile();
        assertThat(accounts.byEmail("tony@bootstrap.test").orElseThrow().admin()).isTrue();
        db.update("update app_user set email = ? where email = ?", "changed@bootstrap.test", "tony@bootstrap.test");
        assertThatThrownBy(bootstrap::reconcile).isInstanceOf(IllegalStateException.class).hasMessageContaining("identity changed");
    }

    @Test
    void expiredSupersededMalformedAndDisabledTokensCannotAuthenticate() {
        var first = tokens.generate(new GenerateOneTimeTokenRequest("alexander@bootstrap.test"));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        var second = tokens.generate(new GenerateOneTimeTokenRequest("alexander@bootstrap.test"));
        assertThat(tokens.consume(new OneTimeTokenAuthenticationToken(first.getTokenValue()))).isNull();
        when(clock.instant()).thenReturn(now.plusSeconds(660));
        assertThat(tokens.consume(new OneTimeTokenAuthenticationToken(second.getTokenValue()))).isNull();
        assertThat(tokens.consume(new OneTimeTokenAuthenticationToken("invalid"))).isNull();
        assertThat(tokens.consume(new OneTimeTokenAuthenticationToken("a".repeat(43)))).isNull();
        when(clock.instant()).thenReturn(now.plusSeconds(700));
        var third = tokens.generate(new GenerateOneTimeTokenRequest("alexander@bootstrap.test"));
        db.update("update app_user set enabled = false");
        assertThat(tokens.consume(new OneTimeTokenAuthenticationToken(third.getTokenValue()))).isNull();
        assertThat(count("bootstrap_grant")).isZero();
    }

    @Test
    void concurrentRedemptionsCreateExactlyOneGrantAndOneSuccessfulConsumption() throws Exception {
        var token = tokens.generate(new GenerateOneTimeTokenRequest("tobias@bootstrap.test"));
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 6; i++) tasks.add(() -> tokens.consume(new OneTimeTokenAuthenticationToken(token.getTokenValue())) != null);
            var results = executor.invokeAll(tasks);
            int successes = 0;
            for (var result : results) if (result.get()) successes++;
            assertThat(successes).isOne();
        }
        assertThat(count("bootstrap_grant")).isOne();
        assertThat(count("magic_token")).isZero();
    }

    @Test
    void generationIsLimitedByEmailAndRemoteAddressAndInvalidInputsHaveGenericResponse() throws Exception {
        mvc.perform(post("/login/link").with(csrf()).param("username", "tobias@bootstrap.test"))
                .andExpect(redirectedUrl("/login?sent"));
        mvc.perform(post("/login/link").with(csrf()).param("username", "tobias@bootstrap.test"))
                .andExpect(redirectedUrl("/login?sent"));
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        for (int i = 0; i < 25; i++) {
            mvc.perform(post("/login/link").with(csrf()).param("username", "address" + i + "@rate.test"))
                    .andExpect(redirectedUrl("/login?sent"));
        }
        verify(mail, times(19)).send(any(SimpleMailMessage.class));
        for (String value : List.of("", " ", "malformed", "bad\nheader")) {
            mvc.perform(post("/login/link").with(csrf()).with(request -> { request.setRemoteAddr("192.0.2.20"); return request; }).param("username", value))
                    .andExpect(redirectedUrl("/login?sent"));
        }
    }

    @Test
    void mailFailuresAreGenericAndInvalidateTheUndeliveredToken() throws Exception {
        doThrow(new MailSendException("private provider diagnostic")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(post("/login/link").with(csrf()).param("username", "tony@bootstrap.test"))
                .andExpect(redirectedUrl("/login?unavailable"));
        assertThat(count("magic_token")).isZero();
        assertThat(count("bootstrap_grant")).isZero();
    }

    @Test
    void authorizationAndCsrfCannotBeBypassedAndLastAdminIsProtected() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/login/link").param("username", "tobias@bootstrap.test")).andExpect(status().isForbidden());
        mvc.perform(post("/login/consume").param("token", "a".repeat(43))).andExpect(status().isForbidden());
        var admin = signIn("tobias@bootstrap.test");
        var adminId = accounts.byEmail("tobias@bootstrap.test").orElseThrow().id();
        admin.get("/admin");
        assertThat(admin.post("/admin/users/" + adminId, Map.of("role", "MEMBER", "status", "APPROVED", "enabled", "true"))
                .getResponse().getStatus()).isEqualTo(400);
        var pending = signIn("pending@registration.test");
        pending.get("/");
        assertThat(pending.post("/admin/resources/GROUP", Map.of("name", "Forbidden"))
                .getResponse().getStatus()).isEqualTo(403);
        mvc.perform(post("/admin/resources/GROUP").cookie(admin.cookie).param("name", "Missing CSRF"))
                .andExpect(status().isForbidden());
        tokens.generate(new GenerateOneTimeTokenRequest("unverified@registration.test"));
        var unverified = accounts.byEmail("unverified@registration.test").orElseThrow();
        admin.get("/admin");
        assertThat(admin.post("/admin/users/" + unverified.id(), Map.of("role", "ADMIN", "status", "APPROVED", "enabled", "true"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(admin.post("/admin/users/" + adminId + "/delete", Map.of()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void existingSessionsUseCurrentDatabaseAccessAndSurviveNewBrowserRequests() throws Exception {
        var browser = signIn("alexander@bootstrap.test");
        assertThat(count("spring_session")).isPositive();
        var independent = new Browser();
        independent.cookie = browser.cookie;
        assertThat(independent.get("/admin").getResponse().getStatus()).isEqualTo(200);
        db.update("update app_user set enabled = false where email = ?", "alexander@bootstrap.test");
        assertThat(independent.get("/admin").getResponse().getStatus()).isEqualTo(403);
        assertThat(independent.get("/app").getResponse().getStatus()).isEqualTo(403);
    }

    private void save(Browser browser, String kind, Map<String, String> fields) throws Exception {
        assertThat(browser.post("/admin/resources/" + kind, fields).getResponse().getStatus()).isEqualTo(302);
    }

    private long id(String table) { return db.queryForObject("select max(id) from " + table, Long.class); }
    private long count(String table) { return db.queryForObject("select count(*) from " + table, Long.class); }

    private String lastRawToken() {
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(captor.capture());
        var text = captor.getAllValues().getLast().getText();
        var matcher = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private Browser signIn(String email) throws Exception {
        var browser = new Browser();
        browser.get("/login");
        assertThat(browser.post("/login/link", Map.of("username", email)).getResponse().getStatus()).isEqualTo(302);
        var raw = lastRawToken();
        browser.get("/login/confirm?token=" + raw);
        assertThat(browser.post("/login/consume", Map.of("token", raw)).getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(browser.get("/").getResponse().getStatus()).isIn(200,302);
        return browser;
    }

    private class Browser {
        Cookie cookie;
        String csrf;
        MvcResult get(String path) throws Exception {
            return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path));
        }
        MvcResult post(String path, Map<String, String> values) throws Exception {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
            values.forEach(request::param);
            if (csrf != null) request.param("_csrf", csrf);
            return perform(request);
        }
        private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
            if (cookie != null) request.cookie(cookie);
            var result = mvc.perform(request).andReturn();
            var session = result.getResponse().getCookie("SESSION");
            if (session != null) cookie = session;
            var matcher = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(result.getResponse().getContentAsString());
            if (matcher.find()) csrf = matcher.group(1);
            return result;
        }
    }

}
