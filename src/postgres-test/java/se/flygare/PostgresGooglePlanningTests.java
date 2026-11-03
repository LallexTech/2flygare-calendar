package se.flygare;

import java.io.IOException;
import java.io.UncheckedIOException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresGooglePlanningTests extends GooglePlanningTests {
 private static final EmbeddedPostgres POSTGRES=start();
 private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().setPort(0).start();}catch(IOException ex){throw new UncheckedIOException(ex);}}
 @DynamicPropertySource static void database(DynamicPropertyRegistry properties){
  properties.add("spring.datasource.url",()->POSTGRES.getJdbcUrl("postgres","postgres"));
  properties.add("spring.datasource.username",()->"postgres");properties.add("spring.datasource.password",()->"postgres");
 }
 @AfterAll static void stop() throws IOException{POSTGRES.close();}
}
