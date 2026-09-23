# Звонки: передача работы — 2026-09-18

Документ для новой сессии: что сделано, где лежат результаты, как поднять стенд и что осталось. Начинать с него.

## Как начать новую сессию

Вставить в первое сообщение:

> Продолжаем работу по звонкам. Прочитай `docs/plans/2026-09-18-calls-handoff.md` — там состояние, стенд и список
> задач. Работаем в ветке `fix/calls-2026-09`. Начни с задачи «…» из раздела «Открытые задачи».

Всё, что ниже, дополняет `AGENTS.md`: правила сборки, тестов и ревью там.

## Состояние кода

- Ветка `fix/calls-2026-09`. Владелец запушил её 2026-09-21; что не запушено, показывает
  `git log origin/fix/calls-2026-09..HEAD`.
- Локальный `master` на 35 коммитов впереди `origin/master` — это та же работа по звонкам до ветки; `master` в ветку
  влит, отставаний нет.
- Проверки на последнем коммите: `npm run build`, `npm run test` (4317), Kotlin
  `:app:testSideloadDebugUnitTest --rerun-tasks` (569) — зелёные.

## Где что лежит

- `docs/manual-verification.md` — все починки, которым нужна проверка на аппарате. Раздел «Ожидают проверки» (7
  записей) — открытые; «Проверено» — закрытые, с логами и замерами. У каждой записи шаги, «Раньше/Ожидается» и статус.
- `docs/call-bugs-needing-you.md` — отчёты пользователей по группам (B–H) и что по каждой группе нужно от владельца.
  Раздел E — итог по Bastyon.
- `docs/call-fix-checklist.md`, `docs/call-bug-reproduction-matrix.md` — исходный разбор отчётов (кластеры O01–O16).
- Память агента `~/.claude/projects/-Users-Alexandr-Sites-host-forta-chat/memory/` — по файлу на найденный дефект и на
  стенд; индекс `MEMORY.md` грузится в новую сессию сам. Главные: `forta-chat-samsung-bench.md` (стенд),
  `forta-chat-swipe-hangup-native.md`, `forta-chat-audio-stream-leak-freeze.md`,
  `forta-chat-native-webrtc-proxy-semantics.md`, `forta-chat-webview-page-freeze-during-calls.md`.
- Отчёты пользователей: публичный репозиторий `greenShirtMystery/forta-bugs` (открытые issues). `gh` с токеном
  j-bitmaker не работает; читать через `api.github.com` без авторизации.

## Стенд

Скрипты стенда живут во временной папке сессии (`scratchpad`) и **стираются ночной очисткой** — вместе с профилем
браузера, в котором выполнен вход. В новой сессии их придётся создать заново; их устройство описано в
`forta-chat-samsung-bench.md` и в записях `manual-verification.md` («Скрипт …»).

- **Samsung SM-A528B**, serial `R5CT316HB2T`, Android 14. Forta — с 2026-09-23 аккаунт **TEST2** (`test3232883282`,
  адрес `P8dNk3RL…`): сессию `Testtest11223344` стёр прогон E2E (`clearState` на первом попавшемся устройстве). Комната
  с TEST1 — `!KchsUDqhsdPwFUXfwd:matrix.pocketnet.app`, оба правила пушей на TEST2 встали, первый звонок TEST1 → TEST2
  прошёл (`bench-test2-1`: ответ, `select_answer … answered on this device`, отбой). Bastyon 1.8.124 на нём по-прежнему
  под `Testtest11223344` — для сценариев группы E Forta надо вернуть в тот же аккаунт (вход — владелец).
- **Pixel 9**, serial `57150DLAQ001B6`, Android 17. Forta — аккаунт `test1122334455667788`. Сейчас отключён.
- **Веб**: headless Chrome через Playwright (`channel: 'chrome'`) с постоянным профилем. Звонящий — TEST1
  (`test3823818`); третий аккаунт — `test23438111`. **Вход в веб делает владелец**: `web/open-login.mjs` открывает
  видимое окно forta.chat и ждёт `matrixReady`, ключ вводит человек. Профиль и `node_modules` живут в scratchpad
  **старой** сессии (`c0c9244b…/scratchpad/web/`); ночная очистка стирает файлы старше ~3 дней — 2026-09-23 она
  выбила и сессию, и Playwright (переустановлен `npm install playwright@1`), профиль восстановлен входом владельца.
