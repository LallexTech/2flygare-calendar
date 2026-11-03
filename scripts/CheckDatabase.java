import java.sql.DriverManager;
import java.sql.SQLException;

/** Read-only connectivity check. Supply the PostgreSQL JDBC driver on the classpath. */
class CheckDatabase {
    public static void main(String[] args) {
        String url = env("DATABASE_JDBC_URL", "DATABASE_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:") || !url.matches(".*[?&]sslmode=verify-full(?:&.*)?$")) {
            System.err.println("Set DATABASE_JDBC_URL to a JDBC PostgreSQL URL with sslmode=verify-full.");
            System.exit(1);
        }
        try (var connection = DriverManager.getConnection(url,
                env("DATABASE_USERNAME", "PGUSER"), env("DATABASE_PASSWORD", "PGPASSWORD"))) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("select 1")) {
                if (!result.next() || result.getInt(1) != 1) throw new SQLException("Query failed", "08001");
            }
            System.out.println("PostgreSQL TLS connection and read-only query succeeded.");
        } catch (SQLException ex) {
            System.err.println("Database check failed (SQLSTATE " + ex.getSQLState() + ").");
            System.exit(1);
        }
    }

    private static String env(String preferred, String fallback) {
        String value = System.getenv(preferred);
        return value == null || value.isBlank() ? System.getenv(fallback) : value;
    }
}
