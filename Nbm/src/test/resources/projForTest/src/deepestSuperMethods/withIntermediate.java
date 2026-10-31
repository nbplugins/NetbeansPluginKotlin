package deepestSuperMethods;

public class WithIntermediate extends Middle {

    @Override
    public boolean equals(Object other) {
        return false;
    }
}

class Middle {

    @Override
    public boolean equals(Object other) {
        return true;
    }
}
