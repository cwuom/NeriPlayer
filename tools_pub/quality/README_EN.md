[English](./README_EN.md) | [中文](./README.md)

# Quality checks

These tools check method complexity, source ownership, and dependency boundaries.

- Requires JDK 17, an Android build environment, and Python 3
- The Python scripts use only the standard library
- Gates that run `:playback:runtime` JVM tests also need a Gradle-discoverable JDK 21
- Production compilation still targets 17

| Check | Purpose |
| --- | --- |
| `verifyCrap` | Combines method complexity and JVM test coverage to check risk scores in scope |
| `verifyModuleBoundaries` | Checks module registration, source ownership, dependency direction, package paths, and size limits |
| `verifyDomainDependencies` | Checks direct JVM class dependencies and host bridge member calls in protected packages |

## Commands

Run from the repository root:

```bash
./gradlew verifyModularization
./gradlew :app:verifyCrap :app:verifyDomainDependencies
./gradlew :app:crapReport
```

| Task | Scope and behavior |
| --- | --- |
| `verifyModularization` | Aggregates all checks; see the list below |
| `:app:check` | Includes CRAP and domain dependency checks |
| `:app:crapReport` | Validates inputs and generates the report; over-threshold scores do not fail |
| `:sync:verifyCrap` | Combines coverage of `:sync` and the sync models in `:model` |
| `:local:verifySyncIntegrationCrap` | Checks the entire `data/sync` directory under `:local` |

`verifyModularization` includes:

- Structural checks
- Debug JVM tests of `app` and the owned JVM libraries
- The combined CRAP report
- Domain dependency checks
- Lint
- Build-logic tests

`:local:verifySyncIntegrationCrap` runs JVM tests for the Android sync adapters and supplements the full `:local` gate.

