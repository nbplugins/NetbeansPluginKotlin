package nullability;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Annotated {

    @Nullable
    public String maybeNull() {
        return null;
    }

    @NotNull
    public String alwaysNotNull() {
        return "";
    }

    public String unannotated() {
        return "";
    }

    public int primitive() {
        return 1;
    }

    public void withParameter(@Nullable String value) {
    }
}
