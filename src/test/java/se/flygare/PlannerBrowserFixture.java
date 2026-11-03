package se.flygare;

import java.nio.file.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;

/** Explicit loopback-only browser fixture. Not included in the application jar. */
public class PlannerBrowserFixture {
 public static void main(String[] args) throws Exception {
  try(var context=SpringApplication.run(FlygareApplication.class,
    "--spring.profiles.active=test","--server.address=127.0.0.1","--server.port=19876",
    "--server.servlet.session.cookie.secure=false","--app.base-url=http://localhost:19876",
    "--spring.datasource.url=jdbc:h2:mem:browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
   var db=context.getBean(JdbcTemplate.class);
   db.update("insert into employee(name) values ('Tony'),('Tobias'),('Jesper'),('Magnus'),('Anders')");
   db.update("insert into calendar(name,color) values ('Villa Björken','#187a58'),('BRF Eken','#326cab')");
   var token=context.getBean(MagicTokens.class).generate(new GenerateOneTimeTokenRequest("alexander@bootstrap.test"));
   Files.writeString(Path.of(".vercel/local-browser-login.json"),"{\"url\":\"http://localhost:19876/login/confirm?token="+token.getTokenValue()+"\"}");
   long deadline=System.currentTimeMillis()+600_000;
   while(!Files.exists(Path.of(".vercel/ui-done"))&&System.currentTimeMillis()<deadline)Thread.sleep(500);
  }
 }
}
