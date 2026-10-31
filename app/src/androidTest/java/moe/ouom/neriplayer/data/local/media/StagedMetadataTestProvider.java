package moe.ouom.neriplayer.data.local.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import moe.ouom.neriplayer.test.BuildConfig;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public final class StagedMetadataTestProvider extends ContentProvider {
    public static final String AUTHORITY =
        BuildConfig.APPLICATION_ID + ".stagedmetadataprovider";
    public static final String DIRECTORY_NAME = "staged_metadata_test";
    public static final String DISPLAY_NAME = "staged-content-probe.m4a";
    public static final Uri CONTENT_URI = Uri.parse(
        "content://" + AUTHORITY + "/audio/" + DISPLAY_NAME
    );

    private boolean failNextWrite;
    private final ArrayList<String> writeModes = new ArrayList<>();

    @Override
    public Bundle call(@NonNull String method, String arg, Bundle extras) {
        if ("failNextWrite".equals(method)) {
            failNextWrite = true;
            return new Bundle();
        }
        if ("resetWriteModes".equals(method)) {
            writeModes.clear();
            return new Bundle();
        }
        if ("writeModes".equals(method)) {
            Bundle result = new Bundle();
            result.putStringArrayList("modes", new ArrayList<>(writeModes));
            return result;
        }
        if ("replaceWithSameBytes".equals(method)) {
            File file = backingFile();
            try {
                File replacement = File.createTempFile("replacement-", ".m4a", file.getParentFile());
                try {
                    Files.copy(file.toPath(), replacement.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if (!replacement.renameTo(file)) throw new IOException("Unable to replace provider object");
                } finally {
                    if (replacement.exists() && !replacement.delete()) {
                        throw new IOException("Unable to remove replacement fixture");
                    }
                }
                return new Bundle();
            } catch (IOException error) {
                throw new IllegalStateException("Unable to replace provider object", error);
            }
        }
        return super.call(method, arg, extras);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        return "audio/mp4";
    }

    @Override
    public Cursor query(
        @NonNull Uri uri,
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
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        File file = backingFile();
        if (mode.contains("w")) writeModes.add(mode);
        if ("rw".equals(mode) && "true".equals(uri.getQueryParameter("noRw"))) {
            throw new FileNotFoundException("The provider does not support direct rw access");
        }
        if ("r".equals(mode) && "true".equals(uri.getQueryParameter("pipe"))) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (FileInputStream input = new FileInputStream(file);
                         ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                    } catch (IOException ignored) {
                        // 消费者提前关闭时结束本次受控管道写入
                    }
                }, "metadata-pipe-fixture").start();
                return pipe[0];
            } catch (IOException error) {
                throw new FileNotFoundException(error.getMessage());
            }
        }
        if (mode.contains("w") && (failNextWrite || "true".equals(uri.getQueryParameter("readOnlyWrites")))) {
            failNextWrite = false;
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        failNextWrite = false;
        writeModes.clear();
        return backingFile().delete() ? 1 : 0;
    }

    @Override
    public int update(
        @NonNull Uri uri,
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