- Звонящий ищет собеседника по имени в списке: для Samsung в TEST2 это `PEER_NAME=test3232883282`
  (`web/call-peer.mjs`); настройки стенда — `scratchpad/bench.env`.
- Общие комнаты: Samsung ↔ TEST1 `!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app`; Samsung ↔ Pixel
  `!YJTutyRoEKqcowPZRk:matrix.pocketnet.app`. Комнату открывать через `chatStore.setActiveRoom(id)` по CDP, а не
  кликом по имени: список Samsung имя Pixel не показывает.
- Управление телефоном — `adb` и CDP к WebView (`adb forward tcp:9223 localabstract:webview_devtools_remote_<pid>`,
  `phone-eval.mjs`). Кнопки нативного экрана звонка на Samsung — по `uiautomator dump` и id (`btn_accept`,
  `btn_decline`, `btn_hangup`); на Pixel uiautomator экран звонка не читает — нажимать по доле экрана.
- Подводные камни: пока телефон заблокирован, экран звонка лежит поверх блокировки и «Домой»/«Недавние» не работают;
  Pixel поворачивается горизонтально на столе; всплывающее уведомление Forta перекрывает карточку звонка Bastyon; на
  Mac может кончиться диск из-за подкачки при полном прогоне тестов (`npm run test -- --maxWorkers=4`).

## Принятые решения владельца

- Ускорение ответа на входящий (0,5–1 с) — вернуться после пересчёта отчётов («Б»).
- Смена сети Wi-Fi → LTE и повтор отправки при ней — не проверять, записи закрыты.
- Второй входящий во время разговора — оставить «занято», ожидания вызова не делать.
- Убитый процесс (`kill -9`) посреди разговора — собеседник ждёт ~35 с; оставить как есть.
- Правило пушей `select_answer` и старые сборки Android на том же аккаунте (рвут принятые звонки, пока не
  обновятся) — риск принят, выпускать одним релизом (2026-09-19).
- #809 п. 1 (уведомление Forta поверх карточки звонка Bastyon) — оставить как есть (2026-09-18).
- Три записи о push-путях с несравнимыми id закрыты как недостижимые на этом homeserver.

## iPhone — с чего продолжать (2026-09-23)

Состояние на конец сессии, чтобы не проверять заново. Читать вместе с памятью `forta-chat-ios-bench.md`.

