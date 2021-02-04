import java.lang.reflect.Array;
import java.util.ArrayList;

public class OOP {
    public static void main(String[] args) {
        Base obj1 = new Derived();
        obj1.work();
        Base obj2 = new DerivedNoOverride();
        obj2.work();

        Base obj3 = new DerivedDerived();
        obj3.work();

        Derived d = new DerivedDerived();
        d.work2(new String[]{"a", "b"});

        IWhatever iface = new Whatever();
        iface.bar();
        iface.foo();
    }
}

class Base {
    public void work() {
        System.out.println("Base");
    }
}

class Derived extends Base {
    public void work() {
        System.out.println("Derived");
    }
    public void work2(String[] args) {
        System.out.println(args);
    }
}

class DerivedNoOverride extends Base {

}

class DerivedDerived extends Derived {
    public void work() {
        super.work();
    }
}

interface IWhatever {
    public void foo();
    default public void bar() {
        System.out.println("Default IWhatever");
    }
}

class Whatever implements IWhatever {
    public void foo() {
        System.out.println("Whatever");
    }
}