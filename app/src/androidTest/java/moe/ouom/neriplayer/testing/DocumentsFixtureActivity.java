package moe.ouom.neriplayer.testing;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.ResultReceiver;
import android.provider.DocumentsContract;

import java.util.Objects;

import moe.ouom.neriplayer.core.download.ManagedDownloadDelayedDocumentsProvider;

/** 测试 APK 在自己的 UID 下准备 Provider，系统目录仍通过文件选择器授权 */
public final class DocumentsFixtureActivity extends Activity {
    public static final String RECEIVER = "receiver";
    public static final String TARGET_PACKAGE = "targetPackage";
    public static final String SETUP = "setup";
    private ResultReceiver receiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        receiver = resultReceiver();
        if (state != null) return;
        try {
            Bundle setup = getIntent().getBundleExtra(SETUP);
            if (setup != null) {
                Bundle result = getContentResolver().call(
                    Uri.parse("content://" + ManagedDownloadDelayedDocumentsProvider.AUTHORITY),
                    ManagedDownloadDelayedDocumentsProvider.SETUP, null, setup);
                Objects.requireNonNull(receiver).send(RESULT_OK, result);
                finish();
            } else {
                Uri initial = Objects.requireNonNull(getIntent().getData());
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), 1);
            }
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1) return;
        try {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                throw new IllegalStateException("directory grant was cancelled");
            }
            Uri tree = data.getData();
            Uri initial = Objects.requireNonNull(getIntent().getData());
            if (!Objects.equals(tree.getAuthority(), initial.getAuthority())
                || !DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getDocumentId(initial))) {
                throw new IllegalStateException("picker returned a different directory: " + tree);
            }
            grantUriPermission(Objects.requireNonNull(getIntent().getStringExtra(TARGET_PACKAGE)), tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            Bundle result = new Bundle();
            result.putString("treeUri", tree.toString());
            Objects.requireNonNull(receiver).send(RESULT_OK, result);
            finish();
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    private void fail(String message) {
        Bundle result = new Bundle();
        result.putString("error", message);
        Objects.requireNonNull(receiver).send(RESULT_CANCELED, result);
        finish();
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver resultReceiver() {
        // 旧版本只能通过未指定类型的 Parcelable API 读取回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getIntent().getParcelableExtra(RECEIVER, ResultReceiver.class);
        }
        return getIntent().getParcelableExtra(RECEIVER);
    }
}
