# 포스코 교대 알람 (Android)

포스코 4조2교대 전용 알람 앱. 기존 웹앱(`work/shift-alarm/site/`)을 WebView 로 그대로 싣고,
**안드로이드 네이티브 알람(`AlarmManager.setAlarmClock`)** 으로 예정 알람을 등록해
앱을 닫거나 화면이 잠겨 있어도 정확한 시각에 울린다.

## 구성

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | WebView + `NativeAlarm` 브릿지 + 권한 안내(알림/정확한 알람/전체화면 알림/다른 앱 위에 표시/배터리) |
| `NativeAlarmBridge.kt` | 웹 ↔ 네이티브. `syncAlarms(json)`, `syncConfig(json)`, `getStatus()`, `requestPermissions()`, `openFullScreenSettings()`, `openOverlaySettings()`, `testAlarmIn(s)`, `pickRingtone()`, `setFadeIn(on)` |
| `AlarmScheduler.kt` | `setAlarmClock` 등록·취소, 5분 스누즈, 재부팅 후 재등록 |
| `AlarmReceiver.kt` | 알람 시각 도달 → 서비스 기동 + 남은 알람 재등록 |
| `AlarmService.kt` | 포그라운드 서비스: 고른 알람음(MediaPlayer, 반복) + 진동 + fullScreenIntent 알림 + 잠금화면 폴백. **진동 전용 알람**은 소리 없이, **점점 크게**는 8초 동안 15%→100% (테스트 알람은 짧은 진동만) |
| `AlarmActivity.kt` | 잠금화면 위 전체화면 알람 UI (알람 해제 / 5분 뒤 다시 / 폰 잠금 해제) |
| `AlarmOverlay.kt` | 전체화면 알림이 막힌 기기에서 "다른 앱 위에 표시"로 잠금화면 위에 그리는 마지막 보루 |
| `Perms.kt` | 전체화면 알림·오버레이·배터리 권한 상태 확인과 설정 화면 직행 |
| `RingPrefs.kt` | 알람음 URI·이름, 점점 크게 설정 저장(SharedPreferences) |
| `ShiftMath.kt` | 4조2교대 8일 주기·과거 사이클·조 편성·교대달력 예외를 Kotlin 으로 계산 |
| `ShiftSync.kt` | 6시간마다 ntfy 를 읽어 근무·휴가를 갱신하고 14일치 알람을 다시 계산·예약 |
| `ShiftSyncReceiver.kt` | `AlarmManager.setInexactRepeating` 이 깨우는 백그라운드 동기화 수신부 |
| `BootReceiver.kt` | 부팅·업데이트·시간 변경 시 알람 재등록 + 주기 동기화 재등록 |
| `assets/www/` | 배포본 웹앱 (index.html, manifest, sw, 아이콘) |

## 잠금화면에서 바로 끄기 (v1.3)

v1.2 를 실제 삼성 폰 잠금화면에서 시험했을 때 **전체화면 `AlarmActivity` 가 뜨지 않고 접힌 알림 카드만**
표시되었고, 끄려면 잠금을 풀어야 했다. 코드에서 확인한 원인과 수정:

1. **알림이 무음이었다** — `setSilent(true)` + 채널 `setSound(null)`. 삼성 One UI 는 무음 알림을
   전체화면 알림으로 승격하지 않고 일반 알림으로 강등한다. → 알림의 무음 플래그를 없애고
   (소리는 서비스가 `USAGE_ALARM` 으로 직접 재생), 채널도 새로 만든다(`shift_alarm_channel_v2`,
   `IMPORTANCE_HIGH`). 예전 채널은 삭제해 중요도가 낮아진 사용자 설정이 따라오지 않게 했다.
2. **알림 ID 가 4711 로 고정** — 시스템은 같은 ID 의 알림을 "새 알림"이 아니라 "갱신"으로 보고
   전체화면 알림 판단(`NotificationInterruptStateProviderImpl.shouldLaunchFullScreenIntentWhenAdded`)을
   건너뛴다. 이전 알람 카드가 남아 있으면 이후 알람은 영원히 전체화면이 안 뜬다.
   → 발화마다 새 ID(`AlarmUi.notifId(ts)`)로 올리고 이전 카드는 지운다.
3. **`notify()` 먼저 → `startForeground`** — 포그라운드 서비스 알림으로 바로 올리지 않고
   일반 알림으로 한 번 "추가"한 뒤 같은 알림으로 승격한다(새 알림 등록 시점에 FSI 판단이 돈다).
