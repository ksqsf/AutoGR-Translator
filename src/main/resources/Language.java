import java.io.IOException;
import java.sql.SQLException;
import javax.sql.*;

public class Language {
    static final String FINAL_STRING = "final string";

    public static void main(String[] args) {
        dummy();
    }

    public static void dummy() {}

    public static boolean dummyBool() {
        return true;
    }

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
        boolean a = true;
        boolean b = false;
        dummy();
        while(a = b) {
            System.out.println("Oh no");
            if (a != true) {
                continue;
            } else {
                break;
            }
        }
        dummy();
    }

    public static void doWhileLoop() {
        dummy();
        boolean a = true, b = false;
        String s;
        try {
            do {
                dummy();
                if (dummyBool()) {
                    throw new Exception("oh no");
                }
                dummy();
            } while (dummyBool());
        } catch (Exception e) {
            s = "log";
        } finally {
            s = "close";
        }
        dummy();
    }

    public static void forLoop() {
        dummy();
        for(int i = 1, j = 2; i < 100 || j > 1234; i++, j--) {
            dummy();
        }
        dummy();
    }
}