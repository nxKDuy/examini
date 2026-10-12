package com.datn.exam.dto;

/** Browser signals are evidence; only the configured aggregate budget may end one attempt. */
public record IntegrityPolicy(boolean monitorTabSwitch, boolean monitorWindowBlur,
        boolean requireFullscreen, boolean monitorCopy, boolean monitorPaste,
        boolean monitorNetwork, WarningPolicy warningPolicy, Integer warningEventLimit) {
    public static final int DEFAULT_WARNING_EVENT_LIMIT=5,MIN_WARNING_EVENT_LIMIT=1,MAX_WARNING_EVENT_LIMIT=100;
    public enum WarningPolicy { LOG_ONLY, WARN_ONLY }
    public IntegrityPolicy {
        if(warningEventLimit==null)warningEventLimit=DEFAULT_WARNING_EVENT_LIMIT;
    }
    public IntegrityPolicy(boolean monitorTabSwitch,boolean monitorWindowBlur,boolean requireFullscreen,
            boolean monitorCopy,boolean monitorPaste,boolean monitorNetwork,WarningPolicy warningPolicy) {
        this(monitorTabSwitch,monitorWindowBlur,requireFullscreen,monitorCopy,monitorPaste,monitorNetwork,
                warningPolicy,DEFAULT_WARNING_EVENT_LIMIT);
    }
    public static IntegrityPolicy disabled() {
        return new IntegrityPolicy(false, false, false, false, false, false, WarningPolicy.LOG_ONLY,DEFAULT_WARNING_EVENT_LIMIT);
    }
}