4. **백그라운드 액티비티 실행(BAL) 권한 미적용** — targetSdk 34 는 PendingIntent 를 보내는 쪽이
   `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`
   로 opt-in 하지 않으면 액티비티 실행이 차단되며, **차단은 예외를 던지지 않는다**(v1.2 는 성공으로 오판).
   → `AlarmUi.launch()` 가 이 옵션을 붙여 PendingIntent 를 보내고, 0.9/2/4초 뒤 재시도한다.
5. **화면을 미리 깨우지 않는다** — WakeLock 은 `startForeground`·알림 발행 뒤에도 화면을 깨우지 않는
   `PARTIAL_WAKE_LOCK` 만 쓴다. 화면은 `AlarmActivity` 의 `setTurnScreenOn(true)` 가 켠다.
   (v1.2 도 `PARTIAL_WAKE_LOCK` 이라 이 항목은 원인이 아니었지만 순서를 문서화해 둔다.)
6. **알림을 스와이프해도 꺼진다** — `setDeleteIntent(끄기)`, `setOngoing(false)`. 잠금화면 카드의
   **끄기 / 5분 뒤** 액션은 잠금 해제 없이 눌린다. 카드가 접혀 있어도 밀어서 끌 수 있다.
7. **진단** — 발화 시점의 권한 상태·화면 켜짐·잠금 여부와, 실제로 전체화면이 떴는지/오버레이로
   떴는지/알림으로만 떴는지를 `AlarmLog` 에 남기고 설정 시트에 표시한다.

설정 시트(`⚙️ 설정 → 안드로이드 네이티브 알람 · 잠금화면 점검`)는
**알림 표시(채널 중요도) / 잠금화면에 알림 표시 / 잠금화면 전체화면 알림 / 다른 앱 위에 표시**를
각각 ✓/✗ 로 보여 주고, **마지막 알람**이 어떻게 떴는지 한 줄로 알려 준다.

## 잠금화면에서 바로 끄기 (v1.2)

v1.1 은 알람이 울리면 잠금화면에는 **알림만** 뜨고, 알람 화면(`AlarmActivity`)은 잠금을 풀고
알림을 눌러야 열렸다. 원인과 수정:

1. **안드로이드 14(API 34)는 `USE_FULL_SCREEN_INTENT` 를 기본 차단**한다(통화·알람 앱으로
   분류되지 않은 사이드로드 앱). 차단 상태에서는 `setFullScreenIntent` 가 무시되고 알림만 뜬다.
   → `NotificationManager.canUseFullScreenIntent()` 로 상태를 확인하고, 꺼져 있으면 설정의
   **잠금화면 전체화면 알림** 줄에서 경고색으로 보여 주고 **바로 설정 화면으로 이동**시킨다.
   알람 화면 자체도 그 상태면 "알림에서 열렸어요 · 눌러서 켜기" 안내를 띄운다.
2. 잠금화면 위 표시 처리를 보강: `setShowWhenLocked(true)` + `setTurnScreenOn(true)` +
   `FLAG_KEEP_SCREEN_ON` + `FLAG_ALLOW_LOCK_WHILE_SCREEN_ON` + 매니페스트
   `showWhenLocked`/`turnScreenOn`/`showOnLockScreen`, 그리고 `FLAG_KEEP_SCREEN_ON` 유지.
3. 알림은 `IMPORTANCE_HIGH` + `CATEGORY_ALARM` + `VISIBILITY_PUBLIC` + `setFullScreenIntent(..., true)`
   에 `setForegroundServiceBehavior(IMMEDIATE)`(포그라운드 알림 10초 지연 방지)까지 붙였다.
   잠금화면 알림 카드에서 **끄기 / 5분 뒤** 액션이 바로 눌린다.
4. **3단계 폴백** — 알림 전체화면이 막힌 기기에서도 잠금화면에서 바로 끌 수 있게:
   ① fullScreenIntent → ② 1.2초 뒤 알람 화면이 안 떴으면 `startActivity` 직접 호출
   (정확한 알람 직후의 임시 허용 + "다른 앱 위에 표시" 권한으로 백그라운드 실행 제한을 통과)
   → ③ 그래도 안 뜨면 `SYSTEM_ALERT_WINDOW` 오버레이로 잠금화면 위에 알람 화면을 그린다.
   화면이 늦게 켜지거나(Screen On)·잠금이 풀리면(User Present) 알람 화면을 다시 올린다.
