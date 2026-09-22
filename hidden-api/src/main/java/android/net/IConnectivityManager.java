package android.net;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Compile-only signature for the system connectivity binder. Android supplies the real class
 * at runtime; this stub must never be packaged with the application.
 */
public interface IConnectivityManager extends IInterface {
    void setFirewallChainEnabled(int chain, boolean enable) throws RemoteException;

    void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException;

    abstract class Stub extends Binder implements IConnectivityManager {
        public static IConnectivityManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
