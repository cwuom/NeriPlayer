package moe.ouom.neriplayer.data.local.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Issue339LyricsTestDocumentProvider extends ContentProvider {
    public static final String AUTHORITY = "moe.ouom.neriplayer.test.issue339lyrics";
    public static final String ROOT_ID = "root-issue339";
    public static final String MUSIC_ID = "opaque/folder-issue339";
    // document IDs are opaque provider values and may contain slash characters
    public static final String AUDIO_ID = "opaque/audio-issue339";
    public static final String LYRICS_ID = "opaque/lyrics-issue339";
    public static final String ORIGINAL_ID = "opaque/original-issue339";
    public static final String TRANSLATED_ID = "opaque/translated-issue339";
    public static final String ROMANIZED_ID = "opaque/romanized-issue339";
    public static final String METADATA_ID = "opaque/metadata-issue339";
    public static final String AUDIO_NAME = "netease - 茶太 - だんご大家族.wav";
    public static final String ORIGINAL_NAME = "netease - 茶太 - だんご大家族.lrc";
    public static final String TRANSLATED_NAME = "netease - 茶太 - だんご大家族_trans.lrc";
    public static final String ROMANIZED_NAME = "netease - 茶太 - だんご大家族_roma.lrc";
    public static final String METADATA_NAME = AUDIO_NAME + ".npmeta.json";

    private static final long FIXTURE_LAST_MODIFIED = 1_700_000_000_000L;
    private static final String[] DEFAULT_DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private static final byte[] EMPTY_CONTENT = new byte[0];
    private static final byte[] ORIGINAL_CONTENT =
        "[00:00.10]original from Lyrics".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSLATED_CONTENT =
        "[00:00.10]translated from Lyrics".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ROMANIZED_CONTENT =
        "[00:00.10]romanized from Lyrics".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIO_CONTENT = buildWaveContent();
    private static final String METADATA_DIRECTORY_NAME = "issue339-metadata";
    private static final String EXTRA_URI = "uri";
    private static final int DIRECTORY_FLAGS =
        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return mimeTypeFor(documentId(uri));
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
        if (isChildDocumentsUri(uri)) {
            for (String childId : childrenFor(documentId(uri))) {
                cursor.addRow(documentRow(columns, childId));
            }
        } else {
            cursor.addRow(documentRow(columns, documentId(uri)));
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (METADATA_ID.equals(documentId(uri))) {
            try {
                File file = metadataFile();
                int flags = mode.contains("w")
                    ? ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                    : ParcelFileDescriptor.MODE_READ_ONLY;
                return ParcelFileDescriptor.open(file, flags);
            } catch (IOException error) {
                FileNotFoundException failure = new FileNotFoundException(
                    "Unable to open Issue #339 metadata: " + uri
                );
                failure.initCause(error);
                throw failure;
            }
        }
        if (!mode.contains("r")) {
            throw new FileNotFoundException("read-only test provider");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            byte[] content = contentFor(documentId(uri));
            Thread writer = new Thread(() -> writePipe(pipe[1], content), "issue339-lyrics-fixture");
            writer.start();
            return pipe[0];
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException(
                "Unable to open Issue #339 fixture: " + uri
            );
            failure.initCause(error);
            throw failure;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (METADATA_ID.equals(documentId(uri))) {
            return metadataFile().delete() ? 1 : 0;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("android:findDocumentPath".equals(method)) {
            Bundle result = new Bundle();
            result.putParcelable(
                "result",
                new DocumentsContract.Path(null, Arrays.asList(ROOT_ID, MUSIC_ID, AUDIO_ID))
            );
            return result;
        }
        if ("android:createDocument".equals(method)) {
            Bundle result = new Bundle();
            result.putParcelable(
                EXTRA_URI,
                DocumentsContract.buildDocumentUri(
                    AUTHORITY,
                    METADATA_ID
                )
            );
            return result;
        }
        if ("android:deleteDocument".equals(method)) {
            Uri target = null;
            if (extras != null) {
                Object rawTarget = Build.VERSION.SDK_INT >= 33
                    ? extras.getParcelable(EXTRA_URI, Uri.class)
                    : extras.get(EXTRA_URI);
                if (rawTarget instanceof Uri) {
                    target = (Uri) rawTarget;
                }
            }
            if (target != null && METADATA_ID.equals(documentId(target))) {
                metadataFile().delete();
            }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

    private static void writePipe(ParcelFileDescriptor writeSide, byte[] content) {
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            output.write(content);
        } catch (IOException ignored) {
            // the client can stop reading while metadata probing is still in progress
        }
    }

    private Object[] documentRow(String[] columns, String documentId) {
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) {
                row[index] = documentId;
            } else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)
                || OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = displayNameFor(documentId);
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)
                || MediaStore.MediaColumns.MIME_TYPE.equals(column)) {
                row[index] = mimeTypeFor(documentId);
            } else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) {
                row[index] = isDirectory(documentId) ? DIRECTORY_FLAGS : 0;
            } else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)
                || OpenableColumns.SIZE.equals(column)) {
                row[index] = contentFor(documentId).length;
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)
                || MediaStore.MediaColumns.DATE_MODIFIED.equals(column)) {
                row[index] = FIXTURE_LAST_MODIFIED / 1_000L;
            }
        }
        return row;
    }

    private List<String> childrenFor(String parentDocumentId) {
        if (ROOT_ID.equals(parentDocumentId)) {
            return Collections.singletonList(MUSIC_ID);
        }
        if (MUSIC_ID.equals(parentDocumentId)) {
            if (metadataFile().isFile()) {
                return Arrays.asList(AUDIO_ID, LYRICS_ID, METADATA_ID);
            }
            return Arrays.asList(AUDIO_ID, LYRICS_ID);
        }
        if (LYRICS_ID.equals(parentDocumentId)) {
            return Arrays.asList(ORIGINAL_ID, TRANSLATED_ID, ROMANIZED_ID);
        }
        return Collections.emptyList();
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

    private static String displayNameFor(String documentId) {
        if (ROOT_ID.equals(documentId)) return "Issue 339";
        if (MUSIC_ID.equals(documentId)) return "my";
        if (AUDIO_ID.equals(documentId)) return AUDIO_NAME;
        if (LYRICS_ID.equals(documentId)) return "Lyrics";
        if (ORIGINAL_ID.equals(documentId)) return ORIGINAL_NAME;
        if (TRANSLATED_ID.equals(documentId)) return TRANSLATED_NAME;
        if (ROMANIZED_ID.equals(documentId)) return ROMANIZED_NAME;
        if (METADATA_ID.equals(documentId)) return METADATA_NAME;
        return documentId;
    }

    private static String mimeTypeFor(String documentId) {
        if (ROOT_ID.equals(documentId) || MUSIC_ID.equals(documentId) ||
            LYRICS_ID.equals(documentId)) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        if (AUDIO_ID.equals(documentId)) return "audio/wav";
        if (METADATA_ID.equals(documentId)) return "application/json";
        return "text/plain";
    }

    private static boolean isDirectory(String documentId) {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeTypeFor(documentId));
    }

    private byte[] contentFor(String documentId) {
        if (AUDIO_ID.equals(documentId)) return AUDIO_CONTENT;
        if (ORIGINAL_ID.equals(documentId)) return ORIGINAL_CONTENT;
        if (TRANSLATED_ID.equals(documentId)) return TRANSLATED_CONTENT;
        if (ROMANIZED_ID.equals(documentId)) return ROMANIZED_CONTENT;
        if (METADATA_ID.equals(documentId)) {
            try {
                return java.nio.file.Files.readAllBytes(metadataFile().toPath());
            } catch (IOException ignored) {
                return EMPTY_CONTENT;
            }
        }
        return EMPTY_CONTENT;
    }

    private File metadataFile() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        File directory = new File(getContext().getCacheDir(), METADATA_DIRECTORY_NAME);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create metadata fixture directory");
        }
        return new File(directory, METADATA_NAME);
    }

    private static byte[] buildWaveContent() {
        int sampleRate = 8_000;
        int sampleCount = sampleRate;
        int dataSize = sampleCount * 2;
        ByteBuffer bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[] { 82, 73, 70, 70 });
        bytes.putInt(36 + dataSize);
        bytes.put(new byte[] { 87, 65, 86, 69 });
        bytes.put(new byte[] { 102, 109, 116, 32 });
        bytes.putInt(16);
        bytes.putShort((short) 1);
        bytes.putShort((short) 1);
        bytes.putInt(sampleRate);
        bytes.putInt(sampleRate * 2);
        bytes.putShort((short) 2);
        bytes.putShort((short) 16);
        bytes.put(new byte[] { 100, 97, 116, 97 });
        bytes.putInt(dataSize);
        for (int index = 0; index < sampleCount; index++) {
            bytes.putShort((short) 0);
        }
        return bytes.array();
    }
}
