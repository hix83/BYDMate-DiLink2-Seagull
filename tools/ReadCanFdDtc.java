import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import com.bydmate.app.helper.dtc.*;
import com.bydmate.app.data.dtc.*;

/** Small app_process harness for the production scanner. Only the exact inspected firmware is allowed. */
public class ReadCanFdDtc {
    static Integer readInt(int device, int feature) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "autoservice");
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(device); data.writeInt(feature);
            if (!service.transact(5, data, reply, 0) || reply.dataAvail() < 8 || reply.readInt() != 0) return null;
            return reply.readInt();
        } catch (Exception error) { return null; }
        finally { data.recycle(); reply.recycle(); }
    }

    static String property(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, name);
        } catch (Exception error) { return ""; }
    }

    static String hex(byte[] bytes) {
        if (bytes == null) return "-";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 256); i++) text.append(String.format("%02X", bytes[i]));
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        if (!CanFdDiagnosticCache.INSTANCE.supports(ReadCanFdDtc::property)) {
            System.out.println("UNSUPPORTED_FIRMWARE"); System.exit(1);
        }
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context base = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        DtcDaemon.INSTANCE.setContext(new ContextWrapper(base) {
            @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        });
        DtcDaemon.INSTANCE.setOtaState(() -> readInt(1032, 1128267824));
        new Thread(() -> {
            int exit = 1;
            try {
                DtcScanner scanner = DtcDaemon.INSTANCE.getScanner();
                System.out.println("START=" + scanner.start(() -> readInt(1011, 555745336)));
                long deadline = System.nanoTime() + 100_000_000_000L;
                DtcScanStatus status;
                do {
                    Thread.sleep(1000);
                    status = scanner.status();
                } while (status.getState() == DtcWire.STATE_RUNNING && System.nanoTime() < deadline);
                System.out.println("STATE=" + status.getState() + " DONE=" + status.getDone() + " REASON=" + status.getReason());
                for (DtcAddressResult result : status.getResults()) {
                    System.out.println(String.format("ECU=%03X OUTCOME=%s COUNT=%s LIST=%s ID=%s",
                        result.getAddr(), result.getOutcome(), hex(result.getCountUds()), hex(result.getListUds()), hex(result.getIdUds())));
                }
                DtcScanSummary summary = DtcParser.INSTANCE.summarize(status.getResults());
                System.out.println("ANSWERED=" + summary.getAnswered() + "/" + summary.getTotal()
                    + " NOW=" + summary.getNow() + " OLD=" + summary.getOld() + " PENDING=" + summary.getPending()
                    + " UNREAD=" + summary.getUnreadBlocks());
                exit = status.getState() == DtcWire.STATE_DONE ? 0 : 1;
            } catch (Throwable error) { error.printStackTrace(); }
            System.exit(exit);
        }, "canfd-read-only-probe").start();
        Looper.loop();
    }
}
