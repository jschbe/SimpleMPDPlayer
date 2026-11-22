# Localization — M7.2

## Implemented status

M7.2 adds seven supported app languages: en/de/fr/it/es/uk/ru, with complete
unqualified English fallback and the six supplied translations. There are now
**196 strings and one plural name (197 names / 198 English CSV rows)**.
`strings-en.csv` remains the canonical English review catalog; 19 new language/
About/legal-navigation strings were appended with context. The earlier context
line references below describe the preserved M7.1 audit snapshot, not current
line numbers. Original English/source wording and existing translation wording
were retained.

Settings → Language offers System default plus native language names. It uses
AppCompat/platform per-app locales (AndroidX auto-storage below API 33; Android
system store on 33+). App first launch leaves the override empty, and returning
to System default clears it. The resource declaration and APK language filter
both contain exactly the seven supported tags; unsupported requests use normal
Android fallback to English. Regional locale variants and multiple device
languages follow Android resource resolution; the app does not force English
because an earlier device configuration used it. See [architecture](ARCHITECTURE.md).

All six supplied XML catalogs initially matched all 178 original resource names
and placeholder signatures. XML/AAPT2 compilation found no escaping or format
errors. The only changes to existing resources were adding `many` to French,
Italian and Spanish `timer_duration_minutes`, as required by Lint/CLDR; each uses
the existing plural wording. That category covers million-value cases outside
the unchanged 1–1440 timer range. Russian/Ukrainian already supplied correct
one/few/many/other branches. German wording was preserved. All 19 new entries
are translated in all catalogs (native autonyms, SPDX identifier and URL remain
literal by design).

App-generated estimated deadlines use context-based Android date/time formatters
with the app resource locale and device 12/24-hour setting. Playback durations,
ASCII custom-minute entry, technical ports, protocol data and metadata stay
unchanged. The service refreshes localized subtitle/provider resources without
restarting HTTP/ExoPlayer/session. Android and Media3 still own stock/accessibility/
notification controls; OS/OEM presentation can follow the system locale.

About supplies localized navigation to the build version, GPL-3.0-or-later,
complete offline GPL and third-party notices, and selectable public source URL.
Original legal texts are preserved in English. See [license audit](LICENSING.md).

Automated coverage is described in the M7.2 verification section of
[architecture](ARCHITECTURE.md): JVM XML/CSV/type/argument checks, actual Android
language selection/restoration/external changes, plural rules, About/legal text,
large-font translated screens and retained live-fixture media/timer state.

## Historical M7.1 inventory

The following inventory records the pre-implementation source audit. Statements
about future localization work, counts, paths and verification below refer to
that historical snapshot; the implemented status above supersedes them.

Status: source audit on 2026-11-22. This milestone adds documentation and an English review export only. It does not add translations, change resources or layout, select a language, or change MPD, Media3, timer or persistence behavior. English remains the app's existing unqualified fallback. This inventory does not establish Google Play publication readiness or supply store-listing copy.

## Scope and canonical source

The shipping source/resource audit covers all 29 Kotlin files under `app/src/main`, all 26 Android XML files including the manifest, the 12 raster launcher resources and the remaining application configuration. The vector/adaptive/raster artwork contains no embedded English labels. All 34 Kotlin test files and the two Android-test assets were also scanned; fixture names, assertions, test-only screens and generated MPD responses are not shipping copy. There are 104 files under `app/src` in this snapshot. Build outputs, generated resource symbols and third-party source are not additional app-authored strings.

