# 포스코 교대 알람 (Android)

포스코 4조2교대 전용 알람 앱. 기존 웹앱(`work/shift-alarm/site/`)을 WebView 로 그대로 싣고,
**안드로이드 네이티브 알람(`AlarmManager.setAlarmClock`)** 으로 예정 알람을 등록해
앱을 닫거나 화면이 잠겨 있어도 정확한 시각에 울린다.

## 구성

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | WebView + `NativeAlarm` 브릿지 + 권한 안내(알림/정확한 알람/전체화면 알림/다른 앱 위에 표시/배터리) |
| `NativeAlarmBridge.kt` | 웹 ↔ 네이티브. `syncAlarms(json)`, `getStatus()`, `requestPermissions()`, `openFullScreenSettings()`, `openOverlaySettings()`, `testAlarmIn(s)` |
| `AlarmScheduler.kt` | `setAlarmClock` 등록·취소, 5분 스누즈, 재부팅 후 재등록 |
| `AlarmReceiver.kt` | 알람 시각 도달 → 서비스 기동 + 남은 알람 재등록 |
| `AlarmService.kt` | 포그라운드 서비스: 시스템 알람음(USAGE_ALARM) 무한 반복 + 진동 + fullScreenIntent 알림 + 잠금화면 폴백 |
| `AlarmActivity.kt` | 잠금화면 위 전체화면 알람 UI (길게 눌러 끄기 / 5분 뒤 다시 / 폰 잠금 해제) |
| `AlarmOverlay.kt` | 전체화면 알림이 막힌 기기에서 "다른 앱 위에 표시"로 잠금화면 위에 그리는 마지막 보루 |
| `Perms.kt` | 전체화면 알림·오버레이·배터리 권한 상태 확인과 설정 화면 직행 |
| `BootReceiver.kt` | 부팅·업데이트·시간 변경 시 알람 재등록 |
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

## 웹앱과의 관계

- 웹앱 JS 는 `window.NativeAlarm` 이 있을 때만 네이티브 경로를 쓴다. 브라우저(배포본)에서는
  기존 웹 알람·탁상시계 동작이 그대로 유지된다(`isNative()` 가드).
- 네이티브 모드에서는 OS 알람이 울리므로 웹 JS 의 자체 울림(`checkFire`)은 건너뛴다(중복 울림 방지).
- `refreshAll()` 때마다 향후 14일 예정 알람을 `{id, date, time, label, ts}` 로 넘겨 재등록한다.
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

minSdk 24 / targetSdk 34 / versionCode 4 / versionName 1.3
