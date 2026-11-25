# Licensing audit — M7.2

Application SPDX identifier: **GPL-3.0-or-later**. The grant is GNU GPL version 3
or, at the recipient's option, any later version. The canonical English GPL v3
text was retrieved verbatim from <https://www.gnu.org/licenses/gpl-3.0.txt> on
2026-11-22 and is stored at [`LICENSE`](../LICENSE), including its appendix. The
license document itself is unchanged. The grant/choice is expressed in README
and About; the v3 document is the same canonical document for both GPL SPDX
variants, so its presence alone would not express the “or later” choice.

The public source repository is <https://github.com/jschbe/SimpleMPDPlayer>
(confirmed publicly accessible without authentication). No changes are committed
or published by this milestone. Before distributing a new binary publicly, make
its **exact corresponding source**, build scripts and changes available through
that repository or another GPL-compliant source-distribution arrangement. A link
to an older public revision alone does not satisfy that obligation for local
unpublished changes. No store publication/signing work is performed here.

## Resolved components

The audit used Gradle's resolved **debugRuntimeClasspath** (111 artifact
coordinates; releaseRuntimeClasspath has 108, all included in the debug set), each downloaded POM and the actual AAR/JAR license entries. It also
inspected published source JARs for Media3, Kotlin/kotlinx, Guava, Okio and
JSpecify, plus relevant upstream license/NOTICE files. The exact artifact list
and bundled legal text are in [`THIRD_PARTY_NOTICES.txt`](THIRD_PARTY_NOTICES.txt).
Duplicate identical Apache texts are represented by one complete license;
component attribution and distinct notices remain. This is a snapshot, not an
automatically updating dependency-license plugin.

| Direct dependency family | Resolved/configured version | License and action |
| --- | --- | --- |
| AppCompat | 1.8.0, newly added | Apache-2.0; Android Open Source Project attribution and complete Apache text bundled. |
| Activity Compose / Core KTX / Lifecycle | 1.13.0 / 1.19.1 / 2.11.0 | Apache-2.0; same attribution/text, no code modified. |
| Compose BOM / Material 3 / UI | BOM 2026.02.01; Material 3 1.4.0, UI/runtime 1.11.0, foundation/animation 1.10.4 resolved | Apache-2.0; includes debug tooling conservatively. The BOM contains dependency constraints rather than executable code. |
| DataStore Preferences | 1.3.0 | Apache-2.0, with shaded Protocol Buffers **BSD-3-Clause**; its complete copyright, conditions and disclaimer are bundled. |
| Media3 ExoPlayer / Session | 1.11.1 | Apache-2.0, including transitive common/container/database/datasource/decoder/extractor. Published source headers inspected; Sonic's Bill Cox attribution retained. No optional native codec extensions added. |
| Kotlin stdlib | 2.2.20 resolved; Compose compiler plugin stays 2.2.10 | Apache-2.0, plus **BSD-3-Clause ThreeTenBP** time and **BSL-1.0** (Boost Software License 1.0) math-derived code. Both complete additional texts and origin attributions bundled. Compiler/build tools are not app runtime dependencies. |
| kotlinx.coroutines Android | 1.10.2 | Apache-2.0; upstream library NOTICE bundled. Transitive serialization 1.7.3 and atomicfu 0.28.0 are Apache-2.0 too. |
| Guava / failureaccess | 33.3.1-android / 1.0.2 | Apache-2.0 inherited from parent POMs; public-domain concurrency/hash origins acknowledged. The empty listenablefuture conflict-avoidance artifact contributes no classes. |
| Okio / JSpecify / JetBrains annotations | 3.17.0 / 1.0.0 / 23.0.0 | Apache-2.0. Okio's published Base64/ZIP headers identify Apache Commons origins; Codec/Compress attribution notices retained conservatively. |
| JUnit / AndroidX Test / Espresso / coroutine test | existing catalog versions | Test-only; not part of the normal app. JUnit 4 is EPL-1.0; AndroidX Test/Espresso and coroutine tests are Apache-2.0. No test libraries relicensed as GPL. |

No conflicting runtime grant was identified in this resolved set. Apache-2.0,
BSD-3-Clause and BSL-1.0 obligations remain applicable alongside GPLv3-or-later.
Apache's license and required upstream notices must accompany redistribution;
BSD copyright/conditions/disclaimer must be reproduced. These are included
locally in About and the repository. No dependency source files are modified.
The audit does not assert ownership of user-supplied artwork or translations;
retain their provenance and ensure contribution rights before wider publication.

## Packaging and upkeep

The generated Android resources come directly from root `LICENSE` and
`docs/THIRD_PARTY_NOTICES.txt` through `GenerateLegalResources`. In **Settings →
About**, **GNU GPL v3 or later** opens the complete GPL text. **Third-party
licenses** opens an informational overview grouped by library family. Use
**Full license texts and notices** there to read the complete attributions,
dependency inventory and associated licenses. Back or **Done** closes the
document to its parent view. Both documents are available offline in their
original English with localized navigation. **Open source repository** opens the
project in a browser.

For dependency changes, inspect `./gradlew :app:dependencies --configuration
debugRuntimeClasspath` and `releaseRuntimeClasspath`, review the resolved POMs,
parent POMs and AAR/JAR/source licenses/NOTICE files, update the inventory and
texts, rebuild, and exercise the About links. APK metadata entries are retained;
the readable bundled notices do not depend on whether Android tooling happens
to preserve those entries. Recheck corresponding-source access before publishing.

Primary references:

- [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html) and
  [GNU Apache-2.0 compatibility explanation](https://www.gnu.org/licenses/license-list.html#apache2).
- [Apache-2.0 redistribution terms](https://www.apache.org/licenses/LICENSE-2.0).
- [AndroidX license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
  and [Media3 license](https://github.com/androidx/media/blob/1.11.1/LICENSE).
- [Kotlin license inventory](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/README.md),
  [ThreeTenBP terms](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/third_party/threetenbp_license.txt),
  [Boost terms](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/third_party/boost_LICENSE.txt).
- [Coroutines NOTICE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/license/NOTICE.txt)
  and [Guava license](https://github.com/google/guava/blob/v33.3.1/LICENSE).
