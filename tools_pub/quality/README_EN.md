[English](./README_EN.md) | [中文](./README.md)

# Quality checks

These tools check method complexity, source ownership, and dependency boundaries.
Gradle tasks require JDK 17, an Android build environment, and Python 3. The Python
scripts use only the standard library.

| Check | Purpose |
| --- | --- |
| `verifyCrap` | Combine method complexity with JVM test coverage to check risk scores within a defined scope |
| `verifyModuleBoundaries` | Check module registrations, source ownership, declared dependency direction, package paths, and source limits |
| `verifyDomainDependencies` | Check direct JVM class dependencies and host bridge member references in protected packages |

## Commands

Run from the repository root:

```bash
./gradlew verifyModularization
./gradlew :app:verifyCrap :app:verifyDomainDependencies
./gradlew :app:crapReport
```

`verifyModularization` runs structural checks, app and owned JVM-library Debug tests,
combined CRAP reporting, domain dependency checks, lint, and build-logic tests.
`:app:check` includes CRAP and domain checks. `:app:crapReport` still validates its
inputs, but does not fail for scores above the threshold.

The 14 owned libraries are registered in [gradle/owned-modules.txt](../../gradle/owned-modules.txt).
`:native` is a pure-native Android library included in registration, structural checks,
and lint. It creates no JaCoCo execution data, CRAP tasks, or JVM test tasks; existing
Kotlin/Java modules retain complete coverage collection. The independent native CI
checks all three host profiles and four Android ABIs; commands are in the
[Native module README](../../modules/native/README.md). For focused checks:

```bash
./gradlew :playback:logic:verifyCrap :playback:runtime:verifyCrap
./gradlew :download:logic:verifyCrap :download:runtime:verifyCrap
./gradlew :local:verifyCrap :local:lintDebug
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
```

Independent CRAP tasks retain the same threshold and corresponding source scope.
`:sync:verifyCrap` combines coverage from sync and its models in `:model`.
`:local:verifySyncIntegrationCrap` separately runs JVM tests for Android sync adapters
and checks the local library's entire `data/sync` directory; it does not replace
the full local-library gate. See the [root README](../../README_EN.md#module-layout)
for module responsibilities.

## CRAP

`crap_report.py` uses `C² × (1 − cov)³ + C`:

- `C` is JaCoCo `COMPLEXITY` missed + covered, the method's cyclomatic complexity.
- `cov` is covered / C, complexity coverage used as a path-coverage proxy. It is
  neither line coverage nor a strict measurement of independent paths.
- A scoped method fails when its unrounded score is greater than 9; exactly 9
  passes. There is no average-score gate or historical baseline exemption.

**9 is this project's risk threshold, not proof of architectural quality.**
With 100% coverage, CRAP still equals cyclomatic complexity. Low scores do not
prove clear responsibilities, and coverage does not prove useful assertions.
Refactoring should make entry points, business steps, state ownership, and failures
easier to trace. Do not split methods, add forwarding layers, or adjust thresholds,
scope, or valid tests solely to improve scores. Review still needs dependencies,
behavior and boundary tests, lint, and relevant integration or device checks.
Mutation testing can assess whether assertions detect faults; the current gate
does not provide it automatically.

References: [CRAP author](https://www.artima.com/weblogs/viewpost.jsp?thread=215899),
[JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html).

### Scope

[config/quality/crap-scope.json](../../config/quality/crap-scope.json) uses
`source_patterns` for whole files and `method_scopes` for classes and JVM method
names, including constructors and Kotlin default-argument methods. Recursive
patterns include new files and subdirectories. Methods outside the gate scope
still appear in reports but do not determine whether the gate passes.

`:app:verifyCrap` obtains each module's own Kotlin/Java bytecode through AGP
`ScopedArtifact.CLASSES` and combines real test execution data into JaCoCo XML.
Sources from each JVM module's `src/main/java` and `src/main/kotlin` are merged into
`app/build/reports/crap/sources`. Duplicate paths and missing or empty execution
data cause failure.

The reporter returns 2 for invalid XML or counters, empty configuration, unmatched
rules, or missing scoped sources/methods; scores above the threshold return 1.
Pure interfaces are validated through class records. Executable default
implementations still receive scores. Coroutine, lambda, and Compose bytecode
use actual counters after JaCoCo's built-in filtering; source branch counts are
not the same as reported JVM complexity.

When moving or splitting code, review the complete diff and actual XML before
updating scope, preserving previously checked logic. Kotlin `internal` methods
may have module suffixes, so selectors must match their actual JVM names.
For delegating Android hosts, distinguish tested components from unexecuted
entry points. Component coverage does not prove host I/O, Provider, or Compose behavior.

### Reports

Combined reports are written to `app/build/reports/crap/`:

| File | Contents |
| --- | --- |
| `methods.json` | All JVM methods mapped to production sources, with signatures, locations, complexity, coverage, scores, and scope flags |
| `above-8.md` | Methods with unrounded scores strictly above 8, including methods outside the gate scope |
| `scope.md` | Every method in the gate scope |
| `coverage.xml`, `coverage/index.html` | Combined JaCoCo coverage |

Independent CRAP reports use each module's `build/reports/crap/`.
Local sync adapter reports use `modules/local/build/reports/sync-crap/`.

## Dependencies

`module_boundaries.py` requires no Android SDK. It reads the registry, Gradle
declarations, and source to check unregistered build nodes, cycles, forbidden
references, production package ownership, and package paths. `:model` cannot
depend on project implementations; libraries cannot depend on app, and protected
production packages cannot move back into app. Library production files must
remain below 2000 lines. Library production directories and the script's
`APP_FAMILIES` directories allow at most 16 direct Kotlin/Java files.

A module boundary does not enforce every responsibility inside the same library.
Platform protocols must not read account repositories, Room, or player state.
Playback/download rules, sync calculations, lyric parsing, and local storage
calculations retain their own allowlists. The rules live in
[module_boundaries.py](./module_boundaries.py) and
[domain-dependencies.json](../../config/quality/domain-dependencies.json);
directory names do not define dependency layers.

`verifyDomainDependencies` uses `jdeps` from the JDK running Gradle to inspect
direct class dependencies, and `javap` to inspect complete JVM member signatures
for host bridges. New classes, nested classes, lambdas, and Kotlin-generated
classes in protected packages are included automatically. Allowing one bridge
method does not grant access to the entire host class.

`excluded_classes` excludes only the named source class and its `$` nested
classes, without wildcards. An excluded subpackage must be covered by another
protected domain. Excluding a source does not relax other classes' references
to it. Missing artifacts, duplicate classes, empty domains, tool failures, or
analysis that omits protected classes cause failure.

The combined domain report is `app/build/reports/domain-dependencies/report.json`.
Independent `:platform`, `:sync`, and `:listentogether` reports use each module's
`build/reports/domain-dependencies/report.json`. This checks direct dependencies,
not transitive or reflective calls. It does not prove that shared models or bridge
internals have no platform dependencies.

## Tool tests

```bash
python3 -B tools_pub/quality/module_boundaries.py
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

Domain dependency tests compile isolated fixtures with `javac --release 17`, then
run real `jdeps` and `javap` to verify that forbidden class dependencies, bridge
members, nested classes, and new files are rejected. These validate the gate tools;
they do not replace business tests.