The canonical editable English source is [`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml). It contains **177 string resources and one plural resource with two English forms**, or **178 resource names / 179 review rows**. Every resource has a production reference, including conditional error and accessibility mappings. There are no other app-owned string arrays, locale-specific resource directories or XML layout texts in the current source. The manifest application/activity labels both refer to `app_name`; screens use Compose.

[`strings-en.csv`](strings-en.csv) exports all 179 app-owned English forms, with exactly `key,english,context` as its columns. The full catalog below contains the same source forms, meanings, argument contracts and display constraints. Inherited library and Android text is inventoried separately below because it must not be translated as app-owned copy.

### CSV conventions

- Encoding is strict UTF-8 without a BOM, with LF line endings and a final newline. Use an explicit UTF-8 CSV import in spreadsheet software.
- Commas delimit columns. Embedded commas and quotation marks are escaped by standard CSV double quoting; quoted cells must be read with a CSV parser, not split on commas. The current file has 180 physical lines including its header and no embedded cell newlines.
- Ordinary keys are the exact Android resource names. `timer_duration_minutes[one]` and `timer_duration_minutes[other]` identify quantity branches of `R.plurals.timer_duration_minutes`; bracketed keys are review identifiers, not new Android resource names. Translation work may later require additional quantity branches.
- `english` contains the unformatted, displayed English template. It retains `%1$s`, `%1$d`, `%2$d` and `%2$02d` exactly, but decodes XML entities and Android literal escaping. It is not a paste-ready XML serialization.
- Specifically, `metadata_separator` is a comma followed by one ASCII space, with no quotation marks in the value. `delete_server_confirmation` displays quotation marks around `%1$s`, without the XML backslashes. Preserve separator whitespace during review/import.
- `context` includes meaning, current source-use filenames/line numbers, placeholder/plural details and layout constraints. Line references describe this snapshot and must be refreshed after source changes. Context text is reviewer guidance, not UI copy.
- Keys and English source cells are read-only inputs to translation review. No target-language text or future language-picker strings have been invented in this export.

## Surface coverage

Paths in this table are relative to `app/src/main/java/com/github/jschbe/simplempdplayer`, except the manifest.

| Surface | Source | Covered text and data |
| --- | --- | --- |
| Launcher / Android app settings | `AndroidManifest.xml` | Application and activity labels use `app_name`; no hardcoded manifest label. |
| Main navigation, Settings and connection header | `ui/ServersApp.kt` | Browser/Player/Queue tabs, Servers, Sleep timer, Appearance, Back, selected-server prompt/checkmark, reconnect/recovery/source/error/stale-state messages, LAN-permission action, theme dialog. |
| Toasts | `MainActivity.kt` | `mpd_available`, `mpd_unavailable`, `connected`, `connection_failed`; all use resource IDs. No raw network exception is shown. |
| Player | `ui/PlayerScreen.kt` | Empty/current-song fallback, playback state, durations, MPD volume, command progress, Random, Repeat and external/unknown states, local-listening information/status/retry/errors, timer attention hint. |
| Player accessibility | `ui/PlayerScreen.kt` | Play/Pause/Stop/Previous/Next descriptions, Random description, Repeat's localized state, metadata Expand/Collapse action and state, listening-info description. Decorative icons deliberately have null descriptions. |
| Queue | `ui/QueueScreen.kt` | Count, empty/unavailable state, Clear confirmation, Move up/down/Remove, track-actions description. Track titles/artists and durations are dynamic data or shared format resources. |
| Browser | `ui/BrowserScreen.kt` | Library root/path, Up description, loading/empty/unsupported/error states, Folder label, file/folder queue-actions description, Append/Replace menus/confirmation and all partial/uncertain-operation feedback. |
| Server management | `ui/ServerManagement.kt` | Add/Edit/Delete profile dialogs, field labels, Host placeholder, validation, Test/Save/Cancel, probe progress, active marker, profile endpoint, drag/Edit/Delete descriptions, settings-storage errors. |
| Sleep timer | `ui/SleepTimerDialog.kt` | Dialog title, enable state, bound target, waiting/countdown/due/terminal states, duration/custom choices, both duration plural forms, validation and help, Android scheduling/delivery/storage errors, Done/Retry. Also Player compact timer text. |
| MediaSession / notification | `audio/LocalStreamService.kt`, `audio/MpdSessionPlayer.kt` | App-provided `stream_notification_status` containing shared MPD state text; shared metadata separator; title/artist/album/server fallback remain dynamic. Media controls and channel labels are inherited library/system text, listed below. |
| Validation / status producers | `ServersViewModel.kt`, `data/*`, `mpd/*`, `timer/*`, `audio/LocalStreamController.kt`, `audio/MpdMediaTransport.kt` | Emit typed states/errors and data. UI maps these to resources; enum names and internal diagnostics are not user copy. |
| Theme / other XML resources | `ui/theme/*`, `res/values/{colors,themes}.xml`, `res/drawable/*`, `res/mipmap-*`, `res/xml/*` | Colors, typography, icons, network/backup configuration; no additional app-owned visible prose. |

### Dynamic user/server content

These visible values are covered as data sources, not translation units:

| Value | Origin / fallback | Current display constraint |
| --- | --- | --- |
| Server display name | User profile; header, dropdown, server list, timer target; notification title fallback when no song. | Header/server-list name: one line with ellipsis; other surfaces depend on layout/OS. No app-imposed name character cap; validation rejects blank names/control characters. |
| Host/IP and ports | User profile; Host input/placeholder and `server_endpoint`. | Inputs are single-line; endpoint summary is at most two lines with ellipsis. DNS validation checks the ASCII hostname length (up to 253 after normalization); port range is 1-65535. These are technical input rules, not translation length budgets. |
| Current/queued track title | MPD Title tag; filename after the last `/` when Title is missing/blank; full path fallback if needed. | Player: up to four title lines expanded / two collapsed; Queue/Browser: two lines, ellipsis. MediaSession uses the same metadata, with OS-dependent truncation. |
| Artist / album lists | MPD repeated Artist/Album tags joined with `metadata_separator`; missing tags omitted. | Player metadata can wrap; Queue artist: one line with ellipsis; Browser artist: two lines with ellipsis. OS chooses media-metadata rendering. |
| Folder/file title and relative path | MPD directory/file URI, filename or Title metadata. | Browser title: two lines; path heading: three lines; ellipsis. Never translate, recase or modify the path sent to MPD. |
| Numeric values | Queue count, remote volume, time position/duration, retry usage, selected minutes, port defaults. | Use the existing resource/input contract. Numeric bounds, rounding and protocol values are not translatable prose. |

## Hardcoded Kotlin and XML findings

**No hardcoded app-owned English UI sentence, button label, toast, notification subtitle or accessibility description needs moving to `strings.xml` in this snapshot.** All such text already uses `stringResource`, `pluralStringResource`, `getString`, a resource-ID toast, or an explicit resource mapping. No resource extraction is proposed for this documentation-only milestone.

The English-looking literals below are internal diagnostics, not hidden UI strings. Keep them outside the translation catalog and never expose their messages as UI errors:

| Source | Literal | Current handling |
| --- | --- | --- |
| `mpd/MpdClient.kt` | `Client already connected` | Programming-state guard; not a user-facing recovery message. |
| `mpd/MpdClient.kt` | `Not connected` | Private IO diagnostic at read/write guards; connection errors are sanitized/mapped. |
| `mpd/MpdClient.kt` | `Connection lost` | EOF diagnostic mapped to `ConnectionError.LOST` and `connection_lost`. |
| `mpd/MpdClient.kt` | `MPD operation timed out` | Timeout diagnostic mapped to `connection_timed_out` or the timer's timeout resource. |
| `mpd/MpdProtocol.kt` | `Invalid MPD response` | Typed protocol error mapped to `invalid_mpd_response` or `timer_error_protocol`. |
| `mpd/MpdProtocol.kt` | `MPD rejected a command` | Typed ACK error mapped to `mpd_rejected` or a timer/browser-specific resource. |

Also excluded from extraction: MPD command/field/state tokens (`repeat`, `single`, `oneshot`, `random`, `pause`, `ACK`, etc.), UTF-8/protocol grammar and escape sequences, package/intent/channel/storage keys, resource names, test tags, URI separators, empty input values, and numeric default inputs `6600` / `8000`. Their spelling is technical behavior, not English UI copy. Default ports currently exist in both models and input initialization and should not become localized text resources. Test-only English assertions and fixture metadata must remain separate from shipped copy; localization-aware tests can be planned in the later implementation milestone.

### Inherited notification and accessibility text

`LocalStreamService` uses the existing Media3 default notification provider; it does not create custom notification actions or a custom channel label. Inspection of the locally resolved Media3 Session **1.11.1** AAR and `DefaultMediaNotificationProvider` confirms these inherited resources:

| Dependency resource key | Canonical English | Context / ownership |
| --- | --- | --- |
| `default_notification_channel_name` | Now playing | Media3 notification channel name, potentially visible in Android notification settings. No app override. |
| `media3_controls_play_description` | Play | Media3 notification/media action description; distinct ownership from app `play`. |
| `media3_controls_pause_description` | Pause | Media3 notification/media action description; distinct ownership from app `pause`. |
| `media3_controls_seek_to_previous_description` | Seek to previous item | Previous-track media action, not a position seek in this app. |
| `media3_controls_seek_to_next_description` | Seek to next item | Next-track media action, not a position seek in this app. |

The same AAR also contains `media3_controls_seek_back_description` (Seek back) and `media3_controls_seek_forward_description` (Seek forward), but this app does not expose those actions. These are not new app translation tasks. The OS may replace or omit notification controls/subtitle according to Android/OEM/controller behavior; there is no fixed app length limit for this surface.

The resolved Compose UI/Material 3 resources provide stock text for the existing fields, dialogs, popups, tabs, switches, sliders and progress indicators. Relevant defaults are listed for accessibility review; the actual spoken combination depends on the widget state, Android and the screen reader, and is not app-authored copy:

| Dependency resource key(s) | English default | Current context |
| --- | --- | --- |
| `default_error_message` | Invalid input | Default error semantics on invalid text fields, alongside app-specific validation. |
| `default_popup_window_title`, `dropdown_menu` | Pop-Up Window; Dropdown menu | Generic popup/menu window semantics. |
| `in_progress` | In progress | Indeterminate progress indicators. |
| `selected`, `not_selected` | Selected; Not selected | Tab/menu/radio selection semantics. |
| `state_empty`, `state_on`, `state_off` | Empty; On; Off | Text-field/switch state semantics. |
| `switch_role`, `tab` | Switch; Tab | Stock widget-role wording. |
| `template_percent` | `%1$d percent.` | Stock progress/range accessibility value; this is not the visible `mpd_volume` label. |
| `m3c_dialog` | Dialog | AlertDialog pane semantics. |
| `m3c_dropdown_menu_expanded`, `m3c_dropdown_menu_collapsed` | Expanded; Collapsed | Exposed duration-dropdown state; separate from app track-information state resources. |
| `m3c_dropdown_menu_toggle` | Toggle dropdown menu | Exposed duration-dropdown accessibility action. |

Android/TalkBack additionally owns role names and interaction hints such as button/edit-field announcements, keyboard/IME Done, text selection/Copy/Paste, permission prompts, app/notification settings, and lock-screen/media/output-device UI. Their exact copy is OS/library-version dependent and should be reviewed on the target OS rather than copied into this app's catalog. Unused dependency strings for date/time pickers, search, bottom sheets, range sliders and other unimplemented widgets are not shipping app translation units. Do not translate libraries by shadowing their resource keys in `res/values` during later app translation work.

## Locale-sensitive formatting audit

All findings here are implementation follow-up items; current formatting is unchanged.

| Value / call site | Current behavior | Later localization decision / verification |
| --- | --- | --- |
| Track elapsed/total duration: `PlayerScreen.TimeText`, reused in Queue/Browser | Truncates nonnegative seconds to whole seconds; formats total minutes and zero-padded seconds using `time_minutes_seconds`. For long tracks minutes can exceed 59 and two digits. | Preserve duration semantics and `%2$02d` padding. This is not a clock time. Review digits and punctuation under the effective app locale without changing MPD position calculations. |
| Timer remaining: `SleepRemaining` | Rounds remaining milliseconds up to minutes. Compact form uses total minutes; dialog splits hours and minute remainder. Zero remaining uses `timer_due`, not a fabricated completion. | Keep the same rounding, boundary and inexact-delivery meaning. Decide invariant localized unit abbreviations versus future quantity-aware unit formatting. Two independent unit quantities cannot be solved by translating one English singular/plural pair. |
| Timer duration/presets: `pluralStringResource(timer_duration_minutes, minutes, minutes)` | The quantity selects a branch and the same integer fills `%1$d`; custom range 1-1440, presets 15/30/45/60. | Supply target-language plural branches, including a correct one-minute custom case; preset-only checks would miss singular. |
| Estimated deadline: `DateFormat.getDateTimeInstance(SHORT, SHORT).format(Date(...))` in `SleepTimerDialog` | Java default locale/time zone chooses date order and clock pattern. It does not receive the app's resource locale or a Context-based 12/24-hour choice explicitly. | An explicit app-language override must define the relationship between UI locale, date/number locale, device zone and Android 12/24-hour preference. Do not assume a future resource override changes Java's process-default locale. |
| `%d` resource formatting: volume, counts, retry usage, durations and endpoint port | Formatted through Android/Compose resource formatting; templates request integers without grouping flags. Formatting can depend on the resource configuration locale. | Align resource/plural/numeric formatting with the later effective app locale. Keep technical port digits unambiguous; do not add thousands separators to a port or time component. |
| Timer custom input | Initialized with `Int.toString()`. `sleepMinutes` explicitly accepts only ASCII digits `0`-`9` and a whole integer 1-1440; spaces/grouping/decimal fractions are rejected. | A translated keyboard may offer other digits. Decide whether to keep/document ASCII-only entry or make a separately tested parser change later; translations alone cannot change accepted input. |
| Server port inputs | Initialized from numeric defaults/profile `toString()`; parsed with Kotlin integer parsing, not a locale-aware NumberFormat. | Keep default values and 1-65535 validation. Verify input/keyboard digit behavior separately before claiming localized-number input support. |
| Metadata joining | Shared `metadata_separator` joins artist/album tags, including in MediaSession. | Review punctuation and intended whitespace; do not translate the metadata itself or add a sentence-conjunction algorithm in this milestone. |
| Dynamic names, URIs and folder ordering | Names remain user/server data. Browser sorts directories first, then `title.lowercase()` and URI, rather than locale-aware collation. | Do not recase/translate protocol identities. Locale-aware display sorting would be a separate behavior change, not a string translation. |
| MPD wire data and persisted settings | Protocol decimals/integers and stored enum/UUID/timestamp/port representations use machine parsing/serialization (`toString`, `toDoubleOrNull`, etc.). | Keep these locale-invariant technical representations separate from display formatting; never translate protocol tokens or DataStore keys/values. |
| MediaSession subtitle / stock controls / notification channel | App subtitle is built with service `getString`; action/channel text comes from Media3, while OS renders the media UI. | Later language switching must refresh retained metadata and verify service/Application contexts and an already-created channel's displayed name, without sending MPD commands or restarting local audio solely for translation. This behavior is not implemented or verified here. |

### Plural requirements

The only current quantity resource is `timer_duration_minutes`. English has `one` and `other`. Select plural forms using the target locale's rules rather than testing `count == 1`; a category name is a grammatical category, not a literal-number condition. Android's [string-resource documentation](https://developer.android.com/guide/topics/resources/string-resource#Plurals) describes quantity resources and formatted arguments.

| Target | Cardinal categories to consider when translation is implemented |
| --- | --- |
| English `en`, German `de` | `one`, `other`. |
| French `fr`, Italian `it`, Spanish `es` | `one`, `many`, `other` in current CLDR; `many` covers large-value cases outside this timer's 1-1440 range. Keep an `other` fallback and review the Android versions actually supported. |
| Ukrainian `uk`, Russian `ru` | `one`, `few`, `many`, `other`; important integer examples include 1, 2, 5, 11, 21, 22 and 25. |

These review categories follow [Unicode CLDR cardinal plural rules](https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html); installed Android versions can use different CLDR revisions. Use Android's selection rather than duplicating rules in Kotlin.

`queue_count` currently uses a label followed by a count. A target-language wording that requires an inflected noun after the number needs a future plural-resource and call-site change; adding unreferenced plural branches to this CSV would not fix it. The abbreviated `timer_compact_remaining` and `timer_remaining` likewise need either invariant localized abbreviations or an explicit later formatting change. `connection_recovering` refers to a fixed total of five retries; its one placeholder is the number of attempts used. Literal validation bounds (1, 1440, 65535) and retry total (5) must retain their numeric meaning.

## Translation conventions and later language selection

| Language | Language tag | Planned resource destination, not created in M7.1 |
| --- | --- | --- |
| English, fallback | `en` | Existing unqualified `res/values/strings.xml`; no duplicate English catalog required now. |
| German | `de` | `res/values-de/strings.xml` |
| French | `fr` | `res/values-fr/strings.xml` |
| Italian | `it` | `res/values-it/strings.xml` |
| Spanish | `es` | `res/values-es/strings.xml` |
| Ukrainian | `uk` | `res/values-uk/strings.xml` |
| Russian | `ru` | `res/values-ru/strings.xml` |

Language selection is future work: System default plus an explicit app-language override. No language picker, supported-locale configuration, persistence, service-locale update or language metadata is added now. `theme_system` means system **appearance** and must not silently become a language preference. Regional variants, tone and language-name display conventions still need review before translation commissioning.

- Preserve stable keys and placeholder argument numbers/types. Translators can reorder indexed arguments to fit grammar; do not replace `%1$d` with `%1$s` or drop zero padding. Keep complete phrases in resources and avoid building sentences from translated fragments. `repeat` and `stream_notification_status` deliberately receive already-localized status labels.
- Translate descriptions, state/action semantics, warnings, validation and rare failure paths with the same care as visible labels. Do not shorten spoken descriptions to meet a visual width. Do not add descriptions to decorative icons merely to create another translation key.
- Use concise, consistent sentence-style UI wording. Match the meaning of navigation destinations and button references inside help/errors; do not promise successful delivery where the source says uncertain, or automatic playback/retry where it says none.
- Keep `SimpleMPDPlayer`, MPD, HTTP, Android and Bluetooth as product/protocol names; keep the configuration token `httpd`, addresses, paths, ports, UUIDs and server metadata literal. A future source-resource cleanup could explicitly mark `app_name` and `host_placeholder` non-translatable, but no resource attributes are changed here.
- Preserve the distinction between remote MPD transport/volume/queue, local HTTP reception/phone volume, and the independent timer. Random and Repeat remain independent, including Single plus Random. Translate mode labels by meaning, never raw wire tokens.
- Copy English ellipsis `…`, middle dot `·`, arrows and quotes according to target-language typography while preserving their role. A locale-specific separator is possible for `metadata_separator`, but trailing whitespace is intentional. On later XML import, escape quotes/apostrophes and XML-special characters correctly; CSV quoting is a different layer.
- No current app string has an enforced character-count maximum. The catalog records actual line limits, ellipsis, compact controls and OS-managed surfaces instead of inventing character budgets. Later visual acceptance should cover expanded translations, narrow screens, large font settings, accessibility speech and notification/lock-screen rendering. Current English layout tests are not evidence of translated-layout readiness.
- Keep the default English resource set complete. Missing translations must retain fallback behavior; library/system strings remain owned by their providers. Future language-only UI updates must not trigger a reconnect, playback/queue mutation, timer reset or audio-authorization change.

## Terminology and open review decisions

No translation choices are resolved by changing the English source in this milestone.

| Term / source keys | Established meaning | Open translation-review question |
| --- | --- | --- |
| Queue — `queue`, `repeat_queue`, queue actions/count | The current server's ordered playback queue. MPD stored playlists are separately unsupported. | Choose a consistent music-playback term, avoiding a saved playlist or a generic task/job queue. Navigation and Repeat may require different grammar despite identical English. |
| Browser — `browser`, `library_*` | Browse folders in MPD's music database. | Use a music/library browsing term rather than imply a web browser. |
| Random — `random` | MPD-controlled shuffled/random playback order; independent of Repeat. | Decide whether the target's standard music-player term corresponds to Random or Shuffle; retain the source key and independent behavior. |
| None / Queue / Single — `repeat_*` | No repetition / repeat entire queue / repeat current track. | Single must mean current-track repetition, not one play, one queue item or stopping after it. |
| One-shot / Stop after track — `repeat_oneshot`, `repeat_stop_after_track` | External MPD states displayed read-only until an explicit mode choice. | Short, distinct wording must not collapse these into supported Single. MPD's literal `oneshot` token itself is not translated on the wire. |
| Sleep — `sleep_timer`, `timer_compact_*`, `timer_remaining` | At expiry the app sends MPD Pause; the phone does not sleep and MPD is not stopped/shut down. | Decide the natural local timer term and compact countdown/off wording without implying device sleep. |
| Playing / Paused / Stopped versus Receiving / Ready / Buffering | Confirmed MPD state versus local HTTP receiver/decoder state. | Keep distinct verbs; receiving bytes is not proof of audible playback. Pause is a remote action in timer messages. |
| Host / Name / MPD port / Stream port | Technical host/address, profile nickname, command TCP port and HTTP audio port respectively. | Decide whether Host needs an established technical term or a clearer label. Do not merge Name with hostname, or Stream port with command port. |
| Done / Save / Test / Retry | Done closes/applies the relevant dialog; Save persists and activates a profile; Test only probes; Retry depends on the error context. | Shared Done has both apply-and-close and close-only contexts. Split resources later only if target-language semantics actually require it; do not enable a timer as a translation side effect. |
| System default — `theme_system`; future language selection | Existing key follows system appearance only. | Future language UI needs a separate key/context and a decision on displaying language names; no language labels are supplied here. |
| Nearby devices / Allow local network | Existing app help/action describing Android permissions. | Align with the supported OS's official localized permission/settings names; exact OS/OEM wording is outside the app catalog. |
| min / h, count heading and wall-clock deadline | Abbreviated units, quantity-dependent grammar and localized date/time. | Decide invariant unit abbreviations versus later plural/unit formatting, and the effective app-locale/12-24-hour policy. |
| Product name / Host example | Current brand and illustrative ASCII IPv4 placeholder. | Confirm explicit non-translatable resource flags in a later source-preparation change; currently they are ordinary string resources. |

## Verification and update procedure

This is a source/encoding audit, not a runtime localization test. No Gradle build, emulator/device test, deployment or live MPD operation is required for these documentation-only changes. All shipping strings/resource references, conditional mapping functions, generated metadata routes and text/semantics call sites were checked; the inventory distinguishes app-owned text, dynamic data, library/system text and internal/test literals.

Checks performed for this snapshot:

- Parsed all 26 XML files; every app-owned string/plural resource has a production reference, and all referenced app-owned string/plural keys are catalogued.
- Matched the 179 CSV keys and English values one-for-one to the source, including both quantity variants, Android quote escaping and separator whitespace. Checked unique keys, nonempty contexts, placeholder signatures, exactly three columns, UTF-8 decoding and a CSV parse/write round trip.
- Compared source/resource file hashes and Git paths to confirm only `docs/LOCALIZATION.md` and `docs/strings-en.csv` changed; no application resource, code, dependency or behavior was modified. Checked `git diff --check` and `git status`.

For an updated snapshot, repeat the source/semantics audit and refresh the catalog/context line references. The following read-only check verifies the export against the current simple string/plural source structure from the repository root:

```python
import csv
import io
import re
import xml.etree.ElementTree as ET
from pathlib import Path

source = ET.parse("app/src/main/res/values/strings.xml").getroot()
expected = {}
for resource in source:
    name = resource.attrib["name"]
    variants = [(name, resource)] if resource.tag == "string" else [
        (f"{name}[{item.attrib['quantity']}]", item) for item in resource
    ]
    for key, item in variants:
        text = "".join(item.itertext())
        if text.startswith('"') and text.endswith('"'):
            text = text[1:-1]  # Android quote delimiters, e.g. metadata_separator
        text = text.replace('\\"', '"').replace("\\'", "'")
        text = text.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        expected[key] = text

raw = Path("docs/strings-en.csv").read_bytes()
assert not raw.startswith(b"\xef\xbb\xbf") and b"\r" not in raw
assert raw.endswith(b"\n")
rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
assert rows[0] == ["key", "english", "context"]
assert all(len(row) == 3 and row[2].strip() for row in rows[1:])
assert len(rows) - 1 == len(expected) == len({r[0] for r in rows[1:]})
assert {key: english for key, english, _ in rows[1:]} == expected
signature = lambda text: re.findall(r"%\d+\$(?:0?\d+)?[sd]", text)
assert all(signature(english) == signature(expected[key])
           for key, english, _ in rows[1:])
roundtrip = io.StringIO(newline="")
csv.writer(roundtrip, lineterminator="\n").writerows(rows)
assert roundtrip.getvalue().encode("utf-8") == raw
print(f"Validated {len(expected)} English forms")
```

This checker handles the Android literal forms present now; extend it deliberately if styled text, new escapes, arrays or other resource types are introduced. CSV equality alone cannot prove that a new hardcoded UI string or a changed meaning has been inventoried; keep the source-call-site review.

## Canonical English app catalog

The quantity selector suffixes below are export notation. Placeholder types and line limits refer to current call sites. English strings are shown as displayed templates, with literal spaces retained inside code spans. Source-use locations are included in the CSV context column.

| Resource key | English source text | Context / meaning | Arguments / plural requirements | Display constraints |
| --- | --- | --- | --- | --- |
| `app_name` | `SimpleMPDPlayer` | Product/launcher name; keep the SimpleMPDPlayer brand unchanged. Also the application and activity label in Android settings. | None; no quantity-dependent wording. | System/notification surface chooses truncation; no app character limit. |
| `queue` | `Queue` | Main tab for the current server's playback queue; not a saved playlist. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `browser` | `Browser` | Main tab for browsing the MPD music library; not an Internet browser. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `servers` | `Servers` | Saved server-management title and Settings destination. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `player` | `Player` | Main playback-control tab. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `settings` | `Settings` | Accessibility description of the app-bar overflow/settings icon. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `back` | `Back` | Accessibility description of navigation back from Servers. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `sleep_timer` | `Sleep timer` | Settings destination/dialog title. A timer that pauses remote MPD; it does not put the phone to sleep. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_waiting` | `Waiting for playback` | Enabled timer waiting to observe playback on its bound server; no countdown or alarm yet. | None; no quantity-dependent wording. | Player: one line with ellipsis in weighted status row; dialog may wrap. No fixed character limit. |
| `timer_compact_remaining` | `Sleep in %1$d min` | Player's compact time until the timer sends MPD Pause; not device sleep. Rounded-up total minutes. | %1$d = rounded-up total remaining minutes (Long). Current abbreviated min avoids English inflection; translate as an invariant abbreviation or flag a future plural-resource change. | Player compact status only: one line with ellipsis in weighted row. No fixed character limit. |
| `timer_compact_off` | `Sleep off` | Player's compact status: the sleep timer is disabled; not MPD stopped. | None; no quantity-dependent wording. | Player compact status only: one line with ellipsis in weighted row. No fixed character limit. |
| `timer_duration` | `Duration` | Sleep-timer duration dropdown label. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `timer_duration_minutes[one]` | `%1$d minute` | Applied duration and preset dropdown entries; current presets are 15/30/45/60, custom range 1-1440 minutes. | R.plurals.timer_duration_minutes, quantity=one. %1$d = selected/preset whole minutes (Int, 1-1440); also the quantity selector. Existing English branches one/other; target-language categories are not limited to these two. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `timer_duration_minutes[other]` | `%1$d minutes` | Applied duration and preset dropdown entries; current presets are 15/30/45/60, custom range 1-1440 minutes. | R.plurals.timer_duration_minutes, quantity=other. %1$d = selected/preset whole minutes (Int, 1-1440); also the quantity selector. Existing English branches one/other; target-language categories are not limited to these two. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `timer_custom_option` | `Custom…` | Choose custom timer duration; the ellipsis indicates further input, not immediate activation. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `timer_custom_done_hint` | `Press Done to apply the duration. This does not turn the timer on.` | Custom-input help; Done applies a valid duration and does not enable an Off timer. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_waiting_hint` | `Waiting starts the full countdown when this app next observes this server playing. A start while the app is closed or another server is selected may only be detected after returning or reconnecting.` | Waiting-state limitation: the full duration starts at confirmed playback observation, possibly only after returning/reconnecting. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `track_info_expand` | `Expand track information` | Accessibility action label on the metadata row: reveal artist/album and expanded title. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `track_info_collapse` | `Collapse track information` | Accessibility action label on the metadata row: show compact title only. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `track_info_expanded` | `Expanded` | Accessibility state of the metadata row; not playback state. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `track_info_collapsed` | `Collapsed` | Accessibility state of the metadata row; not playback state. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `stream_info_button` | `About Listen on phone` | Accessibility label of the information icon beside Listen on phone; opens an explanation, never toggles audio. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `stream_info` | `Plays the active MPD HTTP stream on this phone. Transport controls operate MPD on the server. Turning this off stops only local reception. Phone and Bluetooth volume control local sound.` | Listening information dialog. Distinguishes local HTTP reception/phone volume from remote MPD controls/output. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_enabled` | `On / Off` | Label beside the sleep-timer enable switch; On/Off is timer authorization, not playback. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_off` | `Off` | Timer-dialog status: timer disabled. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_select_server` | `Select a server before starting the timer.` | Timer cannot be enabled until a server profile is selected. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_target` | `Timer server: %1$s` | Name of the server bound to the timer; may differ from the currently selected server. | %1$s = original bound server display name; user data, do not translate. | Wrapping text; no fixed character limit. |
| `timer_remaining` | `Sleep in %1$d h %2$d min` | Timer-dialog countdown until remote MPD Pause; rounded-up duration split into hours and remaining minutes. | %1$d = remaining whole hours (Long); %2$d = remaining minute part 0-59 (Long). Abbreviated units; full-word translations may require future quantity-aware formatting. | Wrapping text; no fixed character limit. |
| `timer_due` | `Due · Waiting for Android` | Timer is due but Android has not delivered the inexact alarm; do not imply completion. | None; no quantity-dependent wording. | Player: one line with ellipsis in weighted status row; dialog may wrap. No fixed character limit. |
| `timer_estimated_end` | `Estimated due time: %1$s` | Estimated wall-clock date/time of the timer deadline, not an exact-delivery promise. | %1$s = already-formatted estimated local date/time; not a raw timestamp. | Wrapping text; no fixed character limit. |
| `timer_custom_minutes` | `Custom minutes` | Label of the editable custom whole-minute duration field. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `timer_minutes_error` | `Enter a whole number from 1 to 1440.` | Custom-duration validation: literal inclusive bounds 1 and 1440; only whole minutes. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_restart_hint` | `Changing duration resets the timer on its original server: count down while playing, otherwise wait.` | Changing duration resets on the originally bound server: start now if playing, otherwise wait for playback. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_power_hint` | `Timing may be delayed by Android power saving. The timer sends Pause to MPD, independently of local listening.` | Timer power-management disclaimer. Pause means the MPD transport command, independent of local HTTP listening. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_scheduling` | `Scheduling…` | Timer alarm registration in progress; not yet registered successfully. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_delivering` | `Sending Pause… Changes will wait for this short attempt.` | One bounded MPD Pause attempt is underway; edits wait for that attempt. Pause is the transport action. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_completed` | `MPD acknowledged Pause. Timer finished.` | Success requires MPD's acknowledgement of Pause; do not equate it with a phone sleep event. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_cancelled` | `Timer cancelled.` | Timer was cancelled; does not say MPD playback was stopped. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_profile_changed` | `Timer cancelled because its server was deleted or its host or ports changed.` | Timer cancelled after bound-server deletion or host/port edit. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_rebooted` | `Timer cancelled after a device restart. Enable it to start again.` | Timer cancelled after the Android device rebooted, not after an MPD restart. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_recovered` | `Invalid timer settings were discarded. Check the duration before starting again.` | Malformed persisted timer settings were discarded; review the last duration. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_player_error` | `Sleep timer needs attention. Open Settings → Sleep timer.` | Compact Player warning directing the user to Settings and Sleep timer; keep destination terminology consistent. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_network` | `Pause could not be confirmed. Check the server and network. No automatic retry.` | Remote Pause unconfirmed after a network/server failure; preserve uncertainty and no-retry statement. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_timeout` | `Pause timed out; delivery may be uncertain. No automatic retry.` | Remote Pause acknowledgement timed out; the server may already have acted; no automatic retry. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_rejected` | `MPD rejected Pause. Check server permissions.` | MPD explicitly rejected Pause; server permissions may be responsible. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_authentication` | `MPD requires authentication or permission for Pause. Passwords are not supported.` | MPD authentication/permission rejection; this app has no password support. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_permission` | `Pause could not be delivered. Check Android local network permission.` | Android local-network permission blocked the Pause attempt; not an MPD password problem. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_protocol` | `The server response could not be validated. Pause is unconfirmed.` | Pause unconfirmed because the MPD response was invalid; never expose the raw response. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_scheduling` | `Android could not register the timer, or registration was interrupted. Enable it to try again.` | Android alarm registration failed or was interrupted; re-enabling is a new attempt. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_uncertain` | `The previous Pause attempt was interrupted. Delivery is uncertain; it will not be retried automatically.` | Interrupted previous Pause attempt has unknown outcome and will not be replayed automatically. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_missing_alarm` | `The timer alarm is no longer available. Enable it to start again.` | The Android PendingIntent/alarm is missing; enable a new timer. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_clock` | `The device boot could not be identified. Timer was not started.` | Android boot identity could not be determined; not a request to correct the wall clock. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `timer_error_storage` | `Timer settings could not be read or saved. Retry without replacing stored settings.` | Timer settings read/write error; retry must preserve stored settings. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_root` | `Music library` | Root heading of the MPD music-library folder browser. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_path` | `Music library / %1$s` | Music-library heading followed by the MPD relative folder path; the path is server data. | %1$s = current MPD relative folder path; server data, do not translate. | Browser heading: up to 3 lines with ellipsis; no fixed character limit. |
| `library_up` | `Up one folder` | Accessibility label for navigation to the parent folder, not previous track. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `library_unavailable` | `Connect to a server to browse its music library` | Browser has no usable MPD connection. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_loading` | `Loading folder…` | One folder listing is loading; not recursive library indexing. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_empty` | `This folder is empty` | Current folder has no listed content. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_no_music` | `No music files or folders` | No supported files/folders remain after skipping stored-playlist entries. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_playlists_unsupported` | `Stored playlists are not supported yet.` | MPD stored playlists are unsupported; this does not refer to the active playback queue. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_load_failed` | `Could not load this folder. It may be unavailable or access may be denied.` | Sanitized folder-listing failure; does not reveal raw server errors or imply playback disconnected. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_actions` | `Queue actions for %1$s` | Accessibility label for a file/folder overflow menu that offers queue actions. | %1$s = file/folder display title; server data, do not translate. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `library_folder` | `Folder` | Secondary label identifying a directory entry. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `append_to_queue` | `Append to queue` | Add a file/folder to the end of the current remote queue; no local playlist is created. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `replace_queue` | `Replace queue` | Destructive replace action in browser menus and confirmation; replacement never auto-starts playback. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `replace_queue_confirmation` | `Replace the queue?` | Browser confirmation title before clearing/replacing the current MPD queue. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `replace_queue_message` | `Remove the current queue and add “%1$s”? Playback will not start automatically.` | Confirm replacement with the selected file/folder title. Preserve both removal and no-auto-start warning. | %1$s = selected file/folder display title; server data, do not translate. | Wrapping text; no fixed character limit. |
| `library_appended` | `Added to queue.` | The add and confirmed queue readback succeeded. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_replaced` | `Queue replaced. Playback has not been started.` | Replacement succeeded; the app did not start playback. Do not promise the server is stopped. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_add_failed` | `Adding content failed or could not be confirmed. The queue may have changed.` | Add failed or its outcome is uncertain; partial MPD changes remain possible. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_clear_failed` | `Could not clear the queue. Nothing was added.` | Clear was rejected, so this replacement did not proceed to Add. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_replace_unconfirmed` | `Queue replacement could not be confirmed. The queue may have changed. Reconnect to check.` | Queue replacement outcome is uncertain; Reconnect checks actual state and does not replay it. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_replace_partial` | `The queue was cleared, but adding content failed or could not be confirmed. Some tracks may have been added.` | Clear succeeded but Add failed/unconfirmed; partial content may remain. Do not imply rollback. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `library_refresh_failed` | `Content was added, but the queue state could not be refreshed. Reconnect to check.` | Add was acknowledged but subsequent queue readback failed; do not say the add itself failed. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `queue_empty` | `The queue is empty` | Empty MPD queue message, also the Player's empty title fallback. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `queue_unavailable` | `Connect to a server to load its queue` | Queue cannot be loaded without a usable server connection. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `no_current_song` | `No current song` | Player fallback when there is no current-song metadata; not necessarily an empty queue. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `playing` | `Playing` | Confirmed MPD playback state; shared by Player and MediaSession subtitle. | None; no quantity-dependent wording. | Player state: 1 line with ellipsis; MediaSession rendering is OS-dependent. No fixed character limit. |
| `paused` | `Paused` | Confirmed MPD pause state; shared by Player and MediaSession subtitle. | None; no quantity-dependent wording. | Player state: 1 line with ellipsis; MediaSession rendering is OS-dependent. No fixed character limit. |
| `stopped` | `Stopped` | Confirmed MPD stopped state; shared by Player and MediaSession subtitle. | None; no quantity-dependent wording. | Player state: 1 line with ellipsis; MediaSession rendering is OS-dependent. No fixed character limit. |
| `playback_unknown` | `Playback status unavailable` | No confirmed MPD playback state; shared by Player and MediaSession subtitle. | None; no quantity-dependent wording. | Player state: 1 line with ellipsis; MediaSession rendering is OS-dependent. No fixed character limit. |
| `play` | `Play` | Accessibility description of the Player Play icon; acts on MPD, not the local HTTP decoder. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `pause` | `Pause` | Accessibility description of the Player Pause icon; preserves MPD playback position. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `stop` | `Stop` | Accessibility description of the Player Stop icon; distinct from Pause. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `next` | `Next` | Accessibility description of the next-track icon, not folder navigation. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `previous` | `Previous` | Accessibility description of the previous-track icon, not navigation Back. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `random` | `Random` | Independent MPD random-order (shuffle) switch label and its accessibility description; remains enabled with Single. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `repeat` | `Repeat: %1$s` | Repeat-dropdown button containing one already-localized mode/state label; never concatenate an untranslated suffix. | %1$s = already-localized Repeat mode/state label from repeat_*. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `repeat_none` | `None` | Repeat option: repeat=0, single=0. Means no repetition, not no current track. | None; no quantity-dependent wording. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `repeat_queue` | `Queue` | Repeat option: repeat=1, single=0. Repeat the entire current queue, not a saved playlist. | None; no quantity-dependent wording. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `repeat_single` | `Single` | Repeat option: repeat=1, single=1. Repeat the current track; not play it just once. | None; no quantity-dependent wording. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `repeat_oneshot` | `One-shot (MPD)` | Read-only display of external MPD single=oneshot; distinct from the supported Single repeat mode. | None; no quantity-dependent wording. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `repeat_stop_after_track` | `Stop after track (MPD)` | Read-only external repeat=0/single=1 state: stop when the current track ends; not immediate Stop. | None; no quantity-dependent wording. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `repeat_unknown` | `Unknown` | Repeat/single status is missing or unsupported; not a selectable fourth mode. | None; no quantity-dependent wording. | Repeat control/menu wraps by width; whole control can move to the next row. No fixed character limit. |
| `metadata_separator` | `, ` | Separator between multiple artist or album tags in Player, Queue, Browser and MediaSession. A comma followed by one space. | None; no quantity-dependent wording. | Keep intended separator whitespace; no length cap; current value is comma plus one ASCII space. |
| `mpd_volume` | `MPD volume: %1$d` | Remote server mixer volume, independent of phone/Bluetooth volume. No percent sign is currently displayed. | %1$d = remote MPD mixer value, integer 0-100. No percent or grouping is added by this template. | Wrapping text; no fixed character limit. |
| `duration_unknown` | `Duration unavailable` | Player duration is unavailable; there is no fabricated seek range. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `total_duration` | `Track duration` | Label for a known track duration when MPD status duration is unavailable. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `time_minutes_seconds` | `%1$d:%2$02d` | Elapsed/total track duration across Player, Queue and Browser. Total minutes and two-digit seconds; not a wall-clock time. | %1$d = total whole minutes (Long); %2$02d = seconds 0-59 (Long), width 2 with zero padding. Preserve positions/types and numeric meaning; not pluralized. | Compact numeric time display; no fixed character limit; minutes may exceed two digits. |
| `applying_command` | `Applying command…` | An MPD user command is pending; controls wait for confirmed readback. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stale_data` | `Showing the last received server state` | Cached MPD snapshot shown while control or idle synchronization is unavailable. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `queue_count` | `Tracks: %1$d` | Heading with number of queue entries, including duplicate paths. Current English uses a label-plus-count, not quantity plurals. | %1$d = queue entry count (Int, including duplicates). Currently a label-plus-count, not plurals; a translation needing count-dependent noun forms requires a future resource/call-site change. | Wrapping text; no fixed character limit. |
| `clear_queue` | `Clear queue` | Destructive action button in Queue and its confirmation dialog. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `clear_queue_confirmation` | `Clear the queue?` | Confirmation title before clearing every entry in the active MPD queue. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `clear_queue_message` | `Remove all tracks from the active MPD queue?` | Clarifies that all remote active-queue tracks are removed; saved music files are not deleted. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `queue_item_actions` | `Actions for %1$s` | Accessibility description of an individual queue track's overflow menu. | %1$s = queue track title; server data, do not translate. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `move_up` | `Move up` | Queue-item action: move earlier in playback order; not server sorting. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `move_down` | `Move down` | Queue-item action: move later in playback order; not server sorting. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `remove` | `Remove` | Remove one queue entry; never delete its music file. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `add_server` | `Add server` | Add-profile dialog title and accessibility label of the Servers Add icon. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `edit_server` | `Edit server` | Existing server-profile editor title. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `delete_server` | `Delete server?` | Confirmation title for deleting a saved profile, not deleting an MPD server or its music. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `delete_server_confirmation` | `Are you sure you want to delete "%1$s"?` | Includes the user-supplied saved-profile name; quotes are displayed, not Android escape syntax. | %1$s = saved server display name; user data, do not translate. | Wrapping text; no fixed character limit. |
| `server_name` | `Name` | Server-profile display-name field label; not a track title or hostname. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `server_host` | `Host` | Server-profile hostname/IP field label; not a URL or server nickname. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `mpd_port` | `MPD port` | TCP port for MPD commands; default numeric value 6600 is machine data. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `http_stream_port` | `Stream port` | Port for the MPD HTTP audio stream; distinct from the MPD command port, default 8000. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `server_endpoint` | `Host: %1$s · MPD port: %2$d` | Profile-list summary: user/server host and numeric MPD command port; not the HTTP stream port. | %1$s = host/IP string; %2$d = MPD TCP port integer 1-65535, not the HTTP port. No grouping; technical addresses/ports must retain their meaning. | Server-list endpoint: up to 2 lines with ellipsis; no fixed character limit. |
| `active_server` | `Active server` | Selected profile marker and accessibility description of the selection checkmark. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `no_servers` | `No saved servers. Add a server to get started.` | First-use/empty-profile-list instruction. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `reconnect` | `Reconnect` | Explicit MPD reconnect action; resets automatic recovery budget, never replays a mutation. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `edit` | `Edit` | Accessibility description of the server-profile Edit icon. | None; no quantity-dependent wording. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `delete` | `Delete` | Server-profile deletion confirmation button and Delete icon accessibility description. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `save` | `Save` | Profile editor: persist, select and connect the profile; Save may be followed by a connection failure. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `cancel` | `Cancel` | Dismiss/discard a pending editor or destructive confirmation; do not perform its action. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `retry` | `Retry` | Retry storage reads or a folder listing in the current context; not a blind retry of an uncertain MPD mutation. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `invalid_name` | `Enter a name without control characters.` | Profile-name validation: nonblank, no control characters; no fixed character cap is configured. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `invalid_host` | `Enter a hostname or IP address without a scheme, path or port.` | Host-field validation: hostname or IP only, without URL scheme/path/embedded port. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `invalid_port` | `Enter a port from 1 to 65535.` | Shared command/stream port validation. Preserve literal inclusive bounds 1 and 65535. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `disconnected` | `Disconnected` | No active MPD control connection; not local audio Off. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connecting` | `Connecting…` | MPD connection setup in progress; distinct from opening the HTTP stream. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `select_server` | `Select server` | Empty header prompt and server-dropdown icon accessibility label. | None; no quantity-dependent wording. | Header: 1 line with ellipsis; also spoken icon description. No fixed character limit. |
| `reorder_server` | `Reorder %1$s` | Accessibility description of the drag handle for a saved profile; not queue reordering. | %1$s = server display name; user data, do not translate. | Accessibility text/action/state; no app truncation or fixed character limit. |
| `appearance` | `Appearance` | Settings destination/dialog for visual theme; not app-language selection. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `theme_system` | `System default` | Appearance option: follow the system theme. Do not reuse this existing key for future language selection. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `theme_light` | `Light` | Light visual theme option. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `theme_dark` | `Dark` | Dark visual theme option. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `done` | `Done` | Shared dialog button: close Appearance/listening info; apply valid custom timer duration and close its dialog without enabling Off. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `host_placeholder` | `192.168.1.100` | Illustrative existing private IPv4 address in the Host placeholder. Keep ASCII literal; not a real saved server or a default connection. | None; no quantity-dependent wording. | Full-width single-line input or dropdown; keep label/placeholder concise. No fixed character limit. |
| `test_connection` | `Test` | Profile editor button: test MPD greeting/ping without saving or changing active control. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `testing` | `Testing…` | Independent MPD connection probe in progress. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `mpd_available` | `MPD available` | Short toast: MPD greeting/ping succeeded; does not confirm HTTP audio availability. | None; no quantity-dependent wording. | Shared header/short-toast text; OS controls toast display and truncation. No fixed character limit. |
| `mpd_unavailable` | `MPD unavailable` | Short toast: MPD greeting/ping failed; detailed sanitized inline error is separate. | None; no quantity-dependent wording. | Shared header/short-toast text; OS controls toast display and truncation. No fixed character limit. |
| `connected` | `Connected` | Confirmed MPD connection success, used in header and short toast. | None; no quantity-dependent wording. | Shared header/short-toast text; OS controls toast display and truncation. No fixed character limit. |
| `connection_failed` | `Connection failed` | MPD connection failure, used in header and short toast. | None; no quantity-dependent wording. | Shared header/short-toast text; OS controls toast display and truncation. No fixed character limit. |
| `connection_refused` | `Connection refused. Check the MPD port and server configuration.` | MPD control connection refused; check command port/server configuration, not HTTP stream port. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `host_not_reachable` | `Host not reachable. Check the host and your network.` | MPD hostname/address cannot be reached or resolved. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_timed_out` | `Connection timed out. Check the server and try again.` | MPD connection/command timeout; not a local HTTP decoder timeout. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `invalid_mpd_response` | `Invalid MPD response. Check that this is an MPD endpoint.` | Sanitized malformed MPD protocol response; no raw response text is displayed. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_recovering` | `Automatic recovery · %1$d of 5 retries used` | Header recovery progress: number of attempts already used (0-5), with a fixed total of five. | %1$d = retry attempts already consumed (Int, 0-5); 5 is a fixed literal total, not a missing placeholder. | Wrapping text; no fixed character limit. |
| `connection_waiting_network` | `Network unavailable` | Android reports no available network; automatic attempts are paused. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_retries_exhausted` | `Automatic recovery stopped after 5 retries. Tap Reconnect to try again.` | Five automatic attempts have been used; Reconnect is explicit and starts a new budget. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_source_connect` | `MPD connection setup failed` | Failure source: initial/restored MPD command-connection setup. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_source_control` | `MPD control connection interrupted` | Failure source: MPD command/control socket; not merely idle/event loss. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_source_idle` | `Live MPD updates interrupted; the control connection is retained` | Failure source: idle/live-event socket only; healthy interactive control remains available. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `connection_lost` | `Connection lost. Check the server and network.` | MPD control transport was lost; distinct from local HTTP-only failure. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `mpd_rejected` | `MPD rejected the command. Check server permissions or whether the selected track is still available. Password authentication is not supported yet.` | Sanitized MPD ACK rejection; permissions or a stale selected track may explain it. Password support is absent. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `network_error` | `Network error. Check your connection and try again.` | Generic MPD networking error, displayed through ConnectionError mapping. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `allow_local_network` | `Allow local network` | App button requesting Android LAN permission; OS permission-dialog wording is owned by Android. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `local_network_permission_required` | `Local network access is required to connect to your MPD server. Allow access, or enable Nearby devices in app settings if the permission prompt no longer appears.` | App explanation of Android LAN access and possible Nearby devices settings. Match official OS terms in later translations. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `settings_recovered` | `Invalid saved settings were ignored. Review your server profiles.` | Malformed profile/preferences data was ignored while retaining valid records; review profiles. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `settings_read_failed` | `Could not load saved servers. Retry to load them again.` | Saved-profile storage read failed; Retry re-reads without overwriting stored data. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `settings_save_failed` | `Could not save changes. Please try again.` | Profile/order/theme/UI-preference write failed; existing settings remain authoritative. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `listen_on_phone` | `Listen on phone` | Manual local HTTP-receiver label and information-dialog title; it does not start remote MPD playback. | None; no quantity-dependent wording. | Listening card: up to 2 lines with ellipsis; dialog title wraps. No fixed character limit. |
| `stream_off` | `Off · MPD outputs are unchanged` | Local reception is Off; MPD outputs remain unchanged. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_connecting` | `Connecting to the HTTP stream…` | Connecting the local HTTP receiver, distinct from MPD control setup. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_buffering` | `Buffering HTTP audio…` | Local HTTP audio buffering, not proof of remote Playing or audible music. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_active` | `Receiving audio · May lag behind MPD` | HTTP audio is being received; it may lag behind confirmed MPD metadata/state. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_waiting` | `Ready · Waiting for MPD playback` | HTTP receiver is ready but waits for MPD playback; incoming bytes can contain silence. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_interrupted` | `Local audio interrupted by Android` | Android audio focus/routing suppressed or interrupted local sound; not an MPD transport action. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_retry` | `Retry local audio` | Manually retry only local HTTP audio, without replaying MPD control commands. | None; no quantity-dependent wording. | Compact button, menu, tab or label; no fixed character limit; review width and large text. |
| `stream_error_unavailable` | `HTTP audio unavailable. Check the stream port and MPD httpd output, then retry.` | HTTP stream unavailable; check stream port and MPD's httpd output. Keep technical token httpd unchanged. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_error_timeout` | `HTTP audio timed out. Check the network and stream port, then retry.` | HTTP connection/read timeout, separate from MPD control timeout. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_error_format` | `The stream could not be decoded. Check the MPD HTTP audio format.` | Local HTTP audio could not be decoded; MPD HTTP encoder format may be unsupported. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_error_permission` | `Local audio needs network access. Check Android app permissions.` | Android network/audio-start permission problem affecting the local receiver. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_error_connection` | `Local audio stopped because the MPD connection was lost. Reconnect, then enable it again.` | Local reception stopped after terminal MPD control failure; reconnect then manually enable reception. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_error_start` | `Android could not start local audio. Open the app and retry.` | Android could not start the local audio service; opening the app may be needed. | None; no quantity-dependent wording. | Wrapping text; no fixed character limit. |
| `stream_notification_status` | `Listen on phone · MPD: %1$s` | App-supplied MediaSession subtitle, visible where the OS/controller renders it; embeds confirmed MPD state. | %1$s = already-localized MPD playback state (playing/paused/stopped/playback_unknown). | System/notification surface chooses truncation; no app character limit. |
