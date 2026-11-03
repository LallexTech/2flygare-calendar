package se.flygare;

import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Administration {
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final PlanningService planning;

    public Administration(JdbcTemplate db, Accounts accounts,PlanningService planning) { this.db = db; this.accounts = accounts;this.planning=planning; }

    public enum Kind {
        GROUP("activity_group"), CALENDAR("calendar"), ACTIVITY("activity");
        private final String table;
        Kind(String table) { this.table = table; }
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void saveUser(String actor, long id, String name, Accounts.Role role,
                         Accounts.Status status, boolean enabled) {
        lockUsers();
        var user = accounts.lock(id);
        if ((status == Accounts.Status.APPROVED || role == Accounts.Role.ADMIN) && user.verifiedAt() == null) {
            throw invalid("Email must be verified before approval or administrator assignment.");
        }
        if (role == Accounts.Role.ADMIN && status != Accounts.Status.APPROVED) {
            throw invalid("An administrator must be approved.");
        }
        if (user.admin() && (!enabled || role != Accounts.Role.ADMIN || status != Accounts.Status.APPROVED)) {
            requireAnotherAdmin();
        }
        db.update("update app_user set display_name = ?, role = ?, status = ?, enabled = ? where id = ?",
                name, role.name(), status.name(), enabled, id);
        audit(actor, "USER_UPDATED", id);
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void deleteUser(String actor, long id) {
        lockUsers();
        var user = accounts.lock(id);
        if (!db.queryForList("select slot from bootstrap_grant where user_id = ?", String.class, id).isEmpty()) {
            throw invalid("Initial administrators can be disabled, but their bootstrap record must be retained.");
        }
        if (user.admin()) requireAnotherAdmin();
        db.update("delete from external_identity where user_id = ?",id);
        db.update("delete from app_user where id = ?", id);
        audit(actor, "USER_DELETED", id);
    }

    private void lockUsers() { db.queryForList("select id from app_user order by id for update", Long.class); }

    private void requireAnotherAdmin() {
        var count = db.queryForObject("""
                select count(*) from app_user where enabled = true and role = 'ADMIN'
                and status = 'APPROVED' and email_verified_at is not null
                """, Long.class);
        if (count == null || count <= 1) throw invalid("Keep at least one active administrator.");
    }

    public List<Map<String, Object>> resources(Kind kind) {
        var rows = kind == Kind.ACTIVITY
                ? db.queryForList("select a.*,c.name as calendar_name from activity a join calendar c on c.id=a.calendar_id order by a.id")
                : kind == Kind.CALENDAR
                ? db.queryForList("select c.*, (select count(*) from activity a where a.calendar_id=c.id) as activity_count from calendar c order by c.id")
                : db.queryForList("select * from " + kind.table + " order by id");
        if (kind == Kind.ACTIVITY) rows.forEach(row -> {
            row.computeIfPresent("starts_at", (key, value) -> iso(value));
            row.computeIfPresent("ends_at", (key, value) -> iso(value));
        });
        return rows;
    }

    private String iso(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof OffsetDateTime offset) return offset.toInstant().toString();
        return value.toString();
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void saveResource(String actor, Kind kind, Long id, String name, String description,
                             Long parentId, String startsAt, String endsAt) {
        if (id != null) requireResource(kind, id);
        Long key;
        if (kind == Kind.ACTIVITY) {
            if (parentId == null) throw invalid("An activity needs a calendar.");
            requireResource(Kind.CALENDAR, parentId);
            OffsetDateTime start;
            OffsetDateTime end;
            try {
                start = OffsetDateTime.parse(startsAt);
                end = OffsetDateTime.parse(endsAt);
            } catch (RuntimeException ex) { throw invalid("Use ISO date/time with timezone, for example YYYY-MM-DDTHH:mm:ss+01:00."); }
            var status=id==null?PlanningService.Status.PLANNED:PlanningService.Status.valueOf(db.queryForObject("select status from activity where id=?",String.class,id));
            Long version=id==null?null:db.queryForObject("select version from activity where id=?",Long.class,id);
            var people=id==null?List.<Long>of():db.queryForList("select employee_id from activity_employee where activity_id=?",Long.class,id);
            try { planning.save(actor,id,new PlanningService.BookingInput(name,description,parentId,start.toInstant(),end.toInstant(),status,people,version,false)); }
            catch(PlanningService.Rejected ex) { throw new ResponseStatusException(ex.status,ex.getMessage()); }
            return;
        } else if (kind == Kind.CALENDAR) {
            if (parentId != null) requireResource(Kind.GROUP, parentId);
            if (id == null) {
                key = insert("insert into calendar(name, description, group_id) values (?, ?, ?)", name, description, parentId);
            } else {
                db.update("update calendar set name = ?, description = ?, group_id = ? where id = ?", name, description, parentId, id);
                key = id;
            }
        } else {
            if (id == null) {
                key = insert("insert into activity_group(name, description) values (?, ?)", name, description);
            } else {
                db.update("update activity_group set name = ?, description = ? where id = ?", name, description, id);
                key = id;
            }
        }
        audit(actor, kind.name() + (id == null ? "_CREATED" : "_UPDATED"), key);
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void deleteResource(String actor, Kind kind, long id) {
        requireResource(kind, id);
        if (kind == Kind.CALENDAR) {
            var count = db.queryForObject("select count(*) from activity where calendar_id = ?", Long.class, id);
            deleteCalendar(actor, id, count == null ? 0 : count);
            return;
        }
        db.update("delete from " + kind.table + " where id = ?", id);
        audit(actor, kind.name() + "_DELETED", id);
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void deleteCalendar(String actor, long id, long expectedActivityCount) {
        requireResource(Kind.CALENDAR, id);
        db.queryForObject("select id from calendar where id = ? for update", Long.class, id);
        var activityCount = db.queryForObject(
                "select count(*) from activity where calendar_id = ?", Long.class, id);
        if (activityCount == null || activityCount != expectedActivityCount) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Projektets antal aktiviteter har ändrats. Ladda om sidan och bekräfta borttagningen igen.");
        }
        db.update("delete from activity where calendar_id = ?", id);
        db.update("delete from calendar where id = ?", id);
        audit(actor, "CALENDAR_DELETED", id);
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void membership(String actor, long groupId, long userId, boolean add) {
        requireResource(Kind.GROUP, groupId);
        if (add && !accounts.lock(userId).approved()) throw invalid("Only approved, verified users can be added to groups.");
        if (add) db.update("insert into group_member(group_id, user_id) values (?, ?) on conflict do nothing", groupId, userId);
        else db.update("delete from group_member where group_id = ? and user_id = ?", groupId, userId);
        audit(actor, add ? "GROUP_MEMBER_ADDED" : "GROUP_MEMBER_REMOVED", groupId);
    }

    public List<Map<String, Object>> memberships() {
        return db.queryForList("select m.group_id, g.name as group_name, m.user_id, u.email from group_member m join activity_group g on g.id=m.group_id join app_user u on u.id = m.user_id order by g.name, u.email");
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void linkEmployee(String actor,long userId,Long employeeId) {
        var user=accounts.lock(userId);if(!user.approved())throw invalid("Only approved accounts can be linked to an employee.");
        db.update("update employee set user_id=null where user_id=?",userId);
        if(employeeId!=null){
            if(db.queryForList("select id from employee where id=? and active=true",Long.class,employeeId).isEmpty())throw invalid("Choose an active employee.");
            if(db.queryForList("select user_id from employee where id=? and user_id is not null and user_id<>?",Long.class,employeeId,userId).size()>0)throw invalid("That employee is already linked to another account.");
            db.update("update employee set user_id=? where id=?",userId,employeeId);
        }
        audit(actor,"EMPLOYEE_ACCOUNT_LINKED",employeeId==null?userId:employeeId);
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void saveProjectAccess(String actor,long userId,long calendarId,String level,boolean canMoveOwn) {
        if(!accounts.lock(userId).approved())throw invalid("Approve the account before granting project access.");
        requireResource(Kind.CALENDAR,calendarId);
        if(!List.of("NONE","VIEWER","WORKER","LEAD").contains(level))throw invalid("Choose a valid project permission.");
        if(level.equals("NONE"))db.update("delete from project_user_access where user_id=? and calendar_id=?",userId,calendarId);
        else db.update("insert into project_user_access(user_id,calendar_id,access_level,can_move_own) values (?,?,?,?) on conflict(user_id,calendar_id) do update set access_level=excluded.access_level,can_move_own=excluded.can_move_own",
                userId,calendarId,level,canMoveOwn&&level.equals("WORKER"));
        audit(actor,"PROJECT_ACCESS_UPDATED",calendarId);
    }

    public List<Map<String,Object>> projectAccess() {
        return db.queryForList("select p.user_id,u.display_name,u.email,p.calendar_id,c.name as project_name,p.access_level,p.can_move_own from project_user_access p join app_user u on u.id=p.user_id join calendar c on c.id=p.calendar_id order by c.name,u.email");
    }

    private void requireResource(Kind kind, long id) {
        if (db.queryForObject("select count(*) from " + kind.table + " where id = ?", Long.class, id) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
        }
    }

    private void audit(String actor, String action, long id) {
        db.update("insert into audit_event(actor, action, target_id) values (?, ?, ?)", actor, action, id);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void cleanExpired(String actor) {
        db.update("delete from magic_token where expires_at <= current_timestamp");
        db.update("delete from link_rate where expires_at <= current_timestamp");
        db.update("delete from spring_session where expiry_time <= ?", System.currentTimeMillis());
        audit(actor, "EXPIRED_RECORDS_CLEANED", 0);
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
