package moe.ouom.neriplayer.common.locale

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS")
class LanguageManagerConcurrencyTest {

    @Test
    fun oldApplicationReaderCannotOverwriteANewerSystemLanguageSelection() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val originalLocale = Locale.getDefault()
        val previousLanguage = LanguageManager.getCurrentLanguage(application)
        val preferences = application.getSharedPreferences("language_settings", Context.MODE_PRIVATE)
        val controlledPreferences = mock(SharedPreferences::class.java)
        val platformLocales = AtomicReference(LocaleList.getEmptyLocaleList())
        val localeManager = mock(LocaleManager::class.java)
        val readerThread = AtomicReference<Thread>()
        val writerThread = AtomicReference<Thread>()
        val oldLanguageRead = CountDownLatch(1)
        val resumeReader = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val paused = AtomicBoolean()
        val executor = Executors.newFixedThreadPool(2)
        var reader: Future<Context>? = null
        var writer: Future<*>? = null

        `when`(controlledPreferences.edit()).thenAnswer { preferences.edit() }
        `when`(controlledPreferences.getString("selected_language", "")).thenAnswer {
            val value = preferences.getString("selected_language", "")
            if (Thread.currentThread() === readerThread.get() && paused.compareAndSet(false, true)) {
                oldLanguageRead.countDown()
                assertTrue("reader was never released", resumeReader.await(10, TimeUnit.SECONDS))
            }
            value
        }
        `when`(localeManager.applicationLocales).thenAnswer { platformLocales.get() }
        doAnswer { call ->
            platformLocales.set(call.getArgument(0))
            null
        }.`when`(localeManager).setApplicationLocales(any(LocaleList::class.java))
        val base = application.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList(Locale.FRENCH))
        })
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "language_settings") controlledPreferences else super.getSharedPreferences(name, mode)

            override fun getSystemService(name: String): Any? =
                if (name == LOCALE_SERVICE) localeManager else super.getSystemService(name)
        }

        try {
            assertEquals(Locale.US, Resources.getSystem().configuration.locales[0])
            LanguageManager.setLanguage(context, LanguageManager.Language.CHINESE)
            reader = executor.submit<Context> {
                readerThread.set(Thread.currentThread())
                LanguageManager.applyLanguage(context)
            }
            assertTrue("reader did not capture the old language", oldLanguageRead.await(10, TimeUnit.SECONDS))
            writer = executor.submit {
                writerThread.set(Thread.currentThread())
                writerStarted.countDown()
                LanguageManager.setLanguage(context, LanguageManager.Language.SYSTEM)
            }
            assertTrue("writer did not start", writerStarted.await(10, TimeUnit.SECONDS))
            awaitWriterCompletionOrReaderOwnership(writer, writerThread.get(), readerThread.get())
            resumeReader.countDown()
            val oldContext = reader.get(10, TimeUnit.SECONDS)
            writer.get(10, TimeUnit.SECONDS)

            assertEquals(LanguageManager.Language.SYSTEM, LanguageManager.getCurrentLanguage(context))
            assertEquals(LocaleList.getEmptyLocaleList(), platformLocales.get())
            assertEquals(Locale.US, Locale.getDefault())
            val currentContext = LanguageManager.applyLanguage(context)
            assertEquals(Locale.US, currentContext.resources.configuration.locales[0])
            assertNotSame(oldContext, currentContext)
            assertSame(currentContext, LanguageManager.applyLanguage(context))
        } finally {
            resumeReader.countDown()
            reader?.cancel(true)
            writer?.cancel(true)
            executor.shutdownNow()
            try {
                assertTrue("locale workers did not stop", executor.awaitTermination(10, TimeUnit.SECONDS))
            } finally {
                LanguageManager.setLanguage(context, previousLanguage)
                Locale.setDefault(originalLocale)
            }
        }
    }

    private fun awaitWriterCompletionOrReaderOwnership(writer: Future<*>, writerThread: Thread, readerThread: Thread) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!writer.isDone) {
            val owner = threads.getThreadInfo(threads.allThreadIds)
                .firstOrNull { it?.threadName == writerThread.name }?.lockOwnerName
            if (owner == readerThread.name) return
            check(System.nanoTime() < deadline) { "writer neither finished nor waited for the paused reader" }
            Thread.onSpinWait()
        }
    }
}
