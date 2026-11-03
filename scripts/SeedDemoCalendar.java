import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit, transactional demo-data import. Never creates login accounts or roles. */
class SeedDemoCalendar {
    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    private record Project(String name, String town, List<String> people, List<String> tasks) { }
    private record Booking(int project, String name, String description, LocalDate date, LocalTime start, LocalTime end,
                           List<String> people) {
        Instant startsAt() { return date.atTime(start).atZone(ZONE).toInstant(); }
        Instant endsAt() { return date.atTime(end).atZone(ZONE).toInstant(); }
    }
    private static final List<Project> PROJECTS = List.of(
        new Project("Villa Björken – köksrenovering", "Göteborg", List.of("Tony", "Tobias"), List.of(
            "Skyddstäckning och demontering", "Regling och förberedelser", "Montering av köksstommar", "Passbitar och socklar",
            "Bänkskivor och smygar", "Listning och justering", "Kompletteringar och egenkontroll", "Överlämning och städning")),
        new Project("BRF Eken – altanbygge", "Mölndal", List.of("Jesper", "Magnus"), List.of(
            "Uppmätning och materialmottagning", "Grundarbete och plintar", "Bärlinor och stolpar", "Bjälklag och reglar",
            "Montering av trall", "Räcken och handledare", "Trappa och avslutningar", "Slutkontroll och städning")),
        new Project("Kontor Hamnen – kontorsanpassning", "Göteborg", List.of("Anders"), List.of(
            "Demontering av mellanväggar", "Regling av nya väggar", "Skivmontage", "Montering av innerdörrar",
            "Akustikpanel", "Dörrjustering och beslag", "Lister och fast inredning", "Kundavstämning och städning")),
        new Project("Villa Solsidan – tillbyggnad", "Kungsbacka", List.of("Tony", "Jesper"), List.of(
            "Utsättning och materialmottagning", "Bärverk och syllar", "Ytterväggsstomme", "Takstomme",
            "Vindskydd och tätning", "Invändig regling", "Invändigt skivmaterial", "Egenkontroll och projektavstämning")),
        new Project("Radhus Ängen – fasadbyte", "Partille", List.of("Tobias", "Anders"), List.of(
            "Etablering och väderskydd", "Demontering av fasadpanel", "Vindduk och läkt", "Montering av ny panel",
            "Foder och knutbrädor", "Komplettering av panel", "Finjustering av detaljer", "Slutkontroll och överlämning")),
        new Project("Förskolan Gläntan – invändigt snickeri", "Lerum", List.of("Magnus"), List.of(
            "Skyddstäckning och materialförberedelser", "Tillverkning av förvaringsbänkar", "Montering av skåp",
            "Dörrfoder och lister", "Akustikpanel och väggdetaljer", "Montering av hyllor", "Mindre justeringar", "Städning och överlämning"))
    );

    private static List<Booking> bookings() {
        var all = new ArrayList<Booking>();
        int[][] dates = {{2, 3, 4, 5, 6, 16, 18, 20}, {9, 10, 11, 12, 13, 17, 19, 20}};
        for (int p = 0; p < PROJECTS.size(); p++) {
            var project = PROJECTS.get(p);
            for (int step = 0; step < 8; step++) {
                var date = LocalDate.of(2026, 11, dates[p < 3 ? 0 : 1][step]);
                boolean last = date.getDayOfMonth() == 20;
                var start = LocalTime.of(last && p >= 3 ? 13 : 7, 0);
                var end = LocalTime.of(last && p < 3 ? 12 : 16, 0);
                if(!last && p==2 && step%2==1) end=LocalTime.of(12,0);
                if(!last && p==5 && step%2==0) start=LocalTime.of(13,0);
                var name = String.join(" & ", project.people()) + ": " + project.tasks().get(step);
                var description = "Demobokning – november 2026. Fiktivt uppdrag.\nProjekt: " + project.name()
                        + "\nPersonal: " + String.join(", ", project.people()) + "\nOrt: " + project.town()
                        + "\nArbete: " + project.tasks().get(step)
                        + (last ? "" : "\nLunchrast: 12:00–13:00.");
                all.add(new Booking(p, name, description, date, start, end, project.people()));
            }
        }
        all.sort(Comparator.comparing(Booking::startsAt).thenComparingInt(Booking::project));
        return List.copyOf(all);
    }

    private static void validate(List<Booking> all) {
        if (all.size() != 48) throw new IllegalStateException("Expected 48 bookings");
        var dates = new HashSet<LocalDate>();
        var workers = new HashSet<String>();
        for (var booking : all) {
            if (booking.date().isBefore(LocalDate.of(2026, 11, 2)) || booking.date().isAfter(LocalDate.of(2026, 11, 22))
                    || booking.date().getDayOfWeek().getValue() > 5 || !booking.end().isAfter(booking.start())
                    || booking.name().length() > 120) throw new IllegalStateException("Invalid demo booking");
            dates.add(booking.date());
            workers.addAll(booking.people());
            for (var other : all) {
                if (booking == other || !booking.date().equals(other.date())) continue;
                if (booking.start().isBefore(other.end()) && booking.end().isAfter(other.start())
                        && booking.people().stream().anyMatch(other.people()::contains)) {
                    throw new IllegalStateException("An employee is double-booked");
                }
            }
        }
        if (dates.size() != 15 || workers.size() != 5) throw new IllegalStateException("Unexpected staff/date coverage");
    }

