# Exam Calendar / Scheduling frontend result

Branch `frontend/app`. Authority: `contracts/exam-calendar-ui-scope.md` (user "duyệt", MAIN `f7c933d`),
`exam-scheduling-api.md`, `notification-scheduling.md`. Backend and contracts were read-only.
Final state: **READY FOR MANUAL UI VALIDATION** (not approved by the user yet).

## What exists

| Area | Where |
| --- | --- |
| `/calendar` (Teacher "Lịch thi", Student "Lịch của tôi"): Agenda (default), Tháng, Tuần, Ngày; last view remembered per device | `src/features/calendar/CalendarPage.tsx`, `CalendarViews.tsx`, `ExamCard.tsx` |
| Compact cards, legend (not colour-only), right panel / bottom sheet with role-aware actions, "+N" block for crowded hours | `DetailPanel.tsx`, `calendar.css` |
| Teacher schedule form: IANA zone, DST gap/overlap, 3 deadline rules, auto open/close, reminders (24h/15m default, custom, off), reason, review step, revision conflicts | `ScheduleDialog.tsx`, `ReminderEditor.tsx`, `scheduleModel.ts` |
| "Lịch" tab and close-with-reason / reopen on the exam page; "Đặt lịch cho đề" on the calendar | `ExamDetailPage.tsx`, `LifecycleDialog.tsx`, `ScheduleExamPicker.tsx` |
| Student: availability-driven lobby with early preview, Follow/Unfollow for PUBLIC, join-code preview, My Exams with backend states, Today/Upcoming on both dashboards | `ExamLobbyPage.tsx`, `FollowButton.tsx`, `MyExamsPage.tsx`, `JoinCodeForm.tsx`, `TodayUpcoming.tsx` |
| In-app notifications (bell, `/notifications`) and opt-in browser push with service worker | `src/features/notifications/*`, `public/sw.js` |
| Time zone arithmetic (no date library) | `src/lib/zone.ts` |

Rules kept: grids use the viewer's zone and say so; every card shows the Exam's zone and a "Giờ của bạn" line when it differs;
`canJoin/canStart/canResume/reasonCode/serverNow/deadline` are the backend's answers (the browser clock only drives display and decides
*when to ask again*); no identity headers, no internal tokens; TEACHER != ADMIN; no drag/drop, resize or create-from-empty-cell;
no library added.

## MAIN review findings

| Finding | Result |
| --- | --- |
| DST axis / now line (`msg_a0fc`, `msg_f1c7`) | Grid positions on the 24-row wall-clock axis. Browser proof in an `America/Los_Angeles` browser on 2030-03-10 (23h) and 2030-11-03 (25h): the 10:00 block is on the 10:00 row (0 px, 41.6667%). Unit tests for spring, fall, clipping, now line. |
| Time labels across DST overlap | Window text, Agenda rows and grid blocks carry each end's offset when they differ ("01:30 GMT-7–01:30 GMT-8"); MAIN's "DST overlap" exam renders that way in the browser. |
| Start-only OPEN not "past" | Open-ended running Exams stay in Hôm nay; the 1h block is drawing only. Unit tests. |
| Early Join after preview | Join is locked with the backend reason when the preview says `canJoin=false`. Browser: JOIN_CODE exam starting tomorrow. |
| Mobile bottom sheet focus | One focus hook for Dialog and panel: Tab trap, scroll lock, Escape only for the top-most modal, focus returns to the opener. Browser (390px): 20 Tab/Shift+Tab presses stay inside, Escape closes, scroll lock released, focus back on the card; nested "Sửa lịch" dialog closes first and keeps the lock. |
| Refresh at boundaries | Calendar and lobby re-ask the backend just after the next start/end/expiry plus a 2-minute light refresh. Browser: standing still on the calendar, an Exam flipped from "Sắp diễn ra" to "Cần tham gia" 13s after `startAt`. |
| Push cleanup on forced logout | Watcher in the auth runtime covers logout, expiry, failed refresh, account switch and a dead session at start-up, using the old identity and token; browser subscription is dropped first, then DELETE. Browser: failed refresh -> `/login`, record cleared, DELETE sent. |
| Pending enable vs logout/switch (`msg_7fa7df8d7bd7`) | `enablePush` re-checks the session after every await, unsubscribes the exact subscription it holds, registers nothing for the new account and deletes a server record the POST may have created using the old token. Unit tests for each await point; mutation-checked. Thêm: các lần enable được xếp hàng (một subscription cho mỗi registration), `undo` chỉ gỡ subscription do chính lần enable đó tạo; test A đang chờ subscribe, B enable trước khi A xong; kiểm tra mutation (bỏ hàng đợi / undo luôn unsubscribe đều làm test đỏ). |
| Legacy editor pass-through (`msg_c5dd`) | Stored start/end sent verbatim (null stays null). Browser: editing a draft's title/description left startAt (to the millisecond), zone, rule, auto flags, reminders and `scheduleRevision` unchanged. |