5. 끄기 동작은 **잠금 해제(`requestDismissKeyguard`) 없이** 끝난다. 끄기 버튼은 88dp 크기에
   누르는 동안 채워지는 **1.2초 길게 누르기**(실수 방지)이고, `5분 뒤 다시` 버튼은 한 번 누르면 된다.
   필요한 경우에만 `폰 잠금 해제` 버튼이 키가드를 해제한다.

## v1.4 (2026-11-19) — 원터치 끄기 · 테스트 알람 진동 · 잠금화면 안정화

v1.3 을 실제 폰에서 시험한 뒤 현수 님이 요청한 세 가지와, 그때까지 남아 있던 잠금화면 문제를 함께 고쳤다.

1. **한 번 눌러 바로 끄기** — 알람 화면의 끄기 버튼에서 1.2초 길게 누르기를 없애고 **한 번 탭**으로
   즉시 꺼진다(`AlarmActivity`). 버튼은 88dp 로 크게 두고(`bg_stop_amber` 리플), 오버레이·알림 카드의
   **끄기**도 원래부터 한 번 누르기다. `5분 뒤 다시` 버튼은 그대로 남는다.
2. **테스트 알람은 진동만** — `10초 뒤 테스트 알람`은 벨소리 없이 **약 3.6초짜리 진동 패턴(반복 없음)** 만
   울리고, 알람 화면은 **1분 뒤 스스로 꺼진다**(`TEST_AUTO_STOP_MS`). 실제 출근 알람은 기존대로
   알람음 반복 + 진동 반복.
3. **잠금화면 전체화면 알람 안정화**
   - **채널 소리** — 채널 `setSound(null, null)`(완전 무음 채널)은 삼성 One UI 가 전체화면 알림으로
     승격하지 않는 조건 중 하나다. v1.4 는 새 채널(`shift_alarm_channel_v3`)에 **들리지 않는 0.25초
     무음 리소스**(`res/raw/silent.wav`, USAGE_ALARM)를 넣어 "소리가 있는 알람 채널"로 만들고,
     실제 울림은 서비스가 낸다. 채널 소리가 null 이 아닌지는 `Perms.channelHasSound()` 로 확인·기록한다.
   - **알람 화면을 먼저 직접 띄운다** — `AlarmReceiver` 가 (저장 알람 재등록보다 먼저) 서비스를 거치지 않고
     `context.startActivity()` 로 `AlarmActivity` 를 바로 연다. 알람을 깨운 시스템 PendingIntent 브로드캐스트
     직후에는 백그라운드 액티비티 실행이 잠깐 허용되고, "다른 앱 위에 표시" 권한이 있으면 항상 허용된다.
     화면이 먼저 뜨면 `AlarmActivity` 가 서비스를 시작해 소리·진동이 함께 시작된다(중복 시작은 `activeTs` 로 막는다).
   - **경로 교대 재시도** — 서비스는 0.9/2/4초 뒤 직접 실행과 `PendingIntent`(34+ BAL opt-in)를 번갈아
     재시도하고, 그래도 안 뜨면 오버레이 → 화면만 켜기 순으로 내려간다.
   - **진단 강화** — `AlarmLog` 에 `startMode`(direct/pi)·`channelSound` 를 남기고 설정 시트의 마지막 알람 줄에
     "잠금화면 전체화면으로 떴어요 ✓ (직접 실행)"처럼 경로를 함께 보여 준다.
   - `AlarmActivity` 는 `setShowWhenLocked`/`setTurnScreenOn`(+매니페스트 `showWhenLocked`/`turnScreenOn`)을
     onCreate·onResume 에서 다시 걸고, 잠금 해제 없이 끄도록 유지했다(요청하지 않은 PIN 창은 띄우지 않는다).
4. 웹(설정 시트) 문구: 테스트 버튼 라벨을 `10초 뒤 테스트 알람 (짧은 진동만 · 소리 없음)` 으로,
   도움말의 끄기 안내를 "한 번 누르면 바로 꺼져요"로 바꿨다. 버전 표기는 v1.4.

검증: `test_app.py` 31/31 · `test_clock.py` 20/20 · `test_native.py` 62/62
(네이티브 소스 점검 21항목 신규 — 원터치·테스트 진동·채널 v3·직접 실행 경로·versionCode).
실기기 잠금화면 검증은 현수 님 폰에서 필요하다(APK `downloads/포스코교대알람-v1.4.apk`).

## v2.0 (2026-11-19) — 여러 알람 · 알람음/점점 크게 · 백그라운드 자동 동기화

현수 님이 요청한 네 가지를 한 번에 넣었다. 알람 화면 버튼 문구는 v1.5 의 **"알람 해제"** 그대로다.

1. **근무별 복수 알람** — 주간/야간/휴무마다 알람을 여러 개 두고 각각 시간·울림 방식·켜기/끄기를
   바꿀 수 있다(예: 주간 `05:20 (진동만)` + `05:21 (소리+진동)`). 데이터는
   `{ id, time, mode: "vibrate"|"sound", enabled }` 로 저장하고, 예정 알람은 **날짜 × 켜진 알람**마다
   고유 id(`2026-11-19|slot_1`)로 `NativeAlarm.syncAlarms` 에 전달한다. v1.5 이하의 단일 시간 설정은
   불러올 때 자동으로 알람 한 건으로 옮긴다(주간 05:20 / 야간 16:30 유지).
   (v2.1부터 특근은 알람 설정·예약에서 빠졌다 — 아래 v2.1 항목 참고)
2. **알람별 진동만 / 소리+진동** — `ShiftAlarm.mode` 로 전달해 `AlarmService` 가 `vibrate` 알람은
   벨소리 없이 진동만 반복한다(테스트 알람과 달리 **해제할 때까지 계속**).
3. **알람음 선택 · 점점 크게** — `NativeAlarm.pickRingtone()` 이 `ACTION_RINGTONE_PICKER` 를 열어
   알람음(TYPE_ALARM)·알림음(TYPE_NOTIFICATION)·기본값 중에서 고르게 하고, 고른 URI 를
   SharedPreferences 에 저장해 `MediaPlayer` 로 재생한다. **점점 크게**를 켜면 8초 동안 볼륨이
   15% → 100% 로 올라간다(0.5초 × 16단계).
4. **백그라운드 자동 동기화** — `ShiftSync` 가 6시간마다(`setInexactRepeating`, 하루 4회) 앱을 열지 않아도
   `ntfy.sh/<동기화 코드>` 를 읽어 `customShifts`·`crewHistory` 를 갱신하고, `ShiftMath` 로 14일치 근무를
   다시 계산해 알람을 재등록한다. `INTERNET` 권한 사용, 원본 교대달력은 쓰지 않는다(읽기 전용).
   ntfy 보관이 비어 있으면 웹과 같은 기준 스냅샷으로 계산한다. 부팅·앱 시작 때도 주기 동기화를 다시 건다.

검증: `test_app.py` 37/37 · `test_clock.py` 20/20 · `test_native.py` 80/80
(네이티브 소스 점검에 v2.0 항목 11개 추가 — mode · 진동 전용 · 알람음 · 페이드 · 6시간 동기화 · INTERNET).
APK: `downloads/포스코교대알람-v2.0.apk` (versionCode 7). 실기기 확인은 현수 님 폰에서.

## v2.1 (2026-11-19) — 특근(overtime)은 알람에서 제외

현수 님 요청: "특근은 알람에서 없애죠". 특근은 출근 시각이 미정이라 고정 알람(05:20)이 의미가 없으므로
**알람 설정·예약 대상에서 완전히 뺀다**(근무·배지 표시는 그대로).

- 웹: 설정 시트의 `근무별 알람`에서 특근 블록을 없앴다(`TYPE_ORDER = ['day','night','off']`).
  `alarmsFor()`·`alarmFor()`·`nativeAlarmList()`·`nativeConfig()` 모두 특근 날에는 알람을 만들지 않는다.
  특근 날짜를 열면 알람 토글·시각·저장이 비활성되고 "특근은 알람을 잡지 않아요" 안내가 뜬다.
  목록·배지·근무 표시(`특`, `overtime` 스타일)는 그대로 남긴다.
- Android: `ShiftMath.hasAlarms(type)` 이 `overtime` 을 거칖하고, `ShiftSync.buildAlarms` 가
  그 근무를 건넌다(웹이 안 보내도 백그라운드 재계산에서도 알람이 생기지 않음).
- versionCode 8 / versionName 2.1, `assets/www` 를 웹 배포본과 동일하게 맞춤(index.html MD5 일치).
- 검증: `test_app.py` 44/44(특근 6항목 신규) · `test_clock.py` 20/20(v2.1 표기) ·
  `test_native.py` 85/85(특근 제외·블록 3개·assets 동일성 5항목 추가).
- APK: `downloads/포스코교대알람-v2.1.apk` (versionCode 8). 실기기 확인은 현수 님 폰에서.

## v2.3 (2026-11-26) — 앱 음량 슬라이더 = 휴대폰 시스템 알람 볼륨(1:1)