The 14 owned libraries are registered in [gradle/owned-modules.txt](../../gradle/owned-modules.txt). See the [implementation notes](../../docs/implementation_EN.md#module-layout) for module responsibilities.

- **Kotlin/Java modules**: full coverage collection; independent CRAP tasks use the same threshold over their own source scopes
- **`:native` module**: pure-native Android library, included only in registration, structural checks, and lint
- `:native` creates no JaCoCo data, CRAP tasks, or JVM test tasks
- Independent CI checks the three native USB host profiles and four ABI builds
- `tests/dsp/` is a standalone CMake/CTest suite
- These checks run outside the JVM gates; see the [Native module README](../../modules/native/README.md) for commands

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

`crap_report.py` calculates method risk as `C² × (1 − cov)³ + C`:

| Metric | Definition |
| --- | --- |
| `C` | JaCoCo `COMPLEXITY` `missed + covered`, the method cyclomatic complexity |
| `cov` | `covered / C`, the complexity coverage |
| Full coverage | At `cov = 100%`, `CRAP = C` |

- `cov` approximates path coverage; it is neither line coverage nor a strict independent-path measure
- Pass condition: a scoped method's unrounded raw score is `<= 9`; `> 9` fails
- There is no average-score gate and no historical baseline exemption

### Threshold and refactoring

The threshold `9` gates complexity and coverage risk.

- Refactoring should make entry points, business steps, state ownership, and failure handling easier to trace
- Do not split methods or add forwarding layers just to lower a score
- Do not loosen the threshold, shrink the scope, or weaken valid tests
- The gate does not run automated mutation testing
- Review combines dependency checks, behavior and boundary tests, lint, and relevant integration or device checks
- This assesses responsibility split and assertion quality

References: [CRAP author](https://www.artima.com/weblogs/viewpost.jsp?thread=215899), [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html).

### Scope

[config/quality/crap-scope.json](../../config/quality/crap-scope.json):

- `source_patterns` selects whole files
- `method_scopes` selects classes and JVM method names, including constructors and Kotlin default-argument methods
- Recursive patterns include new files and subdirectories
- Methods outside the gate scope still appear in reports but do not decide the gate result

Data sources of `:app:verifyCrap`:

- Gets app and registered-library Kotlin/Java bytecode through AGP `ScopedArtifact.CLASSES`
- Merges real test execution data into JaCoCo XML
- Merges sources from each JVM module's `src/main/java` and `src/main/kotlin` into `app/build/reports/crap/sources`
- Duplicate paths and missing or empty execution data fail the task

Reporter exit codes:

- 2: invalid XML or counters, empty configuration, unmatched rules, or missing scoped sources or methods
- 1: a score above the threshold

Counting rules:

- Pure interfaces are validated through class records
- Executable default implementations are still scored
- Coroutine, lambda, and Compose bytecode use the actual counters after JaCoCo's built-in filtering
- Source branch counts differ from the JVM complexity in reports

When moving or splitting code:

- Review the complete diff and actual XML before updating scope, and keep previously checked logic
- Kotlin `internal` methods may carry module suffixes, so selectors must match the real JVM names
- For delegating Android hosts, distinguish tested components from unexecuted entry points
- Component coverage does not prove host I/O, Provider, or Compose behavior

### Reports

Combined reports are written to `app/build/reports/crap/`:

| File | Contents |
| --- | --- |
| `methods.json` | All JVM methods mapped to production sources |
| `above-8.md` | Methods with unrounded scores strictly above 8, including out-of-scope methods |
| `scope.md` | Every method in the gate scope |
| `coverage.xml`, `coverage/index.html` | Combined JaCoCo coverage |

Each `methods.json` entry has the signature, location, complexity, coverage, score, and scope flag.

- Independent CRAP reports are in each module's `build/reports/crap/`
- Local sync adapter reports are in `modules/local/build/reports/sync-crap/`

## Dependencies

Dependency enforcement combines source-level structural checks with bytecode-level direct dependency checks. Rules are defined in [module_boundaries.py](./module_boundaries.py) and [domain-dependencies.json](../../config/quality/domain-dependencies.json); directory names do not imply dependency layers.

### Source and module boundaries

Entry point: `module_boundaries.py`, which runs without the Android SDK.

- Reads the module registry, Gradle declarations, and source files
- Checks unregistered build nodes, cycles, forbidden references, production package ownership, and package path consistency
- `:model` cannot depend on project implementations
- Libraries cannot depend on `app`, and protected production packages cannot move back into `app`
- Library production files stay under 2000 lines
- Library production directories and `APP_FAMILIES` directories allow at most 16 direct Kotlin/Java files

### Bytecode domain dependencies

Entry point: `verifyDomainDependencies`.

- Uses `jdeps` from the Gradle JDK to inspect direct class dependencies
- Uses `javap` to verify full JVM signatures of host bridge members
- Platform protocols must not read account repositories, Room, or player state
- Playback/download rules, sync calculations, lyric parsing, and local storage calculations each keep their own allowlists

### Domain configuration and scope

- **Automatic inclusion and least privilege**: new classes, nested classes, lambdas, and Kotlin-generated classes in protected packages are checked automatically; allowing one bridge method does not open the rest of its host class
- **Exclusions**: `excluded_classes` matches only the named source class and its `$` nested classes, with no wildcards
- An excluded subpackage must be covered by another protected domain, and excluding a source class does not relax incoming reference restrictions from other classes
- **Failure conditions**: missing artifacts, duplicate classes, empty domains, tool failures, or omitted protected classes fail the task
- **Combined report**: `app/build/reports/domain-dependencies/report.json`
- **Independent reports**: `build/reports/domain-dependencies/report.json` in each of `:platform`, `:sync`, and `:listentogether`
- **Analysis scope**: direct bytecode dependencies only, with no transitive calls or reflection
- Platform dependencies inside shared models and bridge implementations need separate review

## Tool tests

```bash
python3 -B tools_pub/quality/module_boundaries.py
python3 -B -m unittest discover -s tools_pub/quality -p 'test_*.py'
```

Domain dependency tests compile isolated fixtures with `javac --release 17`, then run real `jdeps` and `javap` to verify that forbidden class dependencies, bridge members, nested classes, and new files are rejected. They validate the gate tools and do not replace business tests.