## Contract observations for MAIN (no backend change made)

1. `GET /exams/public` still returns the legacy shape (no `followed`, `timezone`, availability). The UI calls `/availability` for each public Exam (first 20). A combined field would remove N calls.
2. `CalendarItem` has no `scheduleRevision`, auto flags or reminder offsets, so the editor reads `GET /exams/{id}/schedule`. Not blocking.
3. Agenda asks for 30 days back to 330 days ahead (backend maximum is 366). Please confirm the range query returns an open-ended Exam whose `startAt` is before `from`; the UI assumes it does.
4. In-app links rely on `metadata.link` from Notification (works against the real stack).
5. Access tokens stay valid after `/auth/logout`; only refresh is revoked (observed while testing forced logout).

## Verification

Commands (from `frontend/`): `npm exec -- tsc -b --pretty false` PASS, `npm run build` PASS (main chunk 301 kB, routes lazy-loaded),
`bash tests/validate-shared.sh` PASS (unit suites: auth runtime, UI logic, zone, calendar model, schedule model, notifications, push/auth),
`npm run lint` PASS, `git diff --check` PASS, `python3 tests/browser-smoke.py` PASS 8/8 (fixture API, route guards only).

Real browser against the real Gateway `127.0.0.1:18180`, Vite `127.0.0.1:13100` (agent-browser, own sessions):
- Teacher: agenda/month/week/day, crowded hours, schedule create (Europe/Paris, DST gap blocked, overlap choice, saved values read back from the API),
  revision conflict + reload, publish/open/close/reopen with reasons and audit log, bell and notification navigation, proctor read-only panel.
- Student: agenda/month with real states, Follow a PUBLIC Exam, early preview with Join/Start locked (backend also returns 409), boundary refresh,
  join-code preview lock, join -> lobby -> start, mobile 390px views with no horizontal overflow.

Not verified / limits:
- **Physical push display was NOT verified.** The automated Chrome refuses notification permission (`denied`), so no real browser subscription, provider delivery or OS notification was produced. The opt-in states, service worker code and cleanup are covered by unit tests with a fake browser and by the real-browser logout path only.
- Gmail inbox delivery is not part of this UI work.
- The DST axis in the browser was checked with a Los Angeles browser clock (`TZ=America/Los_Angeles`); other zones rely on unit tests.
- Admin has the "Lịch thi" menu entry; calendar content for Admin follows whatever the backend returns for that account.

## Manual checklist

Teacher: open Lịch thi (Agenda), switch Tháng/Tuần/Ngày, click a card (panel), "Đặt lịch cho đề" -> pick an Exam -> set zone/start/end/rule/reminders -> review -> save;
try a DST gap time (e.g. 2027-03-28 02:30 in Europe/Paris) and an overlap (2026-10-25 02:30); edit the same schedule from two tabs for the revision message;
close an Exam before its end (reason required) and reopen it; check the bell and `/notifications`.
Student: Lịch của tôi; Follow a PUBLIC Exam from Kỳ thi của tôi; open Xem trước and confirm Join/Start stay locked until the start; paste a join code and press Xem trước;
watch an upcoming Exam become available without reloading; try everything on a phone width and with the keyboard only (Tab, Escape in the bottom sheet).
