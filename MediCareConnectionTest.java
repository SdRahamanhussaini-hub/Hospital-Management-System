import java.sql.Connection;
import java.sql.DatabaseMetaData;

public class MediCareConnectionTest {
    public static void main(String[] args) {
        try (Connection con = DBConnection.getConnection()) {
            DatabaseMetaData meta = con.getMetaData();

            System.out.println("Connection established!");
            System.out.println("Database: " + con.getCatalog());
            System.out.println("Driver: " + meta.getDriverName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}