package se.flygare;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("@access.approved(authentication)")
public class PlanningService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    public enum Status { PLANNED, IN_PROGRESS, BLOCKED, DONE, CANCELLED;
        boolean reservesTime() { return this != DONE && this != CANCELLED; }
    }
    public record Employee(long id, String name, Integer dailyMinutes, Long userId) { }
    public record Project(long id, String name, String color, String accessLevel, boolean canMoveOwn) { }
    public record Settings(int dailyMinutes, String workStart, String breakStart, int breakMinutes) { }
    public record DailyAllocation(long employeeId, LocalDate date, int minutes, LocalTime startTime, LocalTime endTime) { }
    public record Booking(long id, long calendarId, String project, String color, String name, String description,
                          Instant start, Instant end, Status status, long version, List<Long> employeeIds,
                          LocalDate startDate, LocalDate endDate, List<DailyAllocation> dailyAllocations, boolean sharedBlock) { }
    public record Slot(Instant start, Instant end) { }
    public record Capacity(long employeeId, long capacityMinutes, long scheduledMinutes, long availableMinutes,
                           long completedMinutes, boolean busyNow, List<Slot> freeSlots) { }
    public record Snapshot(List<Employee> employees, List<Project> projects, List<Booking> bookings,
                           List<Capacity> capacity, Settings settings, long employeeId, boolean admin) { }
    public record BookingInput(@NotBlank @Size(max=120) String name, @Size(max=2000) String description,
                               @NotNull @Positive Long calendarId, @NotNull Instant start, @NotNull Instant end,
                               @NotNull Status status, @NotNull @Size(max=50) List<@NotNull @Positive Long> employeeIds,
                               List<@NotNull DailyAllocation> dailyAllocations, Boolean sharedBlock, Long version, boolean allowConflict) {
        public BookingInput(String name,String description,Long calendarId,Instant start,Instant end,Status status,
                            List<Long> employeeIds,Long version,boolean allowConflict) {
            this(name,description,calendarId,start,end,status,employeeIds,null,null,version,allowConflict);
        }
    }
    public record EmployeeInput(@NotBlank @Size(max=120) String name, @Min(30) @Max(1440) Integer dailyMinutes) { }
    public record SettingsInput(@Min(30) @Max(1440) int dailyMinutes, @NotBlank String workStart,
                                @NotBlank String breakStart, @Min(0) @Max(180) int breakMinutes) { }
    public record ProjectInput(@NotBlank @Size(max=120) String name, @Pattern(regexp="#[0-9a-fA-F]{6}") String color) { }
    public record Conflict(long bookingId, String name, Instant start, Instant end) { }
    public static class Rejected extends RuntimeException {
        public final HttpStatus status; public final String code; public final List<Conflict> conflicts;
        Rejected(HttpStatus status, String code, String message, List<Conflict> conflicts) {
            super(message); this.status=status; this.code=code; this.conflicts=conflicts;
        }
    }
    private final JdbcTemplate db;
    private final Clock clock;
    private final Accounts accounts;
    public PlanningService(JdbcTemplate db, Clock clock, Accounts accounts) { this.db=db; this.clock=clock; this.accounts=accounts; }

    @Transactional(readOnly=true)
    public Snapshot snapshot(Instant start,Instant end,String actor) {
        if(!end.isAfter(start)||Duration.between(start,end).toDays()>93)throw bad("Välj en period på högst tre månader.");
        var account=accounts.byEmail(actor).orElseThrow();boolean isAdmin=account.admin();
        var people=employees();var prefs=settings();var calendars=projects(actor,isAdmin);
        var visible=calendars.stream().map(Project::id).collect(java.util.stream.Collectors.toSet());
        var tasks=bookings(start,end).stream().filter(booking->visible.contains(booking.calendarId())).toList();
        long employeeId=db.query("select id from employee where user_id=?",rs->rs.next()?rs.getLong(1):0L,account.id());
        return new Snapshot(people,calendars,tasks,people.stream().map(person->capacity(person,prefs,tasks,start,end)).toList(),prefs,employeeId,isAdmin);
    }
    public List<Employee> employees() {
        return db.query("select * from employee where active=true order by id",(rs,n)->new Employee(rs.getLong("id"),rs.getString("name"),
                (Integer)rs.getObject("daily_minutes"),(Long)rs.getObject("user_id")));
    }
    public List<Project> projects(String actor,boolean isAdmin) {
        if(isAdmin)return db.query("select id,name,color,'LEAD' as access_level,true as can_move_own from calendar order by id",
                (rs,n)->new Project(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5)));
        long userId=accounts.byEmail(actor).orElseThrow().id();
        return db.query("select c.id,c.name,c.color,p.access_level,p.can_move_own from calendar c join project_user_access p on p.calendar_id=c.id where p.user_id=? order by c.id",
                (rs,n)->new Project(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5)),userId);
    }    public Settings settings() {
        return db.queryForObject("select * from planning_settings where id=1",(rs,n)->new Settings(
                rs.getInt("daily_minutes"),rs.getString("work_start"),rs.getString("break_start"),rs.getInt("break_minutes")));
    }
    private List<Booking> bookings(Instant start, Instant end) {
        var taskSettings=settings();
        Map<Long,List<Long>> assignments=new HashMap<>();
        Map<Long,List<DailyAllocation>> daily=new HashMap<>();
        db.query("""
                select ae.activity_id,ae.employee_id from activity_employee ae join activity a on a.id=ae.activity_id
                where a.starts_at < ? and a.ends_at > ? order by ae.employee_id
                """,rs->{assignments.computeIfAbsent(rs.getLong(1),key->new ArrayList<>()).add(rs.getLong(2));},
                Timestamp.from(end),Timestamp.from(start));
        db.query("""
                select wd.activity_id,wd.employee_id,wd.work_date,wd.minutes,wd.start_time,wd.end_time from activity_work_day wd
                join activity a on a.id=wd.activity_id where a.starts_at < ? and a.ends_at > ?
                order by wd.activity_id,wd.work_date,wd.employee_id
                """,rs->{daily.computeIfAbsent(rs.getLong(1),key->new ArrayList<>()).add(
                        new DailyAllocation(rs.getLong(2),rs.getDate(3).toLocalDate(),rs.getInt(4),rs.getTime(5).toLocalTime(),rs.getTime(6).toLocalTime()));},
                Timestamp.from(end),Timestamp.from(start));
        return db.query("""
                select a.*,c.name as project_name,c.color from activity a join calendar c on c.id=a.calendar_id
                where a.starts_at < ? and a.ends_at > ? order by a.starts_at,a.id
                """,(rs,n)->new Booking(rs.getLong("id"),rs.getLong("calendar_id"),rs.getString("project_name"),
                rs.getString("color"),rs.getString("name"),rs.getString("description"),rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),Status.valueOf(rs.getString("status")),rs.getLong("version"),
                List.copyOf(assignments.getOrDefault(rs.getLong("id"),List.of())),
                rs.getTimestamp("starts_at").toInstant().atZone(ZONE).toLocalDate(),
                rs.getTimestamp("ends_at").toInstant().minusNanos(1).atZone(ZONE).toLocalDate(),
                allocationsFor(assignments.getOrDefault(rs.getLong("id"),List.of()),rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),daily.get(rs.getLong("id")),taskSettings),rs.getBoolean("shared_block")),Timestamp.from(end),Timestamp.from(start));
    }

    private List<DailyAllocation> allocationsFor(List<Long> people,Instant start,Instant end,List<DailyAllocation> stored,Settings settings) {
        if(stored!=null) return List.copyOf(stored);
        var rows=new ArrayList<DailyAllocation>();var first=start.atZone(ZONE).toLocalDate();
        var last=end.minusNanos(1).atZone(ZONE).toLocalDate();var pauseTime=LocalTime.parse(settings.breakStart());
        for(var employee:people) for(var day=first;!day.isAfter(last);day=day.plusDays(1)) {
            var dayStart=day.atStartOfDay(ZONE).toInstant();var dayEnd=day.plusDays(1).atStartOfDay(ZONE).toInstant();
            var pause=day.atTime(pauseTime).atZone(ZONE).toInstant();var pauseEnd=pause.plusSeconds(settings.breakMinutes()*60L);
            long minutes=minutes(max(max(start,dayStart),min(end,pause)),min(min(end,dayEnd),pause));
            minutes+=minutes(max(max(start,dayStart),pauseEnd),min(min(end,dayEnd),dayEnd));
            var activityStart=start.atZone(ZONE);var activityEnd=end.atZone(ZONE);
            var dayStartTime=day.equals(first)&&!activityStart.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ?activityStart.toLocalTime():LocalTime.parse(settings.workStart());
            var dayEndTime=day.equals(last)&&!activityEnd.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ?activityEnd.toLocalTime():dayStartTime.plusMinutes(minutes+(dayStartTime.isBefore(pauseTime)&&dayStartTime.plusMinutes(minutes).isAfter(pauseTime)?settings.breakMinutes():0));
            rows.add(new DailyAllocation(employee,day,(int)minutes,dayStartTime,dayEndTime));
        }
        return List.copyOf(rows);
    }

    @PreAuthorize("@access.approved(authentication)")
    @Transactional
    public long save(String actor,Long id,BookingInput input) {
        var first=input.start().atZone(ZONE).toLocalDate();var last=input.end().minusNanos(1).atZone(ZONE).toLocalDate();
        if(!input.end().isAfter(input.start())||last.isBefore(first)||last.toEpochDay()-first.toEpochDay()>92)throw bad("Välj ett aktivitetsintervall på 1 till 93 dagar.");
        if(db.queryForObject("select count(*) from calendar where id=?",Integer.class,input.calendarId())!=1)throw bad("Välj ett giltigt projekt.");
        var people=input.employeeIds().stream().distinct().sorted().toList();var locks=new TreeSet<>(people);var workSettings=settings();
        if(id!=null)locks.addAll(db.queryForList("select employee_id from activity_employee where activity_id=?",Long.class,id));
        for(var employee:locks)if(db.queryForList("select id from employee where id=? and active=true for update",Long.class,employee).isEmpty())throw bad("Den valda medarbetaren finns inte.");
        if(id!=null){var versions=db.queryForList("select version from activity where id=? for update",Long.class,id);if(versions.isEmpty())throw new Rejected(HttpStatus.NOT_FOUND,"NOT_FOUND","Aktiviteten finns inte längre.",List.of());if(input.version()==null||!versions.getFirst().equals(input.version()))throw stale();}
        List<DailyAllocation> allocations=input.dailyAllocations();
        if(allocations==null){allocations=id==null?List.of():db.query("select employee_id,work_date,minutes,start_time,end_time from activity_work_day where activity_id=? order by work_date,employee_id",
                (rs,n)->new DailyAllocation(rs.getLong(1),rs.getDate(2).toLocalDate(),rs.getInt(3),rs.getTime(4).toLocalTime(),rs.getTime(5).toLocalTime()),id);
            if(id!=null&&allocations.isEmpty())allocations=allocationsFor(people,input.start(),input.end(),null,settings());}
        long expected=(long)people.size()*(last.toEpochDay()-first.toEpochDay()+1);
        if(allocations.size()!=expected||allocations.stream().map(row->row.employeeId()+"/"+row.date()).distinct().count()!=expected
                ||allocations.stream().anyMatch(row->!people.contains(row.employeeId())||row.date().isBefore(first)||row.date().isAfter(last)||row.minutes()<0||row.minutes()>1440
                ||row.startTime()==null||row.endTime()==null
                ||row.minutes()>0&&(!row.endTime().isAfter(row.startTime())||row.minutes()!=scheduledMinutes(row.startTime(),row.endTime(),row.date(),workSettings))))throw bad("Ange giltiga timmar samt start- och sluttider för varje vald person och dag.");
        boolean shared=input.sharedBlock()!=null?input.sharedBlock():id!=null&&db.queryForObject("select shared_block from activity where id=?",Boolean.class,id);
        if(!shared&&people.size()>1)throw bad("En separat aktivitet kan bara ha en person.");
        if(id!=null)authorizeBooking(actor,id,input,people,allocations);
        else if(!isAdmin(actor)&&projectLevel(actor,input.calendarId())!=ProjectLevel.LEAD)throw forbidden();
        var conflicts=new ArrayList<Conflict>();
        if(input.status().reservesTime())for(var row:allocations)if(row.minutes()>0){
            int dailyCapacity=db.queryForObject("select coalesce(daily_minutes,?) from employee where id=?",Integer.class,workSettings.dailyMinutes(),row.employeeId());
            int existing=db.queryForObject("select coalesce(sum(w.minutes),0) from activity_work_day w join activity a on a.id=w.activity_id where w.employee_id=? and w.work_date=? and w.activity_id<>? and a.status in ('PLANNED','IN_PROGRESS','BLOCKED')",Integer.class,row.employeeId(),row.date(),id==null?-1:id);
            var overlaps=db.query("select a.id,a.name from activity_work_day w join activity a on a.id=w.activity_id where w.employee_id=? and w.work_date=? and w.activity_id<>? and w.start_time < ? and w.end_time > ? and a.status in ('PLANNED','IN_PROGRESS','BLOCKED') order by a.id limit 1",
                    (rs,n)->new Conflict(rs.getLong(1),rs.getString(2),row.date().atStartOfDay(ZONE).toInstant(),row.date().plusDays(1).atStartOfDay(ZONE).toInstant()),row.employeeId(),row.date(),id==null?-1:id,row.endTime(),row.startTime());
            if(!overlaps.isEmpty()||existing+row.minutes()>dailyCapacity){
                if(!overlaps.isEmpty())conflicts.addAll(overlaps);else {var prior=db.query("select a.id,a.name from activity_work_day w join activity a on a.id=w.activity_id where w.employee_id=? and w.work_date=? and w.activity_id<>? and w.minutes>0 and a.status in ('PLANNED','IN_PROGRESS','BLOCKED') order by a.id limit 1",(rs,n)->new Conflict(rs.getLong(1),rs.getString(2),row.date().atStartOfDay(ZONE).toInstant(),row.date().plusDays(1).atStartOfDay(ZONE).toInstant()),row.employeeId(),row.date(),id==null?-1:id);conflicts.addAll(prior);}
            }
        }
        if(!conflicts.isEmpty()&&!input.allowConflict())throw new Rejected(HttpStatus.CONFLICT,"STAFF_CONFLICT","Arbetstiden krockar med en annan bokning eller överstiger dagskapaciteten. Vill du spara ändå?",conflicts.stream().distinct().toList());
        if(id==null)id=insert("insert into activity(name,description,calendar_id,starts_at,ends_at,status,shared_block) values (?,?,?,?,?,?,?)",input.name().strip(),Objects.requireNonNullElse(input.description(),""),input.calendarId(),Timestamp.from(input.start()),Timestamp.from(input.end()),input.status().name(),shared);
        else db.update("update activity set name=?,description=?,calendar_id=?,starts_at=?,ends_at=?,status=?,shared_block=?,version=version+1 where id=?",input.name().strip(),Objects.requireNonNullElse(input.description(),""),input.calendarId(),Timestamp.from(input.start()),Timestamp.from(input.end()),input.status().name(),shared,id);
        db.update("delete from activity_employee where activity_id=?",id);for(var employee:people)db.update("insert into activity_employee(activity_id,employee_id) values (?,?)",id,employee);
        db.update("delete from activity_work_day where activity_id=?",id);for(var row:allocations)db.update("insert into activity_work_day(activity_id,employee_id,work_date,minutes,start_time,end_time) values (?,?,?,?,?,?)",id,row.employeeId(),row.date(),row.minutes(),row.startTime(),row.endTime());
        audit(actor,conflicts.isEmpty()?"BOOKING_SAVED":"BOOKING_CONFLICT_OVERRIDE",id);return id;
    }
    @Transactional
    public List<Long> saveBatch(String actor,List<BookingInput> inputs) {
        if(inputs==null||inputs.isEmpty()||inputs.size()>50)throw bad("Välj mellan 1 och 50 medarbetare.");
        var ids=new ArrayList<Long>();for(var input:inputs){if(input.sharedBlock()!=Boolean.FALSE||input.employeeIds().size()!=1)throw bad("Varje separat aktivitet måste ha exakt en person.");ids.add(save(actor,null,input));}return List.copyOf(ids);
    }
    private enum ProjectLevel { NONE, VIEWER, WORKER, LEAD }
    private ProjectLevel projectLevel(String actor,long projectId) {
        long userId=accounts.byEmail(actor).orElseThrow().id();
        var level=db.query("select access_level from project_user_access where user_id=? and calendar_id=?",
                rs->rs.next()?ProjectLevel.valueOf(rs.getString(1)):ProjectLevel.NONE,userId,projectId);
        return level==null?ProjectLevel.NONE:level;
    }
    private boolean moveOwn(String actor,long projectId) {
        long userId=accounts.byEmail(actor).orElseThrow().id();
        Boolean allowed=db.query("select can_move_own from project_user_access where user_id=? and calendar_id=?",
                rs->rs.next()?rs.getBoolean(1):false,userId,projectId);return Boolean.TRUE.equals(allowed);
    }
    private boolean isAdmin(String actor){return accounts.byEmail(actor).map(Accounts.Account::admin).orElse(false);}
    private RuntimeException forbidden(){return new Rejected(HttpStatus.FORBIDDEN,"FORBIDDEN","Du saknar behörighet att ändra aktiviteten i projektet.",List.of());}
    private void authorizeBooking(String actor,long id,BookingInput input,List<Long> people,List<DailyAllocation> allocations) {
        if(isAdmin(actor)||projectLevel(actor,input.calendarId())==ProjectLevel.LEAD)return;
        if(projectLevel(actor,input.calendarId())!=ProjectLevel.WORKER)throw forbidden();
        var account=accounts.byEmail(actor).orElseThrow();var own=db.query("select id from employee where user_id=?",rs->rs.next()?rs.getLong(1):0L,account.id());
        var saved=db.query("select calendar_id,name,description,status,starts_at,ends_at,shared_block from activity where id=?",rs->{if(!rs.next())return null;return new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant(),rs.getBoolean(7)};},id);
        if(saved==null||Boolean.TRUE.equals(saved[6])||own==0||db.queryForList("select 1 from activity_employee where activity_id=? and employee_id=?",Integer.class,id,own).isEmpty())throw forbidden();
        long oldProject=(long)saved[0];if(oldProject!=input.calendarId()||Boolean.TRUE.equals(saved[6])&&!Objects.equals(saved[1],input.name())||!people.equals(db.queryForList("select employee_id from activity_employee where activity_id=? order by employee_id",Long.class,id))||!Objects.equals(saved[6],input.sharedBlock()))throw forbidden();
        Map<String,DailyAllocation> oldRows=new HashMap<>();db.query("select employee_id,work_date,minutes,start_time,end_time from activity_work_day where activity_id=?",rs->{var row=new DailyAllocation(rs.getLong(1),rs.getDate(2).toLocalDate(),rs.getInt(3),rs.getTime(4).toLocalTime(),rs.getTime(5).toLocalTime());oldRows.put(row.employeeId()+"/"+row.date(),row);},id);
        Map<String,DailyAllocation> newRows=new HashMap<>();for(var row:allocations)newRows.put(row.employeeId()+"/"+row.date(),row);
        boolean moved=!saved[4].equals(input.start())||!saved[5].equals(input.end());
        boolean scheduleChanged=oldRows.entrySet().stream().anyMatch(entry->entry.getValue().employeeId()==own&&!Objects.equals(entry.getValue(),newRows.get(entry.getKey())))||newRows.entrySet().stream().anyMatch(entry->entry.getValue().employeeId()==own&&!Objects.equals(entry.getValue(),oldRows.get(entry.getKey())));
        if(((moved||scheduleChanged)&&!moveOwn(actor,input.calendarId()))||(moved&&Boolean.TRUE.equals(saved[6])&&people.size()>1))throw forbidden();
        for(var row:allocations)if(row.employeeId()!=own){var prior=oldRows.get(row.employeeId()+"/"+row.date());if(!Objects.equals(prior,row))throw forbidden();}
    }
    @PreAuthorize("@access.approved(authentication)")
    @Transactional
    public void delete(String actor,long id,long version) {
        Long project=db.query("select calendar_id from activity where id=?",rs->rs.next()?rs.getLong(1):null,id);
        if(project==null)throw new Rejected(HttpStatus.NOT_FOUND,"NOT_FOUND","Aktiviteten finns inte längre.",List.of());
        if(!isAdmin(actor)&&projectLevel(actor,project)!=ProjectLevel.LEAD)throw forbidden();
        if(db.update("delete from activity where id=? and version=?",id,version)!=1) throw stale();
        audit(actor,"BOOKING_DELETED",id);
    }
    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public long saveEmployee(String actor,Long id,EmployeeInput input) {
        var settings=settings();
        if(input.dailyMinutes()!=null && LocalTime.parse(settings.workStart()).toSecondOfDay()/60+input.dailyMinutes()+settings.breakMinutes()>1440)
            throw bad("Arbetsdagen måste rymmas inom samma dag.");
        if(id==null) id=insert("insert into employee(name,daily_minutes) values (?,?)",input.name().strip(),input.dailyMinutes());
        else if(db.update("update employee set name=?,daily_minutes=? where id=?",input.name().strip(),input.dailyMinutes(),id)!=1) throw bad("Personen finns inte.");
        audit(actor,"EMPLOYEE_SAVED",id); return id;
    }
    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public void saveSettings(String actor,SettingsInput input) {
        try {
            var start=LocalTime.parse(input.workStart()); var pause=LocalTime.parse(input.breakStart());
            if(pause.isBefore(start) || start.toSecondOfDay()/60+input.dailyMinutes()+input.breakMinutes()>1440) throw bad("Arbetsdagen måste rymmas inom samma dag.");
            for(var employee:employees()) if(employee.dailyMinutes()!=null && start.toSecondOfDay()/60+employee.dailyMinutes()+input.breakMinutes()>1440)
                throw bad("En kollegas individuella arbetsdag ryms inte inom samma dag.");
        } catch(java.time.format.DateTimeParseException ex) { throw bad("Ange tider som HH:mm."); }
        db.update("update planning_settings set daily_minutes=?,work_start=?,break_start=?,break_minutes=? where id=1",
                input.dailyMinutes(),input.workStart(),input.breakStart(),input.breakMinutes());
        audit(actor,"WORKDAY_SETTINGS_SAVED",1);
    }
    @PreAuthorize("@access.admin(authentication)")
    @Transactional
    public long createProject(String actor,ProjectInput input) {
        long id=insert("insert into calendar(name,color) values (?,?)",input.name().strip(),Objects.requireNonNullElse(input.color(),"#187a58"));
        audit(actor,"CALENDAR_CREATED",id); return id;
    }

    private Capacity capacity(Employee employee,Settings settings,List<Booking> all,Instant from,Instant to) {
        int daily=employee.dailyMinutes()==null?settings.dailyMinutes():employee.dailyMinutes();
        var mine=all.stream().filter(b->b.employeeIds().contains(employee.id())).toList();
        long capacity=0,scheduled=0,completed=0;var free=new ArrayList<Slot>();var now=clock.instant();
        var first=from.atZone(ZONE).toLocalDate();var last=to.minusNanos(1).atZone(ZONE).toLocalDate();
        var today=now.atZone(ZONE).toLocalDate();var breakStart=LocalTime.parse(settings.breakStart());
        for(var date=first;!date.isAfter(last);date=date.plusDays(1)) {
            boolean workingDay=date.getDayOfWeek().getValue()<=5;long planned=0,done=0;
            var occupied=new ArrayList<Slot>();
            for(var booking:mine) if(booking.status()!=Status.CANCELLED) for(var row:booking.dailyAllocations()) if(row.employeeId()==employee.id()&&row.date().equals(date)) {
                planned+=row.minutes();if(booking.status()==Status.DONE)done+=row.minutes();
                if(booking.status().reservesTime()&&row.minutes()>0) {
                    var start=date.atTime(row.startTime()).atZone(ZONE).toInstant();var end=date.atTime(row.endTime()).atZone(ZONE).toInstant();
                    var pauseStart=date.atTime(breakStart).atZone(ZONE).toInstant();var pauseEnd=pauseStart.plusSeconds(settings.breakMinutes()*60L);
                    if(start.isBefore(pauseStart))occupied.add(new Slot(start,min(end,pauseStart)));
                    if(end.isAfter(pauseEnd))occupied.add(new Slot(max(start,pauseEnd),end));
                }
            }
            if(workingDay)capacity+=daily;scheduled+=planned;completed+=done;if(!workingDay)continue;
            var workStart=date.atTime(LocalTime.parse(settings.workStart())).atZone(ZONE).toInstant();
            var workEnd=workStart.plusSeconds((daily+(daily>Duration.between(LocalTime.parse(settings.workStart()),breakStart).toMinutes()?settings.breakMinutes():0))*60L);
            var pauseStart=date.atTime(breakStart).atZone(ZONE).toInstant();var pauseEnd=pauseStart.plusSeconds(settings.breakMinutes()*60L);
            var working=new ArrayList<Slot>();if(workStart.isBefore(pauseStart))working.add(new Slot(workStart,min(workEnd,pauseStart)));if(workEnd.isAfter(pauseEnd))working.add(new Slot(max(workStart,pauseEnd),workEnd));
            occupied.sort(Comparator.comparing(Slot::start));
            if(!date.isBefore(today))for(var window:working){var cursor=max(window.start(),now);if(!cursor.isBefore(window.start()))cursor=window.start();
                for(var busy:occupied){if(!busy.end().isAfter(cursor)||!busy.start().isBefore(window.end()))continue;var busyStart=max(busy.start(),window.start());var busyEnd=min(busy.end(),window.end());if(busyStart.isAfter(cursor)){var slotStart=max(cursor,now);if(busyStart.isAfter(slotStart))free.add(new Slot(slotStart,busyStart));}if(busyEnd.isAfter(cursor))cursor=busyEnd;}
                var slotStart=max(cursor,now);if(window.end().isAfter(slotStart))free.add(new Slot(slotStart,window.end()));}
        }
        long available=free.stream().mapToLong(slot->minutes(slot.start(),slot.end())).sum();
        boolean busy=mine.stream().filter(b->b.status().reservesTime()).flatMap(b->b.dailyAllocations().stream())
                .filter(row->row.employeeId()==employee.id()&&row.date().equals(today)&&row.minutes()>0)
                .anyMatch(row->{var begin=today.atTime(row.startTime()).atZone(ZONE).toInstant();var end=today.atTime(row.endTime()).atZone(ZONE).toInstant();return !now.isBefore(begin)&&now.isBefore(end);});
        return new Capacity(employee.id(),capacity,scheduled,available,completed,busy,List.copyOf(free));
    }    private static int scheduledMinutes(LocalTime start,LocalTime end,LocalDate date,Settings settings) {
        long elapsed=Duration.between(start,end).toMinutes();var pause=LocalTime.parse(settings.breakStart());var pauseEnd=pause.plusMinutes(settings.breakMinutes());
        var overlapStart=start.isAfter(pause)?start:pause;var overlapEnd=end.isBefore(pauseEnd)?end:pauseEnd;
        long overlap=overlapEnd.isAfter(overlapStart)?Duration.between(overlapStart,overlapEnd).toMinutes():0;
        return (int)Math.max(0,elapsed-overlap);
    }
    private static Instant max(Instant a,Instant b) { return a.isAfter(b)?a:b; }
    private static Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
    private static long minutes(Instant a,Instant b) { return b.isAfter(a)?Duration.between(a,b).toMinutes():0; }
    private Rejected bad(String message) { return new Rejected(HttpStatus.BAD_REQUEST,"INVALID_BOOKING",message,List.of()); }
    private Rejected stale() { return new Rejected(HttpStatus.CONFLICT,"STALE_BOOKING","Någon har ändrat bokningen. Vyn har uppdaterats; försök igen.",List.of()); }
    private void audit(String actor,String action,long id) { db.update("insert into audit_event(actor,action,target_id) values (?,?,?)",actor,action,id); }
    private long insert(String sql,Object... values) {
        var keys=new GeneratedKeyHolder();
        db.update(connection->{var statement=connection.prepareStatement(sql,new String[]{"id"});
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]); return statement;},keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
