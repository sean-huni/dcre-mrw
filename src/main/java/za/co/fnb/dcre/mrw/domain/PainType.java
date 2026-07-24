package za.co.fnb.dcre.mrw.domain;

/**
 * Mandate outbound message vocabulary (domain, R-21: the action -> ISO 20022
 * message mapping is business logic, persisted as a code value never an enum
 * ordinal). One action code maps to exactly one pain message:
 * <ul>
 *   <li>CREATE -> pain.009 MandateInitiationRequest,</li>
 *   <li>AMEND  -> pain.010 MandateAmendmentRequest,</li>
 *   <li>CANCEL -> pain.011 MandateCancellationRequest.</li>
 * </ul>
 * {@code token} is the filename + registry {@code pain_type} code value (the
 * fint-sim T10 globs {@code *_PAIN00[9]*.xml} etc.); {@code profile} is the RMB
 * profile version (SYNTHETIC per A-60 until the XSDs are recovered); {@code root}
 * is the synthetic message root element the writer wraps.
 */
public enum PainType {

    PAIN009("CREATE", "PAIN009", "pain.009.001.03", "MndtInitnReq"),
    PAIN010("AMEND", "PAIN010", "pain.010.001.03", "MndtAmdmntReq"),
    PAIN011("CANCEL", "PAIN011", "pain.011.001.03", "MndtCxlReq");

    private final String actionCode;
    private final String token;
    private final String profile;
    private final String root;

    PainType(final String actionCode, final String token, final String profile, final String root) {
        this.actionCode = actionCode;
        this.token = token;
        this.profile = profile;
        this.root = root;
    }

    /** Fail-closed: an INITIALIZED spine row with an unmapped action is a bug, never a silent skip. */
    public static PainType forAction(final String actionCode) {
        for (final PainType type : values()) {
            if (type.actionCode.equals(actionCode)) {
                return type;
            }
        }
        throw new IllegalArgumentException("no pain message mapped for action_code '%s'".formatted(actionCode));
    }

    public String token() {
        return token;
    }

    public String profile() {
        return profile;
    }

    public String root() {
        return root;
    }
}
