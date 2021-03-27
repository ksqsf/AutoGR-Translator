import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.time.LocalDate;

public class JavaFX {
    @FXML DatePicker datePicker = new DatePicker();

    void foo() {
        LocalDate date = datePicker.getValue();
    }
}
