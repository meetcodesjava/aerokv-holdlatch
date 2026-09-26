package day05;

/** Protocol verbs and framing constants for AeroServer's line-based wire protocol. */
public final class AeroCommand {
    private AeroCommand() {}

    public static final String SET = "SET";
    public static final String PUT = "PUT";
    public static final String GET = "GET";
    public static final String DEL = "DEL";
    public static final String DELETE = "DELETE";
    public static final String HOLD = "HOLD";
    public static final String MHOLD = "MHOLD";
    public static final String RELEASE = "RELEASE";
    public static final String RELEASE_IF = "RELEASEIF";
    public static final String AUTH = "AUTH";
    public static final String PING = "PING";

    /** Separates individual seat/key IDs inside a single MHOLD command's key-list field. */
    public static final String MULTI_KEY_SEPARATOR = "\\|";
}
