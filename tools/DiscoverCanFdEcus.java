import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import com.bydmate.app.helper.dtc.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded, separate discovery probe. Does not broaden the production scanner's whitelist. */
public class DiscoverCanFdEcus extends ReadCanFdDtc {
    static final List<byte[]> previous = new ArrayList<>();
    static long deadline;
    static int sent;

    static void gate() {
        if (!CanFdDiagnosticCache.INSTANCE.supports(DiscoverCanFdEcus::property)) throw new IllegalStateException("firmware");
        if (!Integer.valueOf(1).equals(readInt(1011, 555745336))) throw new IllegalStateException("not_park");
        String reason = CanFdDtcReadOnlyProtocol.INSTANCE.gate(readInt(1032, 1128267824), property("persist.sys.byd.otaupdate"));
        if (reason != null) throw new IllegalStateException(reason);
        if (!property("sys.diagmgrsrv.task_bk").isEmpty()) throw new IllegalStateException("diag_manager");
        if (System.nanoTime() > deadline) throw new IllegalStateException("budget");
    }

    static byte[] meaningful(byte[] frame) {
        if (frame.length < 7) return frame;
        return Arrays.copyOf(frame, Math.min(frame.length, 7 + (frame[3] & 255)));
    }

    static void foreign(byte[] frame) {
        byte[] payload = meaningful(frame);
        for (byte[] old : previous) if (Arrays.equals(old, payload)) return;
        throw new IllegalStateException("foreign_frame=" + hex(payload));
    }

    static DtcMatch query(DtcDevice device, int address, int service, int sub) throws Exception {
        gate();
        byte[] queued;
        while ((queued = device.poll(0)) != null) foreign(queued);
        // Only DTC count/list or the two fixed identity DIDs. No caller-supplied UDS.
        byte[] request;
        if (service == 0x19 && sub == 1) request = new byte[]{1, (byte)(address >> 8), (byte)address, 3, 0x19, 1, (byte)255};
        else if (service == 0x19 && sub == 2) request = new byte[]{1, (byte)(address >> 8), (byte)address, 3, 0x19, 2, 0x0D};
        else if (service == 0x22 && (sub == 0xF197 || sub == 0xF194))
            request = new byte[]{1, (byte)(address >> 8), (byte)address, 3, 0x22, (byte)0xF1, (byte)sub};
        else throw new IllegalArgumentException("not_a_read");
        if (device.send(request) != 0) throw new IllegalStateException("transport_rejected");
        sent++;
        long until = System.nanoTime() + 2_500_000_000L;
        DtcMatch result = null;
        while (System.nanoTime() < until) {
            byte[] frame = device.poll(Math.max(1, (until - System.nanoTime()) / 1_000_000));
            if (frame == null) break;
            DtcMatch match = DtcWire.INSTANCE.match(CanFdDtcReadOnlyProtocol.INSTANCE.normalize(frame), address, service, sub);
            if (match == DtcMatch.NotMine.INSTANCE) { foreign(frame); continue; }
            previous.add(meaningful(frame));
            if (match == DtcMatch.Pending.INSTANCE) continue;
            result = match; break;
        }
        Thread.sleep(400);
        while ((queued = device.poll(0)) != null) foreign(queued);
        return result;
    }

    static String describe(DtcMatch result) {
        if (result == null) return "no_answer";
        if (result instanceof DtcMatch.Answer) return hex(((DtcMatch.Answer)result).getUds());
        if (result instanceof DtcMatch.Nrc) return "NRC=" + ((DtcMatch.Nrc)result).getCode();
        return result.getClass().getSimpleName();
    }

    public static void main(String[] args) throws Exception {
        final int first = Integer.parseInt(args[0], 16), last = Integer.parseInt(args[1], 16);
        if (first < 0x700 || last > 0x7F7 || last < first || last - first >= 64) throw new IllegalArgumentException("range");
        Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context base = (Context)type.getMethod("getSystemContext").invoke(thread);
        DtcDaemon.INSTANCE.setContext(new ContextWrapper(base) {
            @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        });
        new Thread(() -> {
            DtcDevice device = new DtcVendorDevice(true);
            int exit = 1, answered = 0, checked = 0;
            deadline = System.nanoTime() + 240_000_000_000L;
            try {
                gate();
                String error = device.register();
                if (error != null) throw new IllegalStateException(error);
                if (device.poll(2000) != null) throw new IllegalStateException("passive_traffic");
                for (int address = first; address <= last; address++) {
                    // Functional broadcast and standard ECU response IDs are not physical query targets.
                    if (address == 0x7DF || address >= 0x7E8 && address <= 0x7EF) continue;
                    DtcMatch count = query(device, address, 0x19, 1);
                    checked++;
                    if (count instanceof DtcMatch.Answer || count instanceof DtcMatch.Nrc || count instanceof DtcMatch.Multiframe) {
                        answered++;
                        System.out.println(String.format("FOUND=%03X COUNT=%s", address, describe(count)));
                        System.out.println(String.format("IDENTITY=%03X NAME=%s ID=%s", address,
                            describe(query(device, address, 0x22, 0xF197)), describe(query(device, address, 0x22, 0xF194))));
                        if (count instanceof DtcMatch.Answer) {
                            byte[] uds = ((DtcMatch.Answer)count).getUds();
                            if (uds.length < 6 || (uds[4] & 255) != 0 || (uds[5] & 255) != 0)
                                System.out.println(String.format("DTC_LIST=%03X %s", address, describe(query(device, address, 0x19, 2))));
                        }
                    } else if (count != DtcMatch.GatewayTimeout.INSTANCE && count != null)
                        System.out.println(String.format("UNRESOLVED=%03X %s", address, describe(count)));
                    if (checked % 8 == 0) System.out.println(String.format("PROGRESS=%03X CHECKED=%d", address, checked));
                }
                System.out.println("COMPLETE CHECKED=" + checked + " ANSWERED=" + answered + " REQUESTS=" + sent);
                exit = 0;
            } catch (Throwable error) { System.out.println("STOP=" + error); }
            finally { try { device.unregister(); System.out.println("UNREGISTERED"); } catch (Throwable error) {} }
            System.exit(exit);
        }, "ecu-discovery-read-only").start();
        Looper.loop();
    }
}
