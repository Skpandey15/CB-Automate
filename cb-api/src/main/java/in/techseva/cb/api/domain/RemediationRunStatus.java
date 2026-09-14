package in.techseva.cb.api.domain;

/** Mirrors remediate.py's report['status'] values exactly (see remediate.py:507,583,591,594). */
public enum RemediationRunStatus {
    RUNNING,
    FIXED,
    PARTIAL,
    MANUAL_REQUIRED,
    NO_FINDINGS,
    FAILED
}
