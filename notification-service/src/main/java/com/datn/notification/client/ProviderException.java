package com.datn.notification.client;
/** Provider call outcome that is not a usable response. Messages never contain tokens or contact data. */
public class ProviderException extends RuntimeException {
    public enum Kind { CONFIG, NOT_FOUND, TRANSIENT, INVALID }
    private final Kind kind;
    public ProviderException(Kind kind,String message){super(message);this.kind=kind;}
    public Kind kind(){return kind;}
}
