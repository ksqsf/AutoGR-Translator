//STEP 1. Import required packages

import java.io.IOException;
import java.sql.*;

public class SimpleJDBC {
    // JDBC driver name and database URL
    static final String JDBC_DRIVER = "com.mysql.jdbc.Driver";
    static final String DB_URL = "jdbc:mysql://localhost/EMP";

    //  Database credentials
    static final String USER = "username";
    static final String PASS = "password";

    public static void raise() throws IOException {
        throw new IOException();
    }

    public static void main(String[] args) {
        int i=0,a=0,b=0,c=0,d=0,e=0;
        if (i < 3) {
            if (i > 5) {
                a = 4;
            } else {
                b = 5;
                return;
            }
            c = 6;
        } else {
            d = 7;
        }
        e = 9;
        try {
            try {
                if (a == 1) {
                    throw new Exception("abc");
                } else if (a == 100) {
                    throw new Exception("def");
                } else if (a == 1000) {
                    throw new IOException("bad");
                }
                a = 2;
            } catch (IOException ex) {
                a = 1000000;
            } catch (Exception ex) {
                a = 3;
                raise();
            } finally {
                a = 4;
            }
        } catch (Exception ex) {
            a = 321;
        }

        callJDBC();
    }//end main
////
    public static void callJDBC() {
        Connection conn = null;
        Statement stmt = null;

        for(int i = 0; i < 10; i++) {
            if (i > 3)
                break;
            System.out.println(i);
        }
        try{
            //STEP 2: Register JDBC driver
            Class.forName("com.mysql.jdbc.Driver");

            //STEP 3: Open a connection
            System.out.println("Connecting to database...");
            conn = DriverManager.getConnection(DB_URL,USER,PASS);

            //STEP 4: Execute a query
            System.out.println("Creating statement...");
            stmt = conn.createStatement();
            stmt.executeUpdate("INSERT INTO Employees VALUES (0, \"john\", \"doe\", 30)");
            String sql;
            sql = "SELECT id, first, last, age FROM Employees";
            ResultSet rs = stmt.executeQuery(sql);

            //STEP 5: Extract data from result set
            while(rs.next()){
                //Retrieve by column name
                int id  = rs.getInt("id");
                int age = rs.getInt("age");
                String first = rs.getString("first");
                String last = rs.getString("last");

                //Display values
                System.out.print("ID: " + id);
                System.out.print(", Age: " + age);
                System.out.print(", First: " + first);
                System.out.println(", Last: " + last);
            }
            //STEP 6: Clean-up environment
            rs.close();
            stmt.close();
            conn.close();
        }catch(SQLException se){
            //Handle errors for JDBC
            se.printStackTrace();
        }catch(Exception e){
            //Handle errors for Class.forName
            e.printStackTrace();
        }finally{
            //finally block used to close resources
            try{
                if(stmt!=null)
                    stmt.close();
            }catch(SQLException se2){
            }// nothing we can do
            try{
                if(conn!=null)
                    conn.close();
            }catch(SQLException se){
                se.printStackTrace();
            }//end finally try
        }//end try
        System.out.println("Goodbye!");
    }
}//end FirstExample
