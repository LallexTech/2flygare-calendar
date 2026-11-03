package se.flygare;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ManagedDeliveryTests {
    private final AppProperties properties = new AppProperties(URI.create("https://app.test"), "",
            new AppProperties.Bootstrap(null, null, "alexander@setup.test"), URI.create("https://auth.test/neondb/auth"));

    @Test
    void managedProviderReceivesOnlyTheConfiguredCallbackAndBrowserReceivesNoSecret() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var mail = mock(JavaMailSender.class);
        var tokens = mock(MagicTokens.class);
        var raw = "a".repeat(43);
        server.expect(requestTo("https://auth.test/neondb/auth/sign-in/magic-link"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Origin", "https://app.test"))
                .andExpect(jsonPath("$.email").value("alexander@setup.test"))
                .andExpect(jsonPath("$.callbackURL").value("https://app.test/login/confirm?token=" + raw))
                .andExpect(jsonPath("$.newUserCallbackURL").value("https://app.test/login/confirm?token=" + raw))
                .andRespond(withSuccess("{\"status\":true}", org.springframework.http.MediaType.APPLICATION_JSON));
        var delivery = new MagicLinkDelivery(mail, properties, tokens, builder);
        var request = new MockHttpServletRequest();
        request.addHeader("Host", "attacker.test");
        var response = new MockHttpServletResponse();
        delivery.handle(request, response, new DefaultOneTimeToken(raw, "alexander@setup.test", Instant.now().plusSeconds(600)));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?sent");
        assertThat(response.getContentAsString()).doesNotContain(raw);
        verifyNoInteractions(mail, tokens);
        server.verify();
    }

    @Test
    void providerFailureInvalidatesTheLocalChallengeWithoutLeakingDiagnostics() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://auth.test/neondb/auth/sign-in/magic-link"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private provider diagnostic"));
        var tokens = mock(MagicTokens.class);
        var response = new MockHttpServletResponse();
        new MagicLinkDelivery(mock(JavaMailSender.class), properties, tokens, builder)
                .handle(new MockHttpServletRequest(), response,
                        new DefaultOneTimeToken("b".repeat(43), "alexander@setup.test", Instant.now().plusSeconds(600)));
        verify(tokens).discard("b".repeat(43));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?unavailable");
        assertThat(response.getContentAsString()).doesNotContain("private provider diagnostic");
        server.verify();
    }
}
