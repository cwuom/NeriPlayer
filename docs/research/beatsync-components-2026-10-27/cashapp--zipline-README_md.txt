# Zipline

This library streamlines using Kotlin/JS libraries from Kotlin/JVM and Kotlin/Native programs. It
makes it fetching code as easy as fetching data:

 * For continuous deployment within mobile apps, just like we do for servers and web apps. It'd be
   simpler to do continuous deploys via the app stores! But that process is too slow and we can't
   guarantee that user’s devices will update immediately.
 * For user-customizable behavior and plugin systems
 * For updating business rules, like pricing or payments
 * For fresh content like games

Zipline works by embedding the [QuickJS JavaScript engine][qjs] in your Kotlin/JVM or Kotlin/Native
program. It's a small and fast JavaScript engine that's well-suited to embedding in applications.

_(Looking for [Duktape Android](#duktape-android)?)_


### Code Example

Let's make a trivia game that has fresh questions every day, even if our users don't update their
apps. We define [our interface][trivia.kt] in `commonMain` so that we can call it from Kotlin/JVM
and implement it in Kotlin/JS.

```kotlin
interface TriviaService : ZiplineService {
  fun games(): List<TriviaGame>
  fun answer(questionId: String, answer: String): AnswerResult
}
```

Next we [implement it][triviaJs.kt] in `jsMain`:

```kotlin
class RealTriviaService : TriviaService {
  // ...
}
```

Let's connect the implementation running in Kotlin/JS to the interface running in Kotlin/JVM. In
`jsMain` we define an [exported function][launchZiplineJs.kt] to bind the implementation:

```kotlin
@JsExport
fun launchZipline() {
  val zipline = Zipline.get()
  zipline.bind<TriviaService>("triviaService", RealTriviaService())
}
```

Now we can start a development server to serve our JavaScript to any running applications that
request it.

```console
$ ./gradlew -p samples trivia:trivia-js:serveDevelopmentZipline --info --continuous
```

Note that this Gradle won't ever reach 100%. That's expected; we want the development server to stay
on. Also note that the `--continuous` flag will trigger a re-compile whenever the code changes.

You can see the served application manifest at
[localhost:8080/manifest.zipline.json](http://localhost:8080/manifest.zipline.json). It references
all the code modules for the application.

In `jvmMain` we need write [a program][launchZiplineJvm.kt] that downloads our Kotlin/JS code and
calls it. We use `ZiplineLoader` which handles code downloading, caching, and loading. We create a
`Dispatcher` to run Kotlin/JS on. This must be a single-threaded dispatcher as each Zipline instance
must be confined to a single thread.

```kotlin
suspend fun launchZipline(dispatcher: CoroutineDispatcher): Zipline {
  val manifestUrl = "http://localhost:8080/manifest.zipline.json"
  val loader = ZiplineLoader(
    dispatcher,
    ManifestVerifier.NO_SIGNATURE_CHECKS,
    OkHttpClient(),
  )
  return loader.loadOnce("trivia", manifestUrl)
}
```

Now we build and run the JVM program to put it all together. Do this in a separate terminal from the
development server!

```console
$ ./gradlew -p samples trivia:trivia-host:shadowJar
java -jar samples/trivia/trivia-host/build/libs/trivia-host-all.jar
```


### Interface bridging

Zipline makes it easy to share interfaces with Kotlin/JS. Define an interface in `commonMain`,
implement it in Kotlin/JS, and call it from the host platform. Or do the opposite: implement it on
the host platform and call it from Kotlin/JS.

Bridged interfaces must extend `ZiplineService`, which defines a single `close()` method to release
held resources.

By default, arguments and return values are pass-by-value. Zipline uses [kotlinx.serialization] to
encode and decode values passed across the boundary.

Interface types that extend from `ZiplineService` are pass-by-reference: the receiver may call
methods on a live instance.

Interface functions may be suspending. Internally Zipline implements `setTimeout()` to make
asynchronous code work as it's supposed to in Kotlin/JS.

Zipline also supports `Flow<T>` as a parameter or return type. This makes it easy to build reactive
systems.


### Fast

One potential bottleneck of embedding JavaScript is waiting for the engine to compile the input
source code. Zipline precompiles JavaScript into efficient QuickJS bytecode to eliminate this
performance penalty.

Another bottleneck is waiting for code to download. Zipline addresses this with support for modular
applications. Each input module (Like Kotlin's standard, serialization, and coroutines libraries)
is downloaded concurrently. Each downloaded module is cached. Modules can also be embedded with the
host application to avoid any downloads if the network is unreachable. If your application module
changes more frequently than your libraries, users only download what's changed.

If you run into performance problems in the QuickJS runtime, Zipline includes a sampling profiler.
You can use this to get a breakdown of how your application spends its CPU time.


### Developer-Friendly

Zipline implements `console.log` by forwarding messages to the host platform. It uses
`android.util.Log` on Android, `java.util.logging` on JVM, and `stdout` on Kotlin/Native.

Zipline integrates Kotlin source maps into QuickJS bytecode. If your process crashes, the stacktrace
will print `.kt` files and line numbers. Even though there's JavaScript underneath, developers don't
need to interface with `.js` files.

After using a bridged interface it must be closed so the peer object can be garbage collected. This
is difficult to get right, so Zipline borrows ideas from [LeakCanary] and aggressively detects
when a `close()` call is missed.


### Secure

Zipline supports [EdDSA Ed25519] and [ECDSA P-256] signatures to authenticate downloaded libraries.

Set up is straightforward. Generate an EdDSA key pair. A task for this is installed with the Zipline
Gradle plugin.

```
$ ./gradlew :generateZiplineManifestKeyPairEd25519
...
---------------- ----------------------------------------------------------------
      ALGORITHM: Ed25519
     PUBLIC KEY: XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
    PRIVATE KEY: YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY
---------------- ----------------------------------------------------------------
...
```

Put the private key on the build server and configure it to sign builds:

```kotlin
zipline {
  signingKeys {
    create("key1") {
      privateKeyHex.set(...)
      algorithmId.set(app.cash.zipline.loader.SignatureAlgorithmId.Ed25519)
    }
  }
}
```

Put the public key in each host application and configure it to verify signatures:

```kotlin
val manifestVerifier = ManifestVerifier.Builder()
  .addEd25519("key1", ...)
  .build()
val loader = ZiplineLoader(
  manifestVerifier = manifestVerifier,
  ...
)
```

Both signing and verifying accept multiple keys to support key rotation.

Zipline is designed to run your organization's code when and where you want it. It does not
offer a sandbox or process-isolation and should not be used to execute untrusted code.

### Trust Model for Signatures

It is essential to keep in mind that this design puts implicit trust on:
1. The Host Application that verifies the signatures.
2. The Build Server that generates the signature(Has access to the signing keys)

It does not protect against any kind of compromise of the above.

Also It does not yet provide a mechanism to outlaw older(signed) versions of executable code that have known problems.


### Speeding Up Hot-Reload
There are a few things you can do to make sure that hot-reload is running as fast as it can:
1. Ensure you are running Gradle 7.5 or later (previous versions had a delay in picking up changed
   files).
2. In your app's gradle.properties add `kotlin.incremental.js.ir=true` to enable Kotlin/JS
   incremental compile.
3. In your app's gradle.properties add `org.gradle.unsafe.configuration-cache=true` to enable the
   Gradle configuration cache.
4. In your app's build.gradle.kts add `tasks.withType(DukatTask::class) { enabled = false }`
   to turn off the Dukat task if you are not using TypeScript type declarations.

### Requirements

Zipline works on Android 4.3+ (API level 18+), Java 8+, and [Kotlin/Native].

Zipline uses unstable APIs in its implementation and is sensitive to version updates for these
components.

| Component            | Supported Version | Notes                                                                       |
|:---------------------|:------------------|:----------------------------------------------------------------------------|
| Kotlin Compiler      | 2.3.0             | Kotlin compiler plugins do not yet have a stable API.                       |
| Kotlin Serialization | 1.10.0            | For `decodeFromDynamic()`, `encodeToDynamic()`, and `ContextualSerializer`. |
| Kotlin Coroutines    | 1.10.2            | For `transformLatest()` and `Deferred.getCompleted()`.                      |

We intend to use stable APIs as soon as they are available.

We intend to keep Zipline host and runtime releases interoperable so you can upgrade each
independently.

| Host Zipline Version  | Supported Runtime Zipline Versions              |
| --------------------: | :---------------------------------------------- |
|                   0.x | Exact same 0.x version as the host.             |
|                   1.x | Any 1.x version.                                |


### License

    Copyright 2015 Square, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


# Duktape-Android

This project was previously known as Duktape-Android and packaged the
[Duktape](https://duktape.org/) JavaScript engine for Android. The Duktape history is still present
in this repo as are the release tags. Available versions are listed on
[Maven central](https://search.maven.org/artifact/com.squareup.duktape/duktape-android).

[ECDSA P-256]: https://en.wikipedia.org/wiki/Elliptic_Curve_Digital_Signature_Algorithm
[EdDSA Ed25519]: https://en.wikipedia.org/wiki/EdDSA
[Kotlin/Native]: https://kotlinlang.org/docs/multiplatform-dsl-reference.html#targets
[LeakCanary]: https://square.github.io/leakcanary/
[kotlinx.serialization]: https://github.com/Kotlin/kotlinx.serialization
[launchZiplineJs.kt]: samples/trivia/trivia-js/src/jsMain/kotlin/app/cash/zipline/samples/trivia/launchZiplineJs.kt
[launchZiplineJvm.kt]: samples/trivia/trivia-host/src/main/kotlin/app/cash/zipline/samples/trivia/launchZiplineJvm.kt
[qjs]: https://bellard.org/quickjs/
[trivia.kt]: samples/trivia/trivia-shared/src/commonMain/kotlin/app/cash/zipline/samples/trivia/trivia.kt
[triviaJs.kt]: samples/trivia/trivia-js/src/jsMain/kotlin/app/cash/zipline/samples/trivia/triviaJs.kt

