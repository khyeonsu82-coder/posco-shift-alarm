# 포스코 교대 알람 (Android)

포스코 4조2교대 전용 알람 앱. 기존 웹앱(`work/shift-alarm/site/`)을 WebView 로 그대로 싣고,
**안드로이드 네이티브 알람(`AlarmManager.setAlarmClock`)** 으로 예정 알람을 등록해
앱을 닫거나 화면이 잠겨 있어도 정확한 시각에 울린다.

## 구성

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | WebView + `NativeAlarm` 브릿지 + 권한 안내(알림/정확한 알람/전체화면/배터리) |
| `NativeAlarmBridge.kt` | 웹 ↔ 네이티브. `syncAlarms(json)`, `getStatus()`, `requestPermissions()`, `testAlarmIn(s)` |
| `AlarmScheduler.kt` | `setAlarmClock` 등록·취소, 5분 스누즈, 재부팅 후 재등록 |
| `AlarmReceiver.kt` | 알람 시각 도달 → 서비스 기동 + 남은 알람 재등록 |
| `AlarmService.kt` | 포그라운드 서비스: 시스템 알람음(USAGE_ALARM) 무한 반복 + 진동 + fullScreenIntent 알림 |
| `AlarmActivity.kt` | 잠금화면 위 전체화면 알람 UI (스누즈 5분 / 알람 끄기) |
| `BootReceiver.kt` | 부팅·업데이트·시간 변경 시 알람 재등록 |
| `assets/www/` | 배포본 웹앱 (index.html, manifest, sw, 아이콘) |

## 웹앱과의 관계

- 웹앱 JS 는 `window.NativeAlarm` 이 있을 때만 네이티브 경로를 쓴다. 브라우저(배포본)에서는
  기존 웹 알람·탁상시계 동작이 그대로 유지된다(`isNative()` 가드).
- 네이티브 모드에서는 OS 알람이 울리므로 웹 JS 의 자체 울림(`checkFire`)은 건너뛴다(중복 울림 방지).
- `refreshAll()` 때마다 향후 14일 예정 알람을 `{id, date, time, label, ts}` 로 넘겨 재등록한다.
- `⏰ 시계앱` 시트는 네이티브 모드에서 "네이티브 알람 예약됨 + 권한 상태"로 바뀐다.
- 네이티브 모드(v1.1~)에서는 탁상시계 버튼·안내를 감춰 메인 액션이 `🔔 소리 테스트`·`⏰ 알람 상태` 두 개만 남는다(웹 배포본은 기존 탁상시계 유지).

## 빌드

GitHub Actions(`.github/workflows/build-apk.yml`)에서 자동 빌드한다.
산출물: `app/build/outputs/apk/debug/app-debug.apk` (artifact `posco-shift-alarm-debug`).

로컬에서 빌드하려면 JDK 17 + Android SDK(compileSdk 34) + Gradle 8.7:

```bash
gradle assembleDebug
```

## 설치 후

1. 앱을 열고 알림 권한을 허용한다.
2. 설정 → 안드로이드 네이티브 알람에서 **정확한 알람 · 잠금화면 알람 · 배터리 예외**를 모두 켠다.
3. `10초 뒤 테스트 알람`으로 잠금화면 위 전체화면 알람을 확인한다.

minSdk 24 / targetSdk 34.
