package j2k;

public abstract class AbstractNoAnn {
    private String ballast = null;

    public abstract String[] getPrimaryKey();

    public String getBallast() {
        return ballast;
    }

    public void setBallast(String ballast) {
        this.ballast = ballast;
    }

    public String getConcreteName() {
        return ballast;
    }
}
