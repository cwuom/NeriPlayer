package android.os;

/** Compile-only signature for the legacy network-management binder. */
public interface INetworkManagementService extends IInterface {
    void setFirewallChainEnabled(int chain, boolean enable) throws RemoteException;

    void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException;

    void setFirewallUidRule(int chain, int uid, int rule) throws RemoteException;

    abstract class Stub extends Binder implements INetworkManagementService {
        public static INetworkManagementService asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
