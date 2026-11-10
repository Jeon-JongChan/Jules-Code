import com.sun.jdi.Bootstrap;
import com.sun.jdi.Method;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.MethodEntryRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 실제 앱의 JDWP 메서드 진입 이벤트를 수집해 그래픽 라이브러리 호출 여부를 기록합니다. */
public final class NativePathTracer {
    /** 지정 포트에 연결하고 종료 신호까지 대상 패키지와 양성 대조군 호출을 집계합니다. */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow();
        var connection = connector.defaultArguments();
        connection.get("hostname").setValue("127.0.0.1");
        connection.get("port").setValue(Integer.toString(port));
        connection.get("timeout").setValue("20000");
        VirtualMachine vm = connector.attach(connection);
        Map<Method, Long> counts = new LinkedHashMap<>();
        String started = Instant.now().toString();
        String termination = "stop_file";
        try {
            String[] filters = args.length > 2 && args[2].equals("target-only")
                    ? new String[] { "androidx.graphics.path.*", "androidx.compose.ui.graphics.AndroidPathIterator*" }
                    : new String[] {
                    "androidx.graphics.path.*",
                    "androidx.compose.ui.graphics.AndroidPathIterator*",
                    "android.graphics.Path"
            };
            for (String filter : filters) {
                MethodEntryRequest request = vm.eventRequestManager().createMethodEntryRequest();
                request.addClassFilter(filter);
                // 앱을 중단시키지 않고 관심 클래스의 진입 이벤트만 전달받습니다.
                request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
                request.enable();
            }
            Files.writeString(output.resolve("ready.txt"), started, StandardCharsets.UTF_8);
            System.out.println("추적기 연결 완료: Graphics Path 및 Android Path 진입 감시");
            System.out.flush();
            vm.resume();
            long deadline = System.nanoTime() + 300_000_000_000L;
            while (!Files.exists(output.resolve("trace.stop"))) {
                if (System.nanoTime() > deadline) {
                    termination = "timeout";
                    break;
                }
                EventSet events = vm.eventQueue().remove(500);
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof MethodEntryEvent entry) counts.merge(entry.method(), 1L, Long::sum);
                }
                events.resume();
            }
            // 디버거가 연결된 상태에서 메서드 메타데이터를 저장한 뒤 연결을 해제합니다.
            StringBuilder rows = new StringBuilder("group\tnative\tcount\tclass\tmethod\tsignature\n");
            long graphicsEntries = 0, graphicsNative = 0, composeEntries = 0, controlEntries = 0, controlNative = 0;
            for (var entry : counts.entrySet()) {
                Method method = entry.getKey();
                String className = method.declaringType().name();
                long count = entry.getValue();
                String group;
                if (className.startsWith("androidx.graphics.path.")) {
                    group = "graphics_path";
                    graphicsEntries += count;
                    if (method.isNative()) graphicsNative += count;
                } else if (className.startsWith("androidx.compose.")) {
                    group = "compose_iterator";
                    composeEntries += count;
                } else {
                    group = "control_android_path";
                    controlEntries += count;
                    if (method.isNative()) controlNative += count;
                }
                rows.append(group).append('\t').append(method.isNative()).append('\t').append(count)
                        .append('\t').append(className).append('\t').append(method.name())
                        .append('\t').append(method.signature()).append('\n');
            }
            Files.writeString(output.resolve("method-entries.tsv"), rows, StandardCharsets.UTF_8);
            String summary = "{\"startedUtc\":\"" + started + "\",\"termination\":\"" + termination
                    + "\",\"graphicsPathEntries\":" + graphicsEntries
                    + ",\"graphicsPathNativeEntries\":" + graphicsNative
                    + ",\"composeIteratorEntries\":" + composeEntries
                    + ",\"controlPathEntries\":" + controlEntries
                    + ",\"controlNativeEntries\":" + controlNative + "}";
            Files.writeString(output.resolve("summary.json"), summary, StandardCharsets.UTF_8);
            System.out.println(summary);
        } catch (VMDisconnectedException error) {
            throw new IllegalStateException("앱 연결이 끊겨 완전한 추적 결과를 얻지 못했습니다.", error);
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
        }
    }
}
