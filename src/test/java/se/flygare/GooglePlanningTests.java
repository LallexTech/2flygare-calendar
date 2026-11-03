package se.flygare;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.google-login=true","app.legacy-test-login=false","GOOGLE_CLIENT_ID=test.apps.googleusercontent.com","GOOGLE_CLIENT_SECRET=test-only","spring.datasource.url=jdbc:h2:mem:google-planning;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GooglePlanningTests {
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate db;
 @Autowired GoogleIdentity identities;
 @Autowired Accounts accounts;
 @BeforeEach void clean(){
  for(var table:List.of("spring_session_attributes","spring_session","external_identity","activity_employee","employee","audit_event","bootstrap_grant","activity","calendar","app_user"))db.update("delete from "+table);
  db.update("update planning_settings set daily_minutes=480,work_start='07:00',break_start='12:00',break_minutes=60 where id=1");
 }
 @Test void gmailDotAndGooglemailAliasesShareCanonicalIdentity(){
  assertThat(GoogleIdentity.canonicalGmail("fixt.ure1@gmail.com")).isEqualTo("fixture1@gmail.com");
  assertThat(GoogleIdentity.canonicalGmail("fixture1@googlemail.com")).isEqualTo("fixture1@gmail.com");
  assertThat(GoogleIdentity.canonicalGmail("fixt.ure2+tag3@gmail.com")).isEqualTo("fixture2+tag3@gmail.com");
  assertThat(GoogleIdentity.canonicalGmail("a.lex@workspace.example")).isEqualTo("a.lex@workspace.example");
  assertThat(GoogleLoginConfig.sameCanonicalEmail("fixture3@gmail.com","fixt.ure3@gmail.com")).isTrue();
  assertThat(GoogleLoginConfig.sameCanonicalEmail("fixture3@gmail.com","fixture4@gmail.com")).isFalse();
 }
 @Test void gmailAliasReusesExistingAccountAndBootstrapGrant() {
  db.update("insert into app_user(email,role,status,email_verified_at) values ('fix.ture5@gmail.com','ADMIN','APPROVED',current_timestamp)");
  long existingId=db.queryForObject("select id from app_user where email='fix.ture5@gmail.com'",Long.class);
  db.update("insert into app_user(email) values ('fixture5@gmail.com')");
  long aliasId=db.queryForObject("select id from app_user where email='fixture5@gmail.com'",Long.class);
  db.update("insert into bootstrap_grant(slot,user_id) values ('alexander',?)",existingId);
  db.update("insert into external_identity(provider,subject,user_id) values ('google','same-person',?)",aliasId);
  assertThat(accounts.byEmail("fixture5@gmail.com").orElseThrow().id()).isEqualTo(existingId);
  identities.verify("same-person","fixture5@gmail.com",true,null);
  assertThat(db.queryForObject("select count(*) from app_user",Integer.class)).isEqualTo(2);
  assertThat(db.queryForObject("select user_id from external_identity where subject='same-person'",Long.class)).isEqualTo(existingId);
  assertThat(db.queryForObject("select user_id from bootstrap_grant where slot='alexander'",Long.class)).isEqualTo(existingId);
 }
 @Test void googleUsesStateAndPkceAndDisablesEmailEndpoints() throws Exception {
  var response=mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection()).andReturn().getResponse();
  assertThat(response.getRedirectedUrl()).startsWith("https://accounts.google.com/").contains("state=","nonce=","code_challenge=","code_challenge_method=S256","redirect_uri=");
  mvc.perform(post("/login/link").with(csrf()).param("username","alexander@bootstrap.test")).andExpect(status().is3xxRedirection());
  assertThat(db.queryForObject("select count(*) from app_user",Integer.class)).isZero();
  var page=mvc.perform(get("/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(page).contains("/oauth2/authorization/google").doesNotContain("action=\"/login/link\"");
 }
 @Test void verifiedIdentityBootstrapIsIdempotentAndOtherUsersStayPending(){
  identities.verify("alex-sub","ALEXANDER@bootstrap.test",true,"bootstrap.test");
  identities.verify("alex-sub","alexander@bootstrap.test",true,"bootstrap.test");
  var alex=accounts.byEmail("alexander@bootstrap.test").orElseThrow();assertThat(alex.admin()).isTrue();assertThat(alex.developer()).isTrue();
  assertThat(db.queryForObject("select count(*) from bootstrap_grant",Integer.class)).isEqualTo(1);
  identities.verify("new-sub","fixture5@gmail.com",true,null);
  assertThat(accounts.byEmail("fixture5@gmail.com").orElseThrow().status()).isEqualTo(Accounts.Status.PENDING);
  db.update("update app_user set role='MEMBER' where id=?",alex.id());
  identities.verify("alex-sub","alexander@bootstrap.test",true,"bootstrap.test");assertThat(accounts.byEmail(alex.email()).orElseThrow().admin()).isFalse();
 }
 @Test void unverifiedOrReboundOrDisabledIdentitiesCannotGainAccess(){
  assertThatThrownBy(()->identities.verify("bad","alexander@bootstrap.test",false,"bootstrap.test")).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
  assertThatThrownBy(()->identities.verify("bad","someone@example.test",true,null)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
  identities.verify("stable","alexander@bootstrap.test",true,"bootstrap.test");
  assertThatThrownBy(()->identities.verify("recycled","alexander@bootstrap.test",true,"bootstrap.test")).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
  assertThatThrownBy(()->identities.verify("stable","fixture6@gmail.com",true,null)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
  assertThat(accounts.byEmail("fixture6@gmail.com")).isEmpty();
  db.update("update app_user set enabled=false where email='alexander@bootstrap.test'");
  assertThatThrownBy(()->identities.verify("stable","alexander@bootstrap.test",true,"bootstrap.test")).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
 }
 private void admin(){identities.verify("admin","alexander@bootstrap.test",true,"bootstrap.test");}
 private String booking(long project,long employee,String start,String end,Long version,boolean conflict){
  return "{\"calendarId\":"+project+",\"name\":\"Montera kök\",\"description\":\"\",\"start\":\""+start+"\",\"end\":\""+end+"\",\"status\":\"PLANNED\",\"employeeIds\":["+employee+"],\"version\":"+version+",\"allowConflict\":"+conflict+"}";
 }
 @Test void bookingsEnforceHalfHourConflictsVersionPermissionsAndAvailability() throws Exception {
  admin();db.update("insert into calendar(name) values ('Kök')");db.update("insert into employee(name) values ('Tony')");
  long p=db.queryForObject("select id from calendar",Long.class),e=db.queryForObject("select id from employee",Long.class);
  var tooShort=booking(p,e,"2030-11-04T06:00:00Z","2030-11-04T06:15:00Z",null,false);
  mvc.perform(post("/api/planning/bookings").with(user("alexander@bootstrap.test")).with(csrf()).contentType("application/json").content(tooShort)).andExpect(status().isBadRequest());
  String body=booking(p,e,"2030-11-04T06:00:00Z","2030-11-04T15:00:00Z",null,false);
  mvc.perform(post("/api/planning/bookings").with(user("alexander@bootstrap.test")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
  mvc.perform(post("/api/planning/bookings").with(user("alexander@bootstrap.test")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_CONFLICT"));
  long id=db.queryForObject("select id from activity",Long.class);
  mvc.perform(put("/api/planning/bookings/"+id).with(user("alexander@bootstrap.test")).with(csrf()).contentType("application/json").content(booking(p,e,"2030-11-04T07:00:00Z","2030-11-04T08:00:00Z",99L,false))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BOOKING"));
  mvc.perform(get("/api/planning?start=2030-11-04T00:00:00Z&end=2030-11-05T00:00:00Z").with(user("alexander@bootstrap.test"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.capacity[0].capacityMinutes").value(480)).andExpect(jsonPath("$.data.capacity[0].availableMinutes").value(0)).andExpect(jsonPath("$.data.capacity[0].scheduledMinutes").value(480));
  identities.verify("member","fixture7@gmail.com",true,null);
  mvc.perform(get("/api/planning?start=2030-11-04T00:00:00Z&end=2030-11-05T00:00:00Z").with(user("fixture7@gmail.com"))).andExpect(status().isForbidden());
  db.update("update app_user set status='APPROVED' where email='fixture7@gmail.com'");
  mvc.perform(get("/api/planning?start=2030-11-04T00:00:00Z&end=2030-11-05T00:00:00Z").with(user("fixture7@gmail.com"))).andExpect(status().isOk());
  mvc.perform(post("/api/planning/bookings").with(user("fixture7@gmail.com")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/planning/bookings").with(user("alexander@bootstrap.test")).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(put("/api/planning/bookings/"+id).with(user("alexander@bootstrap.test")).with(csrf()).contentType("application/json").content(booking(p,e,"2030-11-04T06:00:00Z","2030-11-04T15:00:00Z",0L,false).replace("PLANNED","DONE"))).andExpect(status().isOk());
  mvc.perform(get("/api/planning?start=2030-11-04T00:00:00Z&end=2030-11-05T00:00:00Z").with(user("alexander@bootstrap.test"))).andExpect(jsonPath("$.data.capacity[0].availableMinutes").value(480)).andExpect(jsonPath("$.data.capacity[0].completedMinutes").value(480));
 }
}
