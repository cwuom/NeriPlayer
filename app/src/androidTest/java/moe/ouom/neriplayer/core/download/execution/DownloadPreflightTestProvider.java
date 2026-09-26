package moe.ouom.neriplayer.core.download.execution;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DownloadPreflightTestProvider extends ContentProvider {
    public static final String AUTHORITY = "moe.ouom.neriplayer.test.downloadpreflight";
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> referenceQueries = new ConcurrentHashMap<>();
    private volatile long delayMs;

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        queries.incrementAndGet();
        referenceQueries.computeIfAbsent(uri.toString(), ignored -> new AtomicInteger()).incrementAndGet();
        if (delayMs > 0L) SystemClock.sleep(delayMs);
        String id = uri.getLastPathSegment();
        if (id == null) id = "";
        if (id.startsWith("permission")) throw new SecurityException("preflight fixture permission lost");
        if (id.startsWith("failure")) throw new IllegalStateException("preflight fixture provider failure");
        String[] columns = projection != null ? projection : new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID };
        MatrixCursor cursor = new MatrixCursor(columns);
        if (!id.startsWith("missing")) {
            Object[] row = new Object[columns.length];
            for (int index = 0; index < columns.length; index++) {
                switch (columns[index]) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                        row[index] = id;
                        break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:
                        row[index] = "audio/mpeg";
                        break;
                    case DocumentsContract.Document.COLUMN_SIZE:
                        row[index] = 1L;
                        break;
                    default:
                        row[index] = null;
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        opens.incrementAndGet();
        if (delayMs > 0L) SystemClock.sleep(delayMs);
        File file = new File(getContext().getCacheDir(), "download-preflight-fixture.mp3");
        if (!file.exists()) {
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(1);
            } catch (IOException error) {
                FileNotFoundException failure = new FileNotFoundException("cannot write preflight fixture");
                failure.initCause(error);
                throw failure;
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        Bundle result = new Bundle();
        switch (method) {
            case "reset":
                queries.set(0);
                opens.set(0);
                referenceQueries.clear();
                delayMs = argument == null ? 0L : Long.parseLong(argument);
                break;
            case "stats":
                result.putInt("queries", queries.get());
                result.putInt("opens", opens.get());
                int maximum = 0;
                for (AtomicInteger count : referenceQueries.values()) maximum = Math.max(maximum, count.get());
                result.putInt("maxReferenceQueries", maximum);
                break;
            case "cleanup":
                new File(getContext().getCacheDir(), "download-preflight-fixture.mp3").delete();
                delayMs = 0L;
                break;
            default:
                break;
        }
        return result;
    }

    @Override
    public String getType(Uri uri) { return "audio/mpeg"; }
    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
