package com.datn.notification.delivery;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PushEndpointPolicyTest {
    PushEndpointPolicy production=new PushEndpointPolicy(List.of("fcm.googleapis.com","push.services.mozilla.com","notify.windows.com","push.apple.com"),false);
    @Test void allowsOnlyHttpsBrowserPushServices(){
        for(String ok:List.of("https://fcm.googleapis.com/fcm/send/abc:def","https://updates.push.services.mozilla.com/wpush/v2/gAAA","https://web.push.apple.com/QH1x","https://wns2-par02p.notify.windows.com/w/?token=x"))
            assertDoesNotThrow(()->production.validate(ok),ok);
    }
    @Test void ssrfTargetsAndMalformedEndpointsRejected(){
        for(String bad:List.of("http://fcm.googleapis.com/fcm/send/x","https://169.254.169.254/latest/meta-data","https://127.0.0.1/push","https://[::1]/push","https://localhost/push",
                "https://evil.localhost/x","https://10.0.0.5/x","https://2130706433/x","https://fcm.googleapis.com:8443/x","https://user@fcm.googleapis.com/x",
                "https://fcm.googleapis.com.evil.test/x","https://evilfcm.googleapis.com.attacker/x","https://attacker.test/fcm.googleapis.com","ftp://fcm.googleapis.com/x",
                "file:///etc/passwd","not a url","","https://fcm.googleapis.com/"+"a".repeat(2100)))
            assertThrows(IllegalArgumentException.class,()->production.validate(bad),bad);
        assertThrows(IllegalArgumentException.class,()->production.validate(null));
    }
    @Test void insecureLoopbackOnlyWithExplicitTestFlag(){
        assertThrows(IllegalArgumentException.class,()->production.validate("http://127.0.0.1:9000/push/a"));
        assertDoesNotThrow(()->new PushEndpointPolicy(List.of("fcm.googleapis.com"),true).validate("http://127.0.0.1:9000/push/a"));
    }
}
