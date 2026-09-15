package moe.ouom.neriplayer.core.download.execution

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionNotificationInstrumentationTest {
    private lateinit var notificationManager: NotificationManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        notificationManager = requireNotNull(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantRuntimePermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        notificationManager.cancel(DOWNLOAD_EXECUTION_NOTIFICATION_ID)
        assertTrue(
            "通知权限未授予，无法验证系统通知栏中的实际卡片",
            notificationManager.areNotificationsEnabled()
        )
    }

    @After
    fun tearDown() {
        notificationManager.cancel(DOWNLOAD_EXECUTION_NOTIFICATION_ID)
    }

    @Test
    fun sharedNotificationPostsOneCardWithSongCountsAndProgress() {
        val snapshot = DownloadExecutionNotificationSnapshot(
            totalSongs = 5,
            completedSongs = 2,
            remainingSongs = 3,
            overallPercentage = 48,
            currentSong = "模拟歌曲",
            currentTransfer = "48% · 4 MB / 8 MB",
            hasWork = true
        )
        val notification = buildDownloadExecutionNotification(context, snapshot)

        notificationManager.notify(DOWNLOAD_EXECUTION_NOTIFICATION_ID, notification)

        val posted = waitForPostedNotification()
        assertEquals(1, posted.size)
        val extras = posted.single().notification.extras
        assertNotNull(extras)
        assertEquals(100, extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX))
        assertEquals(48, extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertFalse(
            extras.getBoolean(NotificationCompat.EXTRA_PROGRESS_INDETERMINATE)
        )
        assertEquals(
            context.getString(R.string.download_execution_notification_title),
            extras.getString(Notification.EXTRA_TITLE)
        )
        val expectedContent = context.getString(
            R.string.download_execution_notification_progress_with_percentage,
            2,
            5,
            3,
            48
        )
        assertEquals(
            expectedContent,
            extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
        )
        val detail = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
        assertTrue(detail?.contains(expectedContent) == true)
        assertTrue(detail?.contains("模拟歌曲") == true)
        assertTrue(detail?.contains("4 MB / 8 MB") == true)
    }

    private fun waitForPostedNotification(): List<StatusBarNotification> {
        repeat(20) {
            val posted = notificationManager.activeNotifications
                .filter { active -> active.id == DOWNLOAD_EXECUTION_NOTIFICATION_ID }
            if (posted.isNotEmpty()) return posted
            SystemClock.sleep(50L)
        }
        return notificationManager.activeNotifications
            .filter { active -> active.id == DOWNLOAD_EXECUTION_NOTIFICATION_ID }
    }
}