    public static void main(String[] args) throws Exception {
        var all = bookings();
        validate(all);
        if (Arrays.asList(args).contains("--check")) {
            System.out.println("Validated 48 bookings, 6 projects, 5 employees, 15 weekdays; no employee overlaps.");
            return;
        }
        var url = System.getenv("DATABASE_JDBC_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Set DATABASE_JDBC_URL");
        int calendars = 0, activities = 0;
        try (var connection = DriverManager.getConnection(url, env("DATABASE_USERNAME", "PGUSER"), env("DATABASE_PASSWORD", "PGPASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, 2026110248L);
                    lock.execute();
                }
                var ids = new ArrayList<Long>();
                var employees = new HashMap<String,Long>();
                for(var name:List.of("Tony","Tobias","Jesper","Magnus","Anders")) {
                    Long employee=find(connection,"select id from employee where name=?",name);
                    if(employee==null) employee=insert(connection,"insert into employee(name) values (?)",name);
                    employees.put(name,employee);
                }
                var colors=List.of("#187a58","#326cab","#9e6624","#7451a3","#b34c57","#277f8c");
                for (var project : PROJECTS) {
                    var description = "Demoprojekt – november 2026. Fiktivt bygguppdrag i " + project.town() + ".";
                    Long id = find(connection, "select id from calendar where name = ? and description = ?", project.name(), description);
                    if (id == null) {
                        id = insert(connection, "insert into calendar(name, description) values (?, ?)", project.name(), description);
                        audit(connection, "DEMO_CALENDAR_CREATED", id);
                        calendars++;
                    }
                    ids.add(id);
                    try(var statement=connection.prepareStatement("update calendar set color=? where id=? and color='#187a58'")) {
                        statement.setString(1,colors.get(ids.size()-1));statement.setLong(2,id);statement.executeUpdate();
                    }
                }
                for (var booking : all) {
                    long calendar = ids.get(booking.project());
                    var start = Timestamp.from(booking.startsAt());
                    var end = Timestamp.from(booking.endsAt());
                    Long id=find(connection, "select id from activity where calendar_id = ? and name = ? and description = ? and starts_at = ? and ends_at = ?",
                            calendar, booking.name(), booking.description(), start, end);
                    if(id==null) {
                        // Upgrade only untouched rows from the original full-day demo import.
                        id=find(connection,"select id from activity where calendar_id=? and name=? and description=? and starts_at=? and ends_at=?",
                            calendar,booking.name(),booking.description(),Timestamp.from(booking.date().atTime(7,0).atZone(ZONE).toInstant()),
                            Timestamp.from(booking.date().atTime(16,0).atZone(ZONE).toInstant()));
                        if(id!=null) try(var statement=connection.prepareStatement("update activity set starts_at=?,ends_at=? where id=? and version=0")) {
                            statement.setTimestamp(1,start);statement.setTimestamp(2,end);statement.setLong(3,id);statement.executeUpdate();
                        }
                    }
                    if(id==null) {
                    id = insert(connection, "insert into activity(calendar_id, name, description, starts_at, ends_at) values (?, ?, ?, ?, ?)",
                            calendar, booking.name(), booking.description(), start, end);
                    audit(connection, "DEMO_ACTIVITY_CREATED", id);
                    activities++;
                    }
                    for(var name:booking.people()) try(var statement=connection.prepareStatement("insert into activity_employee(activity_id,employee_id) values (?,?) on conflict do nothing")) {
                        statement.setLong(1,id);statement.setLong(2,employees.get(name));statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception ex) { connection.rollback(); throw ex; }
        } catch (SQLException ex) {
            System.err.println("Demo import failed and was rolled back (SQLSTATE " + ex.getSQLState() + ").");
            System.exit(1);
        }
        System.out.println("Created " + calendars + " project calendars and " + activities + " activities. Existing records retained.");
    }

    private static String env(String primary, String fallback) {
        var value = System.getenv(primary);
        return value == null ? System.getenv(fallback) : value;
    }
    private static Long find(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (var result = statement.executeQuery()) { return result.next() ? result.getLong(1) : null; }
        }
    }
    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql, new String[]{"id"})) {
            bind(statement, values);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Missing generated key"); return keys.getLong(1); }
        }
    }
    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }
    private static void audit(Connection connection, String action, long id) throws SQLException {
        try (var statement = connection.prepareStatement("insert into audit_event(actor, action, target_id) values ('DEMO_SEED', ?, ?)")) {
            statement.setString(1, action); statement.setLong(2, id); statement.executeUpdate();
        }
    }
}
