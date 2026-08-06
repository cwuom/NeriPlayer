package moe.ouom.neriplayer.data.local.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class StagedMetadataTestProvider extends ContentProvider {
    public static final String AUTHORITY =
        "moe.ouom.neriplayer.test.stagedmetadataprovider";
    public static final String DIRECTORY_NAME = "staged_metadata_test";
    public static final String DISPLAY_NAME = "staged-content-probe.m4a";
    public static final Uri CONTENT_URI = Uri.parse(
        "content://" + AUTHORITY + "/audio/" + DISPLAY_NAME
    );

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "audio/mp4";
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        String[] columns = projection != null
            ? projection
            : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        File file = backingFile();
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = DISPLAY_NAME;
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[index] = file.length();
            } else if (MediaStore.MediaColumns.MIME_TYPE.equals(column)) {
                row[index] = "audio/mp4";
            } else if (MediaStore.MediaColumns.DATE_MODIFIED.equals(column)) {
                row[index] = file.lastModified() / 1_000L;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = backingFile();
        if ("rw".equals(mode)) {
            throw new FileNotFoundException("The provider does not support direct rw access");
        }
        int flags = mode.contains("w")
            ? ParcelFileDescriptor.MODE_READ_WRITE
                | ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE
            : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return backingFile().delete() ? 1 : 0;
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        return 0;
    }

    private File backingFile() {
        if (getContext() == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        File directory = new File(getContext().getCacheDir(), DIRECTORY_NAME);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException(
                "Unable to create staged metadata test directory"
            );
        }
        return new File(directory, DISPLAY_NAME);
    }
}
