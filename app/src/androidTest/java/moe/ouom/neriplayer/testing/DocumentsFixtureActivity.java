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
    public static final String REQUEST_ID = "requestId";
    public static final String CANCEL = "moe.ouom.neriplayer.test.CANCEL_DOCUMENTS_FIXTURE";
    private static final int PICK_TREE = 1;
    private ResultReceiver receiver;
    private ResultReceiver cleanupReceiver;
    private Bundle pendingResult;
    private int pendingCode = RESULT_CANCELED;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        receiver = resultReceiver(getIntent());
        if (CANCEL.equals(getIntent().getAction())) {
            cleanupReceiver = receiver;
            receiver = null;
            finishAndRemoveTask();
            return;
        }
        if (state != null) return;
        try {
            Bundle setup = getIntent().getBundleExtra(SETUP);
            if (setup != null) {
                Bundle result = getContentResolver().call(
                    Uri.parse("content://" + ManagedDownloadDelayedDocumentsProvider.AUTHORITY),
                    ManagedDownloadDelayedDocumentsProvider.SETUP, null, setup);
                complete(RESULT_OK, result);
            } else {
                Uri initial = Objects.requireNonNull(getIntent().getData());
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), PICK_TREE);
            }
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ResultReceiver callback = resultReceiver(intent);
        if (CANCEL.equals(intent.getAction())
            && Objects.equals(intent.getStringExtra(REQUEST_ID), getIntent().getStringExtra(REQUEST_ID))) {
            cleanupReceiver = callback;
            finishActivity(PICK_TREE);
            fail("directory fixture was cancelled");
        } else if (callback != null) {
            Bundle result = new Bundle();
            result.putString("error", "another directory fixture is still active");
            callback.send(RESULT_CANCELED, result);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 在销毁回调中通知测试，窗口焦点恢复仍由后续 UI 断言确认
        if (isFinishing()) {
            if (receiver != null && pendingResult != null) receiver.send(pendingCode, pendingResult);
            if (cleanupReceiver != null) cleanupReceiver.send(RESULT_OK, Bundle.EMPTY);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TREE || isFinishing()) return;
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
            complete(RESULT_OK, result);
        } catch (RuntimeException error) {
            fail(error.toString());
        }
    }

    private void fail(String message) {
        Bundle result = new Bundle();
        result.putString("error", message);
        complete(RESULT_CANCELED, result);
    }

    private void complete(int code, Bundle result) {
        pendingCode = code;
        pendingResult = result;
        finishAndRemoveTask();
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver resultReceiver(Intent intent) {
        // 旧版本只能通过未指定类型的 Parcelable API 读取回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(RECEIVER);
    }
}