현수 님 피드백: 앱의 `음량 100%` 가 작다. "시스템 소리를 내 휴대폰 음향소리랑 매칭시켜서 내가 원하는만큼
제어되게 해야 하는데 안 되는거지".

- 원인: v2.2 의 슬라이더는 `MediaPlayer.setVolume()`(앱 내부 배율)만 조절해, 폰의 `STREAM_ALARM` 볼륨이
  낮으면 100% 로 올려도 그 한계에 갇혔다.
- `AlarmVolume`(신규): `getStreamVolume/getStreamMaxVolume(STREAM_ALARM)` 로 폰 알람 볼륨을 읽고
  `setStreamVolume(STREAM_ALARM, index, 0)` 으로 직접 바꾼다. %↔단계 환산(7/15 → 47%, 100% → 15/15).
- 브릿지 `getSystemAlarmVolume()` / `setSystemAlarmVolume(pct)` — 바뀐 실제 값을 돌려줘 슬라이더가
  폰 볼륨에 맞춰진다. 설정 시트를 열면 지금 폰 알람 볼륨(예: `47% · 7/15`)을 그대로 보여 준다.
- `폰 알람 볼륨도 최대로` 토글 제거(슬라이더가 곧 폰 볼륨). `+6/+12dB` 부스트는 폰 최대 위에 더한다.
- `MainActivity.setVolumeControlStream(STREAM_ALARM)` + `VOLUME_CHANGED_ACTION` 수신으로 물리 볼륨 키
  변경도 슬라이더에 반영. 울릴 때 볼륨을 올렸다 되돌리던 동작은 없애고 MediaPlayer 는 100% 로 재생한다
  (앱 배율×시스템 볼륨 이중 감쇠 제거). 폰 볼륨이 0(무음)이고 마지막 설정값이 0이 아니면 복원한다.
- `MODIFY_AUDIO_SETTINGS` 권한, versionCode 10 / versionName 2.3.
- 검증: `testDebugUnitTest` 9/9 · `lintDebug` 0 errors · `test_native.py` 129/129 ·
  `test_app.py` 55/55 · `test_clock.py` 20/20 · `test_live.py` ALL PASS.
- APK: `downloads/포스코교대알람-v2.3.apk`.

## 웹앱과의 관계

- 웹앱 JS 는 `window.NativeAlarm` 이 있을 때만 네이티브 경로를 쓴다. 브라우저(배포본)에서는
  기존 웹 알람·탁상시계 동작이 그대로 유지된다(`isNative()` 가드).
- 네이티브 모드에서는 OS 알람이 울리므로 웹 JS 의 자체 울림(`checkFire`)은 건너뛴다(중복 울림 방지).
- `refreshAll()` 때마다 향후 14일 예정 알람을 `{id, date, time, label, ts, mode}` 로 넘겨 재등록하고,
  `syncConfig({topic, types, master, overrides})` 로 근무별 알람 규칙을 넘겨 백그라운드 동기화가 같은 규칙을 쓰게 한다.
- `⏰ 시계앱` 시트는 네이티브 모드에서 "네이티브 알람 예약됨 + 권한 상태"로 바뀐다.
- 네이티브 모드(v1.1~)에서는 탁상시계 버튼·안내를 감춰 메인 액션이 `🔔 소리 테스트`·`⏰ 알람 상태` 두 개만 남는다(웹 배포본은 기존 탁상시계 유지).
- 설정 시트(`⚙️ 설정 → 안드로이드 네이티브 알람`)에 **잠금화면 전체화면 알림 / 다른 앱 위에 표시** 상태와
  각 설정 화면으로 바로 가는 버튼을 따로 두었다(v1.2).

## 빌드

GitHub Actions(`.github/workflows/build-apk.yml`)에서 자동 빌드한다.
산출물: `app/build/outputs/apk/debug/app-debug.apk` (artifact `posco-shift-alarm-debug`).

로컬에서 빌드하려면 JDK 17 + Android SDK(compileSdk 34) + Gradle 8.7:

```bash
sh build_local.sh
```

## 설치 후

1. 앱을 열고 알림 권한을 허용한다.
2. 설정 → 안드로이드 네이티브 알람에서 **정확한 알람 · 잠금화면 전체화면 알림 · 배터리 예외**를 켠다.
   (기기에 따라 `다른 앱 위에 표시`도 함께 켜면 잠금화면 알람이 더 확실하다.)
3. `10초 뒤 테스트 알람`으로 **화면을 잠근 상태에서** 잠금화면 위 알람 화면이 뜨는지 확인한다.

minSdk 24 / targetSdk 34 / versionCode 10 / versionName 2.3
