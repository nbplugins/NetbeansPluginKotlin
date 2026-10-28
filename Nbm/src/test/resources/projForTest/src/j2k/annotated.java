package j2k;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
public abstract class Annotated {
    private String ballast = null;

    @JsonIgnore
    public abstract String[] getPrimaryKey();

    public String getBallast() {
        return ballast;
    }

    public void setBallast(String ballast) {
        this.ballast = ballast;
    }

    @JsonIgnore
    public String getConcreteName() {
        return ballast;
    }
}
