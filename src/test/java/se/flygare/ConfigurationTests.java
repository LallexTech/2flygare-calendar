package se.flygare;

import java.net.URI;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTests {
    @Test
    void configuredEmailsAreValidUniqueAndNormalizedWithAtLeastOneAdministrator() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new AppProperties.Bootstrap("", "b@setup.test", "c@setup.test"))).isEmpty();
            assertThatThrownBy(() -> new AppProperties.Bootstrap("", "", "")).isInstanceOf(IllegalArgumentException.class);
            assertThat(validator.validate(new AppProperties.Bootstrap("invalid", "b@setup.test", "c@setup.test"))).isNotEmpty();
            assertThatThrownBy(() -> new AppProperties.Bootstrap("  A@SETUP.TEST ", "a@setup.test", "c@setup.test"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new AppProperties.Bootstrap("  A@SETUP.TEST ", "b@setup.test", "c@setup.test").tobiasEmail()).isEqualTo("a@setup.test");
            var alexanderOnly = new AppProperties.Bootstrap(null, "", "  ALEXANDER@SETUP.TEST ");
            assertThat(validator.validate(alexanderOnly)).isEmpty();
            assertThat(alexanderOnly.identities()).containsExactly(new AppProperties.Identity("alexander", "alexander@setup.test", "Alexander Sjögren", true));
        }
    }

    @Test
    void magicLinkOriginCannotUseAnInsecureRemoteHostOrEmbeddedCredentials() {
        var bootstrap = new AppProperties.Bootstrap("a@setup.test", "b@setup.test", "c@setup.test");
        for (var url : new String[]{"http://remote.test", "https://user:password@remote.test", "https://remote.test?redirect=elsewhere", "https://remote.test/path"}) {
            assertThatThrownBy(() -> new AppProperties(URI.create(url), "mail@setup.test", bootstrap)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatNoException().isThrownBy(() -> new AppProperties(URI.create("https://remote.test"), "mail@setup.test", bootstrap));
    }
}
