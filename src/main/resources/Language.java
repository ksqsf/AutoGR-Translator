import java.io.IOException;
import java.sql.SQLException;
import javax.sql.*;

public class Language {
    static final String FINAL_STRING = "final string";

    public static void main(String[] args) {
        dummy();
    }

    public static void dummy() {}

    public static void raise() throws IOException {
        throw new IOException();
    }

    public static void branch() {
        dummy();
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
        dummy();
    }

    public static void exceptionFinally() {
        String foo = "";
        try {
            try {
                foo = "inner try";
                if (foo.startsWith("inner")) {
                    raise();
                } else {
                    dummy();
                }
            } catch (SQLException se) {
                foo = "inner SQLException";
                raise();
            } catch (Exception e) {
                foo = "inner Exception";
            } finally {
                foo = "inner finally";
            }
        } catch (IOException ioException) {
            foo = "outer IOException";
        } catch (Exception exc) {
            foo = "outer Exception";
        } finally {
            foo = "outer finally";
        }
    }

    public static void exception() {
        String foo = "";
        try {
            try {
                foo = "inner try";
                if (foo.startsWith("inner")) {
                    raise();
                } else {
                    dummy();
                }
            } catch (SQLException se) {
                foo = "inner SQLException";
                raise();
            } catch (Exception e) {
                foo = "inner Exception";
            }
        } catch (IOException ioException) {
            foo = "outer IOException";
        } catch (Exception exc) {
            foo = "outer Exception";
        }
    }

    public static void whileLoop() {

    }

    public static void doWhileLoop() {

    }

    public static void forLoop() {

    }
}