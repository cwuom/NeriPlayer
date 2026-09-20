package moe.ouom.neriplayer.core.download;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class ManagedDownloadMigrationTestDocumentProvider extends ContentProvider {
    public static final String AUTHORITY =
        "moe.ouom.neriplayer.test.manageddownloadmigration";
    public static final String ROOT_ID = "migration-root";
    public static final String SOURCE_ROOT_ID = "migration-source-root";
    public static final String TARGET_ROOT_ID = "migration-target-root";
    public static final String RESET = "test:resetMigration";
    public static final String QUERY_FAULT = "test:queryFault";
    public static final String QUERY_COUNT = "test:queryCount";
    public static final String REFERENCE_QUERY_FAULT = "test:referenceQueryFault";
    public static final String PUBLICATION_READ_FAULT = "test:publicationReadFault";
    public static final String PUBLICATION_MISSING_READ = "test:publicationMissingRead";
    public static final String PUBLICATION_CHILDREN_FAULT = "test:publicationChildrenFault";
    public static final String PUBLICATION_WRITE_GATE = "test:publicationWriteGate";
    public static final String AUTO_RENAME_NEXT_COLLISION = "test:autoRenameNextCollision";
    private static volatile String publicationWriteGateName;
    private static volatile boolean autoRenameNextCollision;
    private static volatile CountDownLatch publicationWriteEntered = new CountDownLatch(0);
    private static volatile CountDownLatch publicationWriteRelease = new CountDownLatch(0);

    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_DISPLAY_NAME =
        "android.provider.extra.DISPLAY_NAME";
    private static final String[] DEFAULT_DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private static final Map<String, Node> NODES = new HashMap<>();
    private static volatile String queryFault;
    private static volatile CountDownLatch queryGate = new CountDownLatch(0);
    private static final AtomicInteger childQueryCount = new AtomicInteger();
    private static final AtomicInteger documentQueryCount = new AtomicInteger();
    private static final AtomicInteger rootDocumentQueryCount = new AtomicInteger();
    private static final AtomicInteger documentPathCount = new AtomicInteger();
    private static final AtomicInteger referenceQueryFaultCount = new AtomicInteger();
    private static volatile String referenceQueryFaultId;
    private static volatile String referenceQueryFaultKind;
    private static final AtomicInteger metadataReadCount = new AtomicInteger();
    private static volatile String publicationReadFaultName;
    private static String publicationReadFaultId;
    private static final AtomicInteger publicationReadFaultCount = new AtomicInteger();
    private static String publicationMissingReadId;
    private static final AtomicInteger publicationMissingReadCount = new AtomicInteger();
    private static String publicationChildrenFaultId;
    private static final AtomicInteger publicationChildrenFaultCount = new AtomicInteger();

    @Override
    public boolean onCreate() {
        ensureRoot();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        Node node = nodeFor(documentId(uri));
        return node == null ? null : node.mimeType;
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        String[] columns = projection == null ? DEFAULT_DOCUMENT_COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        String documentId = documentId(uri);
        if (isChildDocumentsUri(uri)) {
            synchronized (NODES) {
                if (documentId.equals(publicationChildrenFaultId)) {
                    publicationChildrenFaultId = null;
                    publicationChildrenFaultCount.incrementAndGet();
                    Bundle extras = new Bundle();
                    extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                    cursor.setExtras(extras);
                    return cursor;
                }
            }
            if (ROOT_ID.equals(documentId)) {
                childQueryCount.incrementAndGet();
            }
            String fault = ROOT_ID.equals(documentId) ? queryFault : null;
            if ("blocked".equals(fault)) {
                try {
                    queryGate.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture query interrupted", error);
                }
            }
            if ("null".equals(fault)) {
                return null;
            }
            if ("dead".equals(fault)) {
                cursor = new MatrixCursor(columns) {
                    @Override
                    public Bundle getExtras() {
                        throw new IllegalStateException("fixture provider disconnected", new DeadObjectException());
                    }
                };
            }
            for (Node child : childrenOf(documentId)) {
                cursor.addRow(documentRow(columns, child));
            }
            return cursor;
        }
        documentQueryCount.incrementAndGet();
        if (ROOT_ID.equals(documentId)) rootDocumentQueryCount.incrementAndGet();
        if (documentId.equals(referenceQueryFaultId)) {
            referenceQueryFaultCount.incrementAndGet();
            if ("null".equals(referenceQueryFaultKind)) return null;
            if ("permission".equals(referenceQueryFaultKind)) {
                throw new SecurityException("fixture exact reference permission denied");
            }
            throw new IllegalStateException("fixture exact reference unavailable");
        }
        Node node = nodeFor(documentId);
        if (node != null) {
            cursor.addRow(documentRow(columns, node));
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Node node = nodeFor(documentId(uri));
        if (node == null || node.directory) {
            throw new FileNotFoundException("Unknown migration fixture document: " + uri);
        }
        if (mode.contains("w") && node.displayName.equals(publicationWriteGateName)) {
            publicationWriteEntered.countDown();
            try {
                if (!publicationWriteRelease.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new FileNotFoundException("publication write test gate timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("publication write test interrupted");
            }
        }
        synchronized (NODES) {
            if (!mode.contains("w") && node.id.equals(publicationMissingReadId)) {
                publicationMissingReadId = null;
                publicationMissingReadCount.incrementAndGet();
                throw new FileNotFoundException("No such file or directory: " + uri);
            }
            if (!mode.contains("w") && node.id.equals(publicationReadFaultId)) {
                publicationReadFaultId = null;
                publicationReadFaultCount.incrementAndGet();
                throw new IllegalStateException("fixture publication metadata readback failure");
            }
        }
        if (!mode.contains("w") && node.displayName.contains(".npmeta")) metadataReadCount.incrementAndGet();
        File file = backingFile(node.id);
        int flags = mode.contains("w")
            ? ParcelFileDescriptor.MODE_READ_WRITE |
                ParcelFileDescriptor.MODE_CREATE |
                (mode.contains("a") ? ParcelFileDescriptor.MODE_APPEND :
                    ParcelFileDescriptor.MODE_TRUNCATE)
            : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return deleteDocument(documentId(uri)) ? 1 : 0;
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        Node node = nodeFor(documentId(uri));
        if (node == null || values == null) {
            return 0;
        }
        Long lastModified = values.getAsLong(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        );
        if (lastModified != null && !node.directory) {
            backingFile(node.id).setLastModified(lastModified);
        }
        return 1;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (PUBLICATION_WRITE_GATE.equals(method)) {
            Bundle result = new Bundle();
            if ("arm".equals(arg)) {
                publicationWriteRelease.countDown();
                publicationWriteEntered = new CountDownLatch(1);
                publicationWriteRelease = new CountDownLatch(1);
                publicationWriteGateName = extras.getString("name");
            } else if ("await".equals(arg)) {
                try {
                    result.putBoolean("entered", publicationWriteEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            } else {
                publicationWriteGateName = null;
                publicationWriteRelease.countDown();
            }
            return result;
        }
        if (AUTO_RENAME_NEXT_COLLISION.equals(method)) {
            autoRenameNextCollision = true;
            return new Bundle();
        }
        if (QUERY_FAULT.equals(method)) {
            queryGate.countDown();
            queryGate = new CountDownLatch("blocked".equals(arg) ? 1 : 0);
            queryFault = arg;
            return new Bundle();
        }
        if (QUERY_COUNT.equals(method)) {
            Bundle result = new Bundle();
            result.putInt("count", childQueryCount.get());
            result.putInt("documentQueries", documentQueryCount.get());
            result.putInt("rootDocumentQueries", rootDocumentQueryCount.get());
            result.putInt("documentPaths", documentPathCount.get());
            result.putInt("referenceQueryFaults", referenceQueryFaultCount.get());
            result.putInt("metadataReads", metadataReadCount.get());
            result.putInt("publicationReadFaults", publicationReadFaultCount.get());
            result.putInt("publicationMissingReads", publicationMissingReadCount.get());
            result.putInt("publicationChildrenFaults", publicationChildrenFaultCount.get());
            return result;
        }
        if (REFERENCE_QUERY_FAULT.equals(method)) {
            referenceQueryFaultId = arg == null ? null : documentId(Uri.parse(arg));
            referenceQueryFaultKind = extras == null ? null : extras.getString("fault");
            referenceQueryFaultCount.set(0);
            return new Bundle();
        }
        if ("android:findDocumentPath".equals(method)) {
            documentPathCount.incrementAndGet();
            Uri target = extras == null ? null : uriExtra(extras);
            List<String> path = new ArrayList<>();
            synchronized (NODES) {
                Node node = target == null ? null : NODES.get(documentId(target));
                while (node != null) {
                    path.add(0, node.id);
                    node = node.parentId == null ? null : NODES.get(node.parentId);
                }
            }
            Bundle result = new Bundle();
            if (!path.isEmpty()) {
                result.putParcelable("result", new DocumentsContract.Path(null, path));
            }
            return result;
        }
        if (PUBLICATION_READ_FAULT.equals(method)) {
            synchronized (NODES) {
                publicationReadFaultName = arg;
                publicationReadFaultId = null;
                publicationReadFaultCount.set(0);
            }
            return new Bundle();
        }
        if (PUBLICATION_MISSING_READ.equals(method)) {
            synchronized (NODES) {
                publicationMissingReadId = arg == null ? null : documentId(Uri.parse(arg));
                publicationMissingReadCount.set(0);
            }
            return new Bundle();
        }
        if (PUBLICATION_CHILDREN_FAULT.equals(method)) {
            synchronized (NODES) {
                publicationChildrenFaultId = arg == null ? null : documentId(Uri.parse(arg));
                publicationChildrenFaultCount.set(0);
            }
            return new Bundle();
        }
        if (RESET.equals(method)) {
            reset();
            return new Bundle();
        }
        if ("android:createDocument".equals(method)) {
            return createDocument(extras);
        }
        if ("android:renameDocument".equals(method)) {
            return renameDocument(extras);
        }
        if ("android:deleteDocument".equals(method)) {
            Uri target = extras == null ? null : uriExtra(extras);
            if (target != null) {
                deleteDocument(documentId(target));
            }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

    private Bundle createDocument(Bundle extras) {
        Uri parentUri = extras == null ? null : uriExtra(extras);
        Node parent = parentUri == null ? null : nodeFor(documentId(parentUri));
        String displayName = extras == null
            ? null
            : extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        if (displayName == null && extras != null) {
            displayName = extras.getString(EXTRA_DISPLAY_NAME);
        }
        String mimeType = extras == null
            ? null
            : extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
        if (parent == null || !parent.directory || displayName == null || mimeType == null) {
            return new Bundle();
        }
        synchronized (NODES) {
            if (hasChildNamed(parent.id, displayName)) {
                if (!autoRenameNextCollision) {
                    return new Bundle();
                }
                autoRenameNextCollision = false;
                displayName = nextAvailableDisplayName(parent.id, displayName);
            }
            String id = "migration-node-" + UUID.randomUUID();
            boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
            NODES.put(id, new Node(id, parent.id, displayName, mimeType, directory));
            if (!directory) {
                backingFile(id);
            }
            return documentResult(id);
        }
    }

    private Bundle renameDocument(Bundle extras) {
        Uri targetUri = extras == null ? null : uriExtra(extras);
        String displayName = extras == null
            ? null
            : extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        if (displayName == null && extras != null) {
            displayName = extras.getString(EXTRA_DISPLAY_NAME);
        }
        Node node = targetUri == null ? null : nodeFor(documentId(targetUri));
        if (node == null || node.parentId == null || displayName == null) {
            return new Bundle();
        }
        synchronized (NODES) {
            if (hasChildNamed(node.parentId, displayName, node.id)) {
                return new Bundle();
            }
            node.displayName = displayName;
            if (ROOT_ID.equals(node.parentId) && displayName.equals(publicationReadFaultName)) {
                try {
                    String metadata = new String(Files.readAllBytes(backingFile(node.id).toPath()), StandardCharsets.UTF_8);
                    if (metadata.contains("\"audioPublicationReceipt\"")) {
                        publicationReadFaultId = node.id;
                        publicationReadFaultName = null;
                    }
                } catch (IOException error) {
                    throw new IllegalStateException("fixture cannot inspect publication metadata", error);
                }
            }
            return documentResult(node.id);
        }
    }

    private Bundle documentResult(String documentId) {
        Bundle result = new Bundle();
        result.putParcelable(
            EXTRA_URI,
            DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        );
        return result;
    }

    private Object[] documentRow(String[] columns, Node node) {
        Object[] row = new Object[columns.length];
        File file = node.directory ? null : backingFile(node.id);
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
                row[index] = node.id;
            } else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)
                || OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = node.displayName;
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)
                || MediaStore.MediaColumns.MIME_TYPE.equals(column)) {
                row[index] = node.mimeType;
            } else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) {
                row[index] = node.directory
                    ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE |
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                        DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                        DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
            } else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)
                || OpenableColumns.SIZE.equals(column)) {
                row[index] = file == null ? 0L : file.length();
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)
                || MediaStore.MediaColumns.DATE_MODIFIED.equals(column)) {
                row[index] = file == null ? 0L : file.lastModified();
            }
        }
        return row;
    }

    private List<Node> childrenOf(String parentId) {
        synchronized (NODES) {
            List<Node> children = new ArrayList<>();
            for (Node node : NODES.values()) {
                if (parentId.equals(node.parentId)) {
                    children.add(node);
                }
            }
            Collections.sort(children, Comparator.comparing(node -> node.displayName));
            return children;
        }
    }

    private boolean hasChildNamed(String parentId, String displayName) {
        return hasChildNamed(parentId, displayName, null);
    }

    private boolean hasChildNamed(String parentId, String displayName, String excludedId) {
        for (Node node : NODES.values()) {
            if (parentId.equals(node.parentId) && !node.id.equals(excludedId)
                && displayName.equals(node.displayName)) {
                return true;
            }
        }
        return false;
    }

    private String nextAvailableDisplayName(String parentId, String requestedName) {
        int extensionIndex = requestedName.lastIndexOf('.');
        String stem = extensionIndex > 0
            ? requestedName.substring(0, extensionIndex)
            : requestedName;
        String extension = extensionIndex > 0
            ? requestedName.substring(extensionIndex)
            : "";
        for (int suffix = 1; ; suffix++) {
            String candidate = stem + " (" + suffix + ")" + extension;
            if (!hasChildNamed(parentId, candidate)) {
                return candidate;
            }
        }
    }

    private boolean deleteDocument(String documentId) {
        if (ROOT_ID.equals(documentId)) {
            return false;
        }
        synchronized (NODES) {
            Node node = NODES.get(documentId);
            if (node == null) {
                return false;
            }
            for (Node child : childrenOf(documentId)) {
                deleteDocument(child.id);
            }
            NODES.remove(documentId);
            if (!node.directory) {
                backingFile(node.id).delete();
            }
            return true;
        }
    }

    private Node nodeFor(String documentId) {
        ensureRoot();
        synchronized (NODES) {
            return NODES.get(documentId);
        }
    }

    private void ensureRoot() {
        synchronized (NODES) {
            ensureRoot(ROOT_ID, "migration-root");
            ensureRoot(SOURCE_ROOT_ID, "migration-source-root");
            ensureRoot(TARGET_ROOT_ID, "migration-target-root");
        }
    }

    private static void ensureRoot(String id, String displayName) {
        if (!NODES.containsKey(id)) {
            NODES.put(
                id,
                new Node(
                    id,
                    null,
                    displayName,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    true
                )
            );
        }
    }

    private void reset() {
        publicationWriteGateName = null;
        autoRenameNextCollision = false;
        publicationWriteRelease.countDown();
        queryGate.countDown();
        queryFault = null;
        childQueryCount.set(0);
        documentQueryCount.set(0);
        rootDocumentQueryCount.set(0);
        documentPathCount.set(0);
        referenceQueryFaultCount.set(0);
        referenceQueryFaultId = null;
        referenceQueryFaultKind = null;
        metadataReadCount.set(0);
        synchronized (NODES) {
            publicationReadFaultName = null;
            publicationReadFaultId = null;
            publicationReadFaultCount.set(0);
            publicationMissingReadId = null;
            publicationMissingReadCount.set(0);
            publicationChildrenFaultId = null;
            publicationChildrenFaultCount.set(0);
            NODES.clear();
            deleteRecursively(backingDirectory());
            ensureRoot();
        }
    }

    private File backingDirectory() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        return new File(getContext().getCacheDir(), "managed-download-migration-provider");
    }

    private File backingFile(String documentId) {
        File directory = backingDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create migration fixture directory");
        }
        return new File(directory, documentId);
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static boolean isChildDocumentsUri(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return !segments.isEmpty() && "children".equals(segments.get(segments.size() - 1));
    }

    private static String documentId(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException ignored) {
            List<String> segments = uri.getPathSegments();
            int childrenIndex = segments.indexOf("children");
            if (childrenIndex > 0) {
                return segments.get(childrenIndex - 1);
            }
            return segments.isEmpty() ? ROOT_ID : segments.get(segments.size() - 1);
        }
    }

    private static Uri uriExtra(Bundle extras) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return extras.getParcelable(EXTRA_URI, Uri.class);
            }
            Object value = Bundle.class
                .getMethod("getParcelable", String.class)
                .invoke(extras, EXTRA_URI);
            return value instanceof Uri ? (Uri) value : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static final class Node {
        final String id;
        final String parentId;
        String displayName;
        final String mimeType;
        final boolean directory;

        Node(
            String id,
            String parentId,
            String displayName,
            String mimeType,
            boolean directory
        ) {
            this.id = id;
            this.parentId = parentId;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.directory = directory;
        }
    }
}
