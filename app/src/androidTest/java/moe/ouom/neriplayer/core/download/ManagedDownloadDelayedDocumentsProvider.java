package moe.ouom.neriplayer.core.download;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用平台 DocumentsProvider 的权限、tree 和 call 分发，独立测试进程不依赖 Kotlin runtime */
public final class ManagedDownloadDelayedDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "moe.ouom.neriplayer.test.delayeddocuments";
    public static final String SETUP = "test:setupDelayedDocuments";
    public static final String COUNTERS = "test:delayedDocumentsCounters";
    public static final String CLEANUP = "test:cleanupDelayedDocuments";
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private File fixtureRoot;
    private String rootId = "";
    private long delayMs;
    private boolean rejectBatch;
    private String deniedName;
    private final AtomicInteger deleteCalls = new AtomicInteger();
    private final AtomicInteger batchCalls = new AtomicInteger();
    private final AtomicInteger activeDeletes = new AtomicInteger();
    private final AtomicInteger maximumActiveDeletes = new AtomicInteger();

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle platformResult = super.call(method, arg, extras);
        if (platformResult != null) return platformResult;
        if (SETUP.equals(method)) {
            if (fixtureRoot != null && fixtureRoot.exists()) throw new IllegalStateException("fixture is active");
            Objects.requireNonNull(extras);
            rootId = "primary:delete-test-" + UUID.randomUUID();
            try {
                fixtureRoot = new File(Objects.requireNonNull(getContext()).getCacheDir(), rootId.substring(8)).getCanonicalFile();
                if (!fixtureRoot.mkdir()) throw new IOException("cannot create fixture");
                delayMs = extras.getLong("delayMs");
                rejectBatch = extras.getBoolean("rejectBatch");
                deniedName = extras.getString("deniedName");
                deleteCalls.set(0);
                batchCalls.set(0);
                maximumActiveDeletes.set(0);
                for (int index = 0; index < extras.getInt("count"); index++) {
                    Files.write(new File(fixtureRoot, "song-" + index + ".mp3").toPath(),
                        ("audio-" + index).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            Uri treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId);
            getContext().grantUriPermission(Objects.requireNonNull(extras.getString("packageName")), treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            Bundle result = new Bundle();
            result.putString("treeUri", treeUri.toString());
            return result;
        }
        if (COUNTERS.equals(method)) {
            Bundle result = new Bundle();
            result.putInt("deleteCalls", deleteCalls.get());
            result.putInt("batchCalls", batchCalls.get());
            result.putInt("maximumActiveDeletes", maximumActiveDeletes.get());
            result.putInt("remainingFiles", Objects.requireNonNull(fixtureRoot.listFiles()).length);
            return result;
        }
        if (CLEANUP.equals(method)) {
            deniedName = null;
            if (fixtureRoot != null && fixtureRoot.exists()) {
                for (File child : Objects.requireNonNull(fixtureRoot.listFiles())) {
                    if (!child.delete()) throw new IllegalStateException("cannot remove fixture child");
                }
                if (!fixtureRoot.delete()) throw new IllegalStateException("cannot remove fixture root");
            }
            return new Bundle();
        }
        return null;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
        throws OperationApplicationException {
        batchCalls.incrementAndGet();
        if (rejectBatch) throw new UnsupportedOperationException("fixture rejects batch calls");
        return super.applyBatch(operations);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, rootId)
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootId);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        addDocument(cursor, documentId, file(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
        throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        File[] children = Objects.requireNonNull(file(parentDocumentId).listFiles());
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) addDocument(cursor, parentDocumentId + "/" + child.getName(), child);
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            return file(documentId).getPath().startsWith(file(parentDocumentId).getPath() + File.separator);
        } catch (FileNotFoundException error) {
            throw new IllegalArgumentException(error);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
        throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = file(documentId);
        if (target.equals(fixtureRoot)) throw new IllegalStateException("cannot delete fixture root");
        deleteCalls.incrementAndGet();
        if (target.getName().equals(deniedName)) throw new SecurityException("fixture permission denied");
        int active = activeDeletes.incrementAndGet();
        maximumActiveDeletes.accumulateAndGet(active, Math::max);
        try {
            SystemClock.sleep(delayMs);
            if (!target.delete()) throw new IllegalStateException("cannot delete document");
        } finally {
            activeDeletes.decrementAndGet();
        }
    }

    private File file(String documentId) throws FileNotFoundException {
        if (!documentId.equals(rootId) && !documentId.startsWith(rootId + "/")) {
            throw new FileNotFoundException("No root for " + documentId);
        }
        String relative = documentId.substring(rootId.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        File candidate;
        try {
            candidate = new File(fixtureRoot, relative).getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalArgumentException(error);
        }
        if (!candidate.equals(fixtureRoot) && !candidate.getPath().startsWith(fixtureRoot.getPath() + File.separator)) {
            throw new SecurityException("document outside fixture");
        }
        if (!candidate.exists()) throw new FileNotFoundException("Missing file for " + documentId);
        return candidate;
    }

    private void addDocument(MatrixCursor cursor, String documentId, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE,
            file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/mpeg");
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length());
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }
}
