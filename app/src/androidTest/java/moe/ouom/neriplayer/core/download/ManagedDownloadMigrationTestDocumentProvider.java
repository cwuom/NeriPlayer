package moe.ouom.neriplayer.core.download;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ManagedDownloadMigrationTestDocumentProvider extends ContentProvider {
    public static final String AUTHORITY =
        "moe.ouom.neriplayer.test.manageddownloadmigration";
    public static final String ROOT_ID = "migration-root";
    public static final String SOURCE_ROOT_ID = "migration-source-root";
    public static final String TARGET_ROOT_ID = "migration-target-root";
    public static final String RESET = "test:resetMigration";

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
    private static long nextNodeId;

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
            for (Node child : childrenOf(documentId)) {
                cursor.addRow(documentRow(columns, child));
            }
            return cursor;
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
                return new Bundle();
            }
            String id = "migration-node-" + (++nextNodeId);
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
        synchronized (NODES) {
            NODES.clear();
            nextNodeId = 0L;
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
