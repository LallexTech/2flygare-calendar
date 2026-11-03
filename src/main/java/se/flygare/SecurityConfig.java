package se.flygare;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean
    org.springframework.web.client.RestClient.Builder mailHttpClient() {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        return org.springframework.web.client.RestClient.builder().requestFactory(factory);
    }

    @Bean
    UserDetailsService userDetailsService(Accounts accounts) {
        return email -> {
            var account = accounts.byEmail(email).filter(user -> user.enabled() && user.verifiedAt() != null)
                    .orElseThrow(() -> new UsernameNotFoundException("Identity unavailable"));
            return User.withUsername(account.email()).password("").roles(account.role().name()).build();
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MagicTokens tokens,
                                            MagicLinkDelivery delivery, Access access,
                                            @org.springframework.beans.factory.annotation.Value("${app.google-login:false}") boolean google,
                                            @org.springframework.beans.factory.annotation.Value("${app.legacy-test-login:false}") boolean legacy,
                                            org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.userinfo.OAuth2UserService<org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,org.springframework.security.oauth2.core.oidc.user.OidcUser>> googleUsers,
                                            org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository> registrations) throws Exception {
        var success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl("/");
        success.setAlwaysUseDefaultTargetUrl(true);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login/link", "/login/confirm", "/login/consume").access((authentication, context) -> new AuthorizationDecision(legacy))
                        .requestMatchers("/login", "/oauth2/**", "/login/oauth2/**", "/error", "/style.css",
                                "/manifest.webmanifest", "/favicon.ico", "/icons/**", "/planner/**", "/install.js", "/planner.css").permitAll()
                        .requestMatchers("/admin", "/admin/**").access((authentication, context) ->
                                new AuthorizationDecision(access.admin(authentication.get())))
                        .requestMatchers("/app", "/app/**", "/api/planning", "/api/planning/**").access((authentication, context) ->
                                new AuthorizationDecision(access.approved(authentication.get())))
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                    .defaultAuthenticationEntryPointFor((request,response,error)->response.sendError(401),request->request.getRequestURI().startsWith("/api/")))
                ;
        if (google) {
            var resolver=new org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver(registrations.getObject(),"/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(oauth -> oauth.loginPage("/login")
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .userInfoEndpoint(info -> info.oidcUserService(googleUsers.getObject()))
                .successHandler(success).failureUrl("/login?invalid"));
        }
        if (legacy) http.oneTimeTokenLogin(ott -> ott
                        .loginPage("/login")
                        .tokenGeneratingUrl("/login/link")
                        .loginProcessingUrl("/login/consume")
                        .showDefaultSubmitPage(false)
                        .generateRequestResolver(request -> {
                            var email = request.getParameter("username");
                            return new GenerateOneTimeTokenRequest(tokens.allowRequest(request.getRemoteAddr())
                                    && org.springframework.util.StringUtils.hasText(email) ? email : "ignored");
                        })
                        .tokenService(tokens)
                        .tokenGenerationSuccessHandler(delivery)
                        .authenticationSuccessHandler(success)
                        .authenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler("/login?invalid")));
        return http.logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'")))
                .build();
    }
}
