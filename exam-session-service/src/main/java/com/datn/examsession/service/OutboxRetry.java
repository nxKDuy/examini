package com.datn.examsession.service;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.util.concurrent.TimeUnit;

/**
 * Publisher-confirm outcome and retry schedule shared by this service's outbox relays (same rules as
 * grading fa63598): capped exponential backoff, then park; reasons UNROUTABLE / NACK / CONFIRM_TIMEOUT.
 */
final class OutboxRetry {
    private OutboxRetry() {}
    static final class Failure extends Exception { Failure(String message){super(message);} }
    /** Waits for the confirm; a mandatory return means no queue is bound to the exchange/routing key. */
    static void confirmed(CorrelationData correlation)throws Exception {
        CorrelationData.Confirm confirm;
        try{confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException timeout){throw new Failure("CONFIRM_TIMEOUT: broker did not confirm within 5s");}
        var returned=correlation.getReturned();
        if(returned!=null)throw new Failure("UNROUTABLE: "+returned.getReplyCode()+" "+returned.getReplyText()+" (no queue bound to "+returned.getExchange()+"/"+returned.getRoutingKey()+")");
        if(confirm==null || !confirm.isAck())throw new Failure("NACK: "+(confirm==null?"no confirm":confirm.getReason()));
    }
    static String reason(Exception failure){return failure instanceof Failure?failure.getMessage():failure.getClass().getSimpleName()+": "+failure.getMessage();}
    static long backoffMs(int attempts,long baseMs,long maxMs){return Math.min(maxMs,baseMs*(1L<<Math.min(Math.max(attempts-1,0),30)));}
    static String truncate(String s){return s==null||s.length()<=500?s:s.substring(0,500);}
}
