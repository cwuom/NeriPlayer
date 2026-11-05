[English](./README_EN.md) | [中文](./README.md)

# Quality checks

These tools check method complexity, source ownership, and dependency boundaries.
Gradle tasks require JDK 17, an Android build environment, and Python 3. The Python
scripts use only the standard library. Gates running `:playback:runtime` JVM tests
also need a Gradle-discoverable JDK 21; production compilation still targets 17.

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

| Task | Scope and Behavior |
| --- | --- |
| `verifyModularization` | Runs structural checks, `app` and owned JVM-library Debug tests, combined CRAP reporting, domain dependency checks, lint, and build-logic tests |
| `:app:check` | Includes CRAP and domain dependency checks |
| `:app:crapReport` | Validates inputs and generates the report without failing on over-threshold scores |
| `:sync:verifyCrap` | Combines coverage from `:sync` and its sync models in `:model` |
| `:local:verifySyncIntegrationCrap` | Runs JVM tests for Android sync adapters and checks `:local`'s entire `data/sync` directory; does not replace the full `:local` gate |

The 14 owned libraries are registered in [gradle/owned-modules.txt](../../gradle/owned-modules.txt). See the [implementation notes](../../docs/implementation_EN.md#module-layout) for module responsibilities:

- **Kotlin/Java modules**: Retain full coverage collection. Independent CRAP tasks enforce the same threshold across their respective source scopes.
- **`:native` module**: Pure-native Android library included in registration, structural checks, and lint only; creates no JaCoCo data, CRAP tasks, or JVM test tasks. Independent CI checks the three native USB host profiles and four Android ABI builds. The standalone `tests/dsp/` CMake/CTest suite is separate from those USB profiles. These checks run outside the JVM gates (see [Native module README](../../modules/native/README.md)).

Run focused checks for affected modules:

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

## CRAP

`crap_report.py` calculates method risk using `C² × (1 − cov)³ + C`:

| Metric / Rule | Definition |
| --- | --- |
| `C` | JaCoCo `COMPLEXITY` `missed + covered` (method cyclomatic complexity) |
| `cov` | `covered / C` (complexity coverage), used as a path-coverage proxy rather than line coverage or strict independent-path measurement |
| Pass / Fail | Fails when a scoped method's unrounded score is `> 9`; `<= 9` passes. No average-score gate or historical baseline exemption |
| Full-Coverage Floor | At `100%` coverage, `CRAP = C` |

The threshold of `9` is a risk gate for complexity and coverage. Refactoring must make entry points, business steps, state ownership, and failure handling easier to trace; never split methods, add forwarding layers, or weaken thresholds, scope, or valid tests just to lower a score. The gate does not run automated mutation testing. Review CRAP results alongside dependency checks, behavior and boundary tests, lint, and relevant integration or device checks to assess responsibilities and assertion quality.

References: [CRAP author](https://www.artima.com/weblogs/viewpost.jsp?thread=215899), [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html).

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

Dependency enforcement combines source-level structural checks with bytecode-level direct dependency checks. Rules are defined in [module_boundaries.py](./module_boundaries.py) and [domain-dependencies.json](../../config/quality/domain-dependencies.json); directory names do not imply dependency layers.

| Layer | Entry Point | Tooling and Rules |
| --- | --- | --- |
| Source & Module Boundaries | `module_boundaries.py` | Runs without the Android SDK. Reads the module registry, Gradle declarations, and source files to check unregistered build nodes, cycles, forbidden references, production package ownership, and package paths. `:model` cannot depend on project implementations, libraries cannot depend on `app`, and protected production packages cannot move back into `app`. Library production files must stay under 2000 lines, and library production directories plus `APP_FAMILIES` directories allow at most 16 direct Kotlin/Java files. |
| Bytecode Domain Dependencies | `verifyDomainDependencies` | Uses `jdeps` from the Gradle JDK to inspect direct class dependencies and `javap` to verify full JVM member signatures on host bridges. Platform protocols must not read account repositories, Room, or player state; playback/download rules, sync calculations, lyric parsing, and local storage calculations maintain separate allowlists. |

### Domain Configuration and Scope

- **Automatic inclusion & least privilege**: New classes, nested classes, lambdas, and Kotlin-generated classes in protected packages are checked automatically. Allowing a single bridge method does not grant access to the rest of its host class.
- **Exclusions**: `excluded_classes` matches only the named source class and its `$` nested classes (no wildcards). Any excluded subpackage must be covered by another protected domain, and excluding a source class does not relax incoming reference restrictions from other classes.
- **Fail-closed conditions**: Missing artifacts, duplicate classes, empty domains, tool failures, or omitted protected classes fail the task.
- **Reports & analysis boundary**: The combined report writes to `app/build/reports/domain-dependencies/report.json`, while `:platform`, `:sync`, and `:listentogether` write independent reports to each module's `build/reports/domain-dependencies/report.json`. Inspection covers direct bytecode dependencies; it does not trace transitive calls or reflection. Review platform dependencies inside shared models and bridge implementations separately.

## Tool tests

```bash
python3 -B tools_pub/quality/module_boundaries.py
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

Domain dependency tests compile isolated fixtures with `javac --release 17`, then
run real `jdeps` and `javap` to verify that forbidden class dependencies, bridge
members, nested classes, and new files are rejected. These validate the gate tools;
they do not replace business tests.
