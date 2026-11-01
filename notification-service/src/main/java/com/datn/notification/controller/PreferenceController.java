package com.datn.notification.controller;
import com.datn.notification.delivery.Preferences;
import com.datn.notification.delivery.Preferences.Setting;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
/** The current user's email/push switches per category. The in-app inbox always receives every notification. */
@RestController @RequiredArgsConstructor @RequestMapping("/notifications/preferences")
public class PreferenceController {
    private final Preferences preferences;
    public record Body(List<Setting> settings){}
    @GetMapping public Body get(@RequestAttribute UUID actor){return new Body(preferences.list(actor));}
    @PutMapping public Body put(@RequestBody Body body,@RequestAttribute UUID actor){return new Body(preferences.update(actor,body==null?null:body.settings()));}
}
