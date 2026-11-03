package se.flygare;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.*;

@Configuration
@ConditionalOnProperty(name="app.google-login",havingValue="true")
public class GoogleLoginConfig {
    @Bean ClientRegistrationRepository clientRegistrations(@Value("${GOOGLE_CLIENT_ID}") String id,
            @Value("${GOOGLE_CLIENT_SECRET}") String secret, AppProperties properties) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId(id).clientSecret(secret).scope("openid","email","profile")
            .redirectUri(properties.baseUrl().toString().replaceAll("/$","")+"/login/oauth2/code/google").build());
    }
    @Bean OAuth2UserService<OidcUserRequest,OidcUser> googleUserService(GoogleIdentity identities) {
        var delegate=new OidcUserService();
        return request -> {
            var user=delegate.loadUser(request);
            String email;
            try { email=identities.verify(user.getSubject(),user.getEmail(),user.getEmailVerified(),user.getClaimAsString("hd")); }
            catch(org.springframework.dao.DataAccessException ex) { throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("google_identity_unavailable"); }
            // Use the verified email as the local principal name; local permissions are always read from our database.
            if (!sameCanonicalEmail(email, user.getEmail())) throw new IllegalStateException("Identity mismatch");
            return new DefaultOidcUser(user.getAuthorities(),user.getIdToken(),user.getUserInfo(),"email");
        };
    }

    static boolean sameCanonicalEmail(String verifiedEmail, String googleEmail) {
        return GoogleIdentity.canonicalGmail(verifiedEmail)
                .equals(GoogleIdentity.canonicalGmail(AppProperties.normalize(googleEmail)));
    }
}