**Аппарат.** iPhone XR (iPhone11,8), iOS 17.3.1, coredevice `8C0187A2-3F6F-5733-93F7-DF8B044FE947`, udid
`00008020-001104C43A88003A`, спарен, Developer Mode **включён** (проверять `xcrun devicectl device info details
--device <id>` — поле `developerModeStatus`; отвечает устройство, не память). Forta на нём не стоит. В Safari вошёл
TEST3 (`test23438111`). `libimobiledevice` на Mac стоит (`ideviceinfo`, `idevicesyslog` — фильтр `-m` без
альтернатив, писать сырой лог и grep'ать).

**Trek А (Safari, отчёты #538/#1276) — закрыт.** Звонок веб TEST1 → Safari: входящий показан, «Принять», микрофон,
звук в обе стороны, владелец слышал. Особенность: спящая вкладка Safari (≈3 мин без экрана) звонки не принимает.
Подробности — `docs/call-bugs-needing-you.md`, раздел «iPhone, веб-версия в Safari».

**Trek Б (нативная запись «iOS: метка получает возраст…») — не начат, до устройства не хватает только подписи.**
Днём 2026-09-23: стена 4 закрыта (plist на месте), сборка под **симулятор iPhone 16 проходит целиком** (`xcodebuild
-project ios/App/App.xcodeproj -scheme App -destination 'platform=iOS Simulator,name=iPhone 16' build`, подписи не
надо), приложение стартует в симуляторе, Firebase Messaging поднимается. Попутно найден и исправлен дефект, который
ломал шаг «убить приложение → звонок → принять на CallKit» (`6f79bccc`): нативный VoIP-обработчик не сообщал CallKit о
звонке до `completion()` — запись «iOS: VoIP-push сообщает CallKit о звонке до `completion()`» в
`manual-verification.md`, проверяется только на XR. `xcrun simctl` / `devicectl` из песочницы Bash не работают
(CoreSimulator/CoreDevice XPC) — запускать вне её. Что сделано и что осталось, по порядку стен:
1. Xcode 16.4 стоит в `/Applications/Xcode.app` (15.4 → `/Applications/Xcode-15.4.app`), `xcode-select` на нём,
   лицензия принята, iOS 18.5 SDK скачан. Xcode 15.4 проект не собирает (SQLCipher.swift 4.14+ требует Swift 6).
2. Разрешение пакетов починено в репо (`74993ced`): `scripts/fix-ios-spm-products.mjs` после `cap sync ios`
   подставляет реальное имя продукта форка `llama-cpp-pro` (`LlamaCppCapacitor` вместо `LlamaCppPro`). Lock-файл
   пересобран Xcode 16.4 и закоммичен. Реальную ошибку SPM xcodebuild прячет — смотреть `swift package resolve` в
   `ios/App/CapApp-SPM` или `-verbose`.
3. ~~Форк не компилируется~~ — **закрыто 2026-09-23.** `LlamaCpp.swift:459` в `maxgithubprofile/llama-cpp-pro` звал
   `queryGpuInfo(nativeContextId)` без метки `contextId:` (Xcode 16.4 не собирает). По решению владельца сделан свой
   форк `j-bitmaker/llama-cpp-pro`: тег `v0.2.4-local-ai.2` = `v0.2.4-local-ai.1` + один коммит `70c9e217` с меткой
   (автор — `j-bitmaker`, как в forta.chat; ветка для PR в исходный форк — `fix/ios-query-gpu-info-label`);
   `package.json` и оба lock-файла указывают на него (коммит в этом репо — `chore(ios): take llama-cpp-pro…`).
   `npm install` теперь ставит рабочую копию, локальных правок в `node_modules` больше нет. Второй пакет с того же
   аккаунта, `local-ai`, не трогали.
4. ~~`GoogleService-Info.plist`~~ — **положен 2026-09-23** (`BUNDLE_ID` = `com.forta.chat`, `PROJECT_ID` =
   `forta-chat`, git игнорирует). Источник на будущее: 1Password «Forta» → `Forta iOS Firebase Config`; заглушку не
   класть — `FirebaseApp.configure()` в `AppDelegate` без условий.
5. ~~Нет сертификата подписи~~ — **закрыто вечером 2026-09-23, Forta 1.13.2 стоит на XR.** Что сработало:
   сертификат `Apple Development: Max Grishkov (9M84393HW3)` (до 5 авг 2027) экспортирован `.p12` из Keychain Access
   старого Mac Максима, импортирован в связку login здесь; Максим пригласил Apple ID владельца в команду через
   App Store Connect → Users and Access (роль Developer); после принятия приглашения
   `xcodebuild … -destination 'id=00008020-001104C43A88003A' -allowProvisioningUpdates
   -allowProvisioningDeviceRegistration build` сам зарегистрировал XR и выпустил `iOS Team Provisioning Profile:
   com.forta.chat` (до 2027-09-23); установка — `xcrun devicectl device install app --device 8C0187A2-… <App.app>`,
   запуск — `devicectl device process launch … com.forta.chat`. Ловушки: `find-identity -v -p codesigning` пишет
   «0 valid» из-за просроченного WWDR-корня 2023 г. в System keychain — реальная подпись работает (`codesign` даёт
   `TeamIdentifier=Y5JW9JU787`); `defaults read com.apple.dt.Xcode IDEProvisioningTeams` пуст, аккаунт Xcode 16 хранит
   в связке. Соглашение Apple Developer Program должен принять Daniel Satchkov **до 2026-10-02**, иначе выпуск
   профилей остановится. Ниже — старое описание стены:
   `security find-identity -v -p codesigning` → 0. Проект —
   автоподпись, Team `Y5JW9JU787`. Владельцу: Xcode → Settings → Accounts → «+» Apple ID → Manage Certificates →
   «+» Apple Development. Профили для App и двух расширений (NotificationService, ShareExtension) Xcode создаст при
   первой сборке на устройство.

**Вечер 2026-09-23, звонки на XR (8 входящих с веба TEST1 → TEST3 в Forta).** Найдено и исправлено:
`af8e3e5d` — четыре плагина таргета App не регистрировались в Capacitor 8 (`UNIMPLEMENTED`; микрофон не
запрашивался, принятый на CallKit звонок тут же отклонялся); категория аудиосессии теперь выставляется при загрузке и
`.mixWithOthers` (активация CallKit прерывала сессию WebKit GPU). После этого: CallKit показывает вызов, «Принять»
→ запрос микрофона → `m.call.answer` → ICE connected, звук с веба на iPhone идёт (70–80 КБ за 25 с).
**Открыто — микрофон iPhone молчит:** `[WebRTC-Diag] audio:0B/0pkt up` весь звонок, на вебе нет inbound-rtp.
Доказательство в `idevicesyslog`: `App(WebKit)[pid] … captureStateChanged … state was: 2048, is now: 0` и
`updateReportedMediaCaptureState: from 2048 to 8192` через 0,4 с после старта захвата (2048 =
HasActiveAudioCaptureDevice, 8192 = HasMutedAudioCaptureDevice), обратно не переходит до конца звонка. Не зависит от
нашей `setActive(true)` (проверено без неё, звонок 8), не совпадает с потерей видимости (RunningBoard:
`running-active-Visible`). В Safari на том же XR микрофон работал (трек А). Что проверять дальше, по порядку:
1. Исходящий звонок с iPhone (без CallKit): веб-автоответ `answer-loop.mjs` (RUN_S=150), владелец звонит `test3823818`
   из Forta. Если байты пойдут — виноват путь CallKit; попытка 2026-09-23 не состоялась (владелец не набрал).
2. В консоли приложения после `pushLocalFeed` вывести `track.muted / enabled / readyState` и повесить `onmute` на
   локальный трек — точное время и WebKit-причина мьюта (нужен `cap:build:ios`).
3. Настройки WKWebView через `MainViewController.webViewConfiguration(for:)` / `WKPreferences` — искать флаги про
   capture/visibility/interruption (перечисление `_experimentalFeatures`/`_internalDebugFeatures` на Mac).
4. Сравнить с Safari: там захват в том же WebKit GPU-процессе не мьютится.
**Ночь 24.09, что дал шаг 1:** исходящий звонок с iPhone (Forta открыта, CallKit не участвует) — **микрофон работает**,
веб получил 39 КБ за 27 с (`runs-ios10-answer.jsonl`). Значит захват WKWebView исправен, мьют — только на пути
ответа через CallKit. Попробован возврат захвата из нативного кода: `bridge?.webView?.setMicrophoneCaptureState(.active)`
из `IOSCallAudio.start()` с повторами 0/0,5/1,5/3/6 с — `microphoneCaptureState` был `.muted`, вызов прошёл и
дальше состояние читалось как активное, **но RTP по-прежнему 0 байт** (звонок 12, консоль `xr-console-6.log`). Код не
закоммичен (не помогает). Попутно: один звонок сорвался на `PUT m.call.answer` → `fetch failed: Load failed`
(сетевой процесс WebKit в момент переключения на экран вызова; SDK ответ не повторяет — отдельная задача);
`voipTokenReceived` в консоли ни разу не появился — VoIP-пуш и пробуждение свёрнутого приложения не проверены;
без открытого приложения входящий вызов до iPhone не доходит. Что дальше по микрофону, по порядку: (а) после
«Принять» на CallKit сразу открыть Forta (иконка на экране вызова) — если байты пойдут, WebKit ждёт активности
приложения, и лечить надо тем, чтобы CallKit-ответ выводил приложение на передний план; (б) в JS после
`pushLocalFeed` логировать `track.muted/enabled/readyState` и `onmute/onunmute`, и по `appStateChange → active`
делать `getUserMedia` заново + `sender.replaceTrack` — обходной путь, если (а) подтвердится; (в) сравнить
`WKPreferences` (`_experimentalFeatures`/`_internalDebugFeatures`, ключи про capture/visibility) — перечисление на Mac
через `swiftc` не собралось, доделать. `idevicesyslog` умирает вместе с обрывом соединения `devicectl` — проверять
`tail -1` файла перед каждым звонком и перезапускать.

Инструменты: консоль JS — `xcrun devicectl device process launch --console --terminate-existing --device <id>
com.forta.chat > файл &` (Capacitor выводит `⚡️ [log]`), системный лог — `idevicesyslog -u <udid> > файл &` (очень
шумный, grep по `App(WebKit)`, `audiomxd(MediaExperience)`, `callservicesd`). Режим «Не беспокоить» на XR должен
быть выключен — иначе `callservicesd` отбрасывает `reportNewIncomingCall` (`CallKit.error.incomingcall Code=3`).
Pixel всё ещё в TEST3 (party `EMRXW` отклонял пробные звонки) — выйти.

Когда 5 будет: `npm run cap:build:ios`, затем сборка на XR
`xcodebuild -project ios/App/App.xcodeproj -scheme App -destination 'id=00008020-001104C43A88003A' -allowProvisioningUpdates build`
и установка `xcrun devicectl device install app --device <coredevice id> <путь .app>`. Вход в аккаунт на iPhone —
владелец (TEST3 свободен, если выйти из него в Safari; TEST1 — веб на Mac, TEST2 — Samsung). Шаги записи: звонок с
Samsung → отклонить на CallKit → через >60 с позвонить из той же комнаты → должен зазвонить; убить приложение → звонок →
принять на CallKit. Логи — `idevicesyslog`.

**Прочее.** На Pixel остался вход в TEST3 — выйти, иначе два клиента делят аккаунт. Стенд звонков: Samsung = TEST2
(`test3232883282`), веб-профиль TEST1 в scratchpad старой сессии `c0c9244b…/scratchpad/web/` (ночная очистка
убивает его раз в ~3 дня; вход через `open-login.mjs`, ключ вводит владелец; там же `call-peer.mjs`,
`reject-probe.mjs`, `probe-login.mjs`).

## Открытые задачи

### Нужно решение владельца

1. **#809 п. 2 — Forta звонит, пока человек уже говорит в Bastyon.** Владелец выбрал вариант А (2026-09-18), сделано:
   правило пушей `com.forta.call.select_answer` + `SelectAnswerPolicy`. Шлюз пушей `selected_party_id` не передаёт
   (`saprobe3`), поэтому «кто ответил» решается по слоту Telecom, бэкенд не нужен. Ответ на этом же телефоне проверен
   (`sahere1`), ответ в Bastyon — тоже (`ownerb3`: рингер Forta снят через 28 мс после пуша); счётчик в списке чатов после такого звонка — 1, только приглашение
   (`ownerb4`). Всё проверено — запись «Forta перестаёт звонить, когда
   на звонок ответили на другом устройстве» в `manual-verification.md`. Риск для релиза: старая сборка Android на том
   же аккаунте примет этот пуш за отбой и будет рвать принятые звонки.
2. **#809 п. 1 — уведомление Forta перекрывает карточку звонка Bastyon.** Решение владельца 2026-09-18: оставить как
   есть, известная особенность. Владелец сам наткнулся на неё в `ownerb1` (ответил в Forta вместо Bastyon).
3. **#1091** — удаление записей о звонках в Forta. Это не ошибка звонков, а просьба о функции; решить, брать ли.

### Можно делать без владельца

4. **«Поздний stop прошлого звонка не гасит следующий»** (`706e8f96`) — проверено 2026-09-18 (`redial2`–`redial6`).
   На живой странице гонка на Samsung недостижима, поэтому её внедряли задержкой `dismissCallUI` по CDP; защита
   сработала, попутно найден и починен остаток — поздний `dismissCallUI` закрывал экран нового звонка.
5. **Передача файлов через Tor** — починено и проверено 2026-09-19: плагин ходил к прокси через `CONNECT`, а
   отправка больших файлов падала на `No access token` ещё до плагина. Запись «Файлы отправляются и скачиваются при
   включённом Tor (Android)» в `manual-verification.md`. Осталось замечание про индикатор отправки (100 % сразу).

### Нужен владелец, устройства есть

6. **Samsung с PIN** — ответ на заблокированном телефоне и после смахивания проверен 2026-09-21 (`lockA1`, `lockB1`).
   Строка `REVOKED` («Full-screen intent…», шаг 2): плагин при выключенном разрешении отдаёт `allowed:false`, но
   отправить настоящий отчёт из локальной сборки нельзя — `VITE_BUG_REPORT_TOKEN` есть только в секретах CI. Остаток
   закрывается вместе с п. 8 сборкой из CI.
7. **AirPods** — сессия 2026-09-22 (`air2`–`air4`): ответ с ножки, «Динамик» ↔ AirPods и осиротевший слот прошли;
   завершение с ножки на связке AirPods + Samsung не доходит до Android (гудки отказа гарнитуры), закрыто как
   недостижимое.
8. **Отправка настоящего отчёта о баге — нужна сборка из CI** (`android-test-apk.yml` на ветке или релиз): «Таймлайн
   аудио доезжает в реальном отчёте», строка `Tor` и шаг 1 «Фактов ICE и Tor в отчёте», строка `REVOKED` из п. 6 и
   новый раздел «Encryption diagnostics».
9. **TURN на 443 по TLS** — настройка сервера; шаг 3 «Фактов ICE и Tor».
10. **Пуш и релиз.** Запушить незапушенные коммиты ветки, выпустить сборку, через неделю пересчитать отчёты о звонках
    (`scripts/triage-call-reports.mjs`, см. `forta-chat-call-report-coverage.md`). После релиза — «Пустые каналы
    уведомлений не возвращаются после обновления» (пара «старый релиз → релиз из CI»).
11. **E2E** — закрыто 2026-09-23: 4/4 однодевайсных сценария на Pixel 9 (`docs/call-bugs-needing-you.md`, раздел H).
    Запускать с `DEVICE=<serial>`: без него скрипт берёт первое устройство и стирает на нём сессию (`clearState`).

### Нужны другие устройства или сеть

12. **Xiaomi, Honor или realme:** «Разговор переживает завершение предыдущего звонка» и 49 отчётов группы B.
13. **Телефон с жалобой на звук:** «Переключатель движка WebRTC меняет поведение».
14. **Сеть, где блокируют Tor:** snowflake проверен 2026-09-21 (сеть стенда в тот день блокировала прямой Tor);
    obfs4 из приложения не выбрать. Бинарник Tor предупреждает, что версия устарела.
15. **iPhone** — 2026-09-23 подключён iPhone XR (iOS 17.3.1, Developer Mode по данным `devicectl` выключен). Два трека:
    - трек А, Safari — **пройден 2026-09-23**: на iOS 17.3.1 входящий показывается, «Принять» работает, микрофон
      выдаётся, звук в обе стороны (#538/#1276 не воспроизводятся; подробности в `call-bugs-needing-you.md`).
      Найденная особенность: спящая вкладка Safari звонки не принимает (JS усыплён, sync стоит);
    - трек Б, нативная запись «iOS: метка получает возраст…» — нужна сборка на устройство. **Xcode 15.4 проект не
      собирает**: `@capacitor-community/sqlite` 8.1.1 тянет `SQLCipher.swift` 4.14+, а все его теги 4.14.0–4.19.0
      требуют `swift-tools-version: 6.0` (Swift 5.10 в 15.4 их не читает); пин не поможет. Нужен **Xcode 16+**, как и
      сказано в `docs/ios-local-build.md`. `libimobiledevice` на Mac поставлен (`idevicesyslog`).
      **Дальше 2026-09-23:** владелец поставил Xcode 16.4 (`/Applications/Xcode.app`, 15.4 → `Xcode-15.4.app`,
      `xcode-select` переключён), iOS 18.5 SDK скачан (`xcodebuild -downloadPlatform iOS`, Apple ID не нужен). Вторая
      стена — форк `llama-cpp-pro` объявляет продукт `LlamaCppCapacitor`, а `cap sync ios` просит `LlamaCppPro`;
      починено `74993ced` (`scripts/fix-ios-spm-products.mjs` после sync). Разрешение пакетов проходит. Для сборки на
      XR не хватает только сертификата подписи (Apple ID → Manage Certificates); Developer Mode на XR включён.
16. **Не-Samsung телефон со старой установкой:** «В „Аккаунтах вызовов“ значится Forta Chat» (Samsung такие
    аккаунты скрывает; нужно обновление поверх старой версии — после релиза).

### Низкая ценность

17. **«Зависший звонок отпускается при возврате»** — закрыто решением владельца 2026-09-21.
18. **Обновить бинарник Tor (плановая, без срока).** `libtor.so` пишет `This version of Tor will eventually stop
    working`: когда сеть сделает недостающий протокол обязательным, встроенный Tor перестанет подключаться у всех.
    Пересобрать под четыре ABI и заново проверить snowflake.

## Найдено и починено в этой сессии (для справки)

Подробности и замеры — в «Проверено» `docs/manual-verification.md`.

- Утечка `AudioContext` замораживала звонки дольше минуты и теряла отбой (`e137334a`).
- Прокси нативного WebRTC: кандидаты в ответе и откат при встречном перезапуске ICE (`6b1b88cc`, `c2ed5f98`).
- Отбой из нативной части при смахивании посреди разговора, через Tor — как весь трафик приложения (`696470e2`,
  `6c459594`, `5dbad8be`); плюс правки другой сессии `e24045ae` (только HTTPS для адреса сервера).
- Повторный звонок после отмены звонящим и после отклонения по таймауту (`e059cc1e`, `ffeb0a46`).
- Размер видео в веб-версии (#936, `a549d635`), повторная регистрация плагина Tor каждые 2 с (`fb151495`).
