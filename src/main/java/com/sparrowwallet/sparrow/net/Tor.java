package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.TorStatusEvent;
import com.sparrowwallet.sparrow.io.IOUtils;
import com.sparrowwallet.sparrow.io.Storage;
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec;
import io.matthewnelson.kmp.tor.runtime.Action;
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent;
import io.matthewnelson.kmp.tor.runtime.TorRuntime;
import io.matthewnelson.kmp.tor.runtime.core.OnEvent;
import io.matthewnelson.kmp.tor.runtime.core.TorEvent;
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd;
import io.matthewnelson.kmp.tor.runtime.core.net.IPSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Tor {
    private static final Logger log = LoggerFactory.getLogger(Tor.class);

    public static final String TOR_DIR = "tor";
    public static final String WORK_DIR = "work";
    public static final String CACHE_DIR = "cache";
    //Where kmp-tor 1.x unpacked its Tor 0.4.7 binaries, which the network no longer accepts
    public static final String OLD_INSTALL_DIR = ".kmptor";
    public static final String TOR_ADDRESS_SUFFIX = ".onion";

    private static Tor tor;

    //kmp-tor keeps one TorRuntime per work directory, and building another for the same directory
    //hands back the first along with the observers it was built with. So the runtime is built once,
    //and stopping and starting Tor stops and starts its daemon.
    private static TorRuntime runtime;
    private static volatile IPSocketAddress socksAddress;

    private final TorRuntime instance;

    public Tor() {
        this.instance = getRuntime();
    }

    private static synchronized TorRuntime getRuntime() {
        if(runtime == null) {
            runtime = createRuntime();
        }

        return runtime;
    }

    private static TorRuntime createRuntime() {
        File torDir = new File(Storage.getSparrowHome(), TOR_DIR);
        File workDir = new File(torDir, WORK_DIR);
        File cacheDir = new File(torDir, CACHE_DIR);
        File oldInstallDir = new File(workDir, OLD_INSTALL_DIR);
        if(oldInstallDir.exists()) {
            IOUtils.deleteDirectory(oldInstallDir);
        }

        TorRuntime.Environment env = TorRuntime.Environment.Builder(workDir, cacheDir, ResourceLoaderTorExec::getOrCreate);
        env.debug = true;

        return TorRuntime.Builder(env, b -> {
            for(RuntimeEvent<?> event : RuntimeEvent.entries()) {
                if(event instanceof RuntimeEvent.ERROR) {
                    b.observerStatic((RuntimeEvent.ERROR)event, OnEvent.Executor.Immediate.INSTANCE, data -> {
                        log.error("Tor error", data);
                        EventManager.get().post(new TorStatusEvent(data.getMessage()));
                    });
                } else if(event instanceof RuntimeEvent.LOG.WARN) {
                    b.observerStatic((RuntimeEvent.LOG.WARN)event, OnEvent.Executor.Immediate.INSTANCE, data -> {
                        log.warn(data.toString());
                        EventManager.get().post(new TorStatusEvent(data.toString()));
                    });
                } else if(event instanceof RuntimeEvent.LOG.INFO) {
                    b.observerStatic((RuntimeEvent.LOG.INFO)event, OnEvent.Executor.Immediate.INSTANCE, data -> {
                        log.info(data.toString());
                        EventManager.get().post(new TorStatusEvent(data.toString()));
                    });
                } else if(event instanceof RuntimeEvent.LOG.DEBUG) {
                    b.observerStatic((RuntimeEvent.LOG.DEBUG)event, OnEvent.Executor.Immediate.INSTANCE, data -> {
                        log.debug(data.toString());
                        EventManager.get().post(new TorStatusEvent(data.toString()));
                    });
                }
            }

            //Tor's own log lines, including the "Bootstrapped n%" notices the splash screen shows
            b.observerStatic(TorEvent.NOTICE.INSTANCE, OnEvent.Executor.Immediate.INSTANCE, data -> {
                log.info(data);
                EventManager.get().post(new TorStatusEvent(data));
            });
            b.observerStatic(TorEvent.WARN.INSTANCE, OnEvent.Executor.Immediate.INSTANCE, data -> {
                log.warn(data);
                EventManager.get().post(new TorStatusEvent(data));
            });
            b.observerStatic(TorEvent.ERR.INSTANCE, OnEvent.Executor.Immediate.INSTANCE, data -> {
                log.error(data);
                EventManager.get().post(new TorStatusEvent(data));
            });

            b.observerStatic(RuntimeEvent.LISTENERS.INSTANCE, OnEvent.Executor.Immediate.INSTANCE, listeners -> {
                socksAddress = listeners.socks.isEmpty() ? null : listeners.socks.iterator().next();
            });

            b.required(TorEvent.ERR.INSTANCE);
            b.required(TorEvent.WARN.INSTANCE);
        });
    }

    /** Where Tor keeps its state, and the lock a second Tor cannot take while another holds it */
    static File getDataDir() {
        return new File(new File(new File(Storage.getSparrowHome(), TOR_DIR), WORK_DIR), "data");
    }

    /**
     * Stops any Tor still running on our data directory. kmp-tor can lose a daemon that hangs (one
     * spun at several cores and stopped answering its control port): it then treats it as stopped,
     * and the next daemon exits at once because it cannot lock the data directory. Call before
     * starting a daemon, off the JavaFX thread; it waits up to ten seconds per stray.
     */
    public static void killStrayDaemons() {
        File dataDir = getDataDir();
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses()
                .filter(process -> process.pid() != self)
                .filter(process -> process.info().arguments().map(args -> holdsDataDir(Arrays.asList(args), dataDir)).orElse(false))
                .forEach(process -> {
                    log.warn("Stopping a Tor process (pid " + process.pid() + ") still running on " + dataDir + ", so a new one can start");
                    process.destroy();
                    try {
                        process.onExit().get(5, TimeUnit.SECONDS);
                    } catch(Exception e) {
                        //A hung Tor can ignore SIGTERM
                        log.warn("Tor process " + process.pid() + " did not stop, killing it");
                        process.destroyForcibly();
                        try {
                            process.onExit().get(5, TimeUnit.SECONDS);
                        } catch(Exception ex) {
                            log.error("Could not stop Tor process " + process.pid(), ex);
                        }
                    }
                });
    }

    /** True when these command line arguments start a Tor on this data directory */
    static boolean holdsDataDir(List<String> args, File dataDir) {
        int index = args.indexOf("--DataDirectory");
        if(index < 0 || index + 1 >= args.size()) {
            return false;
        }
        String path = args.get(index + 1);
        return new File(path).getAbsoluteFile().equals(dataDir.getAbsoluteFile());
    }

    public static Tor getDefault() {
        return Tor.tor;
    }

    public static void setDefault(Tor tor) {
        Tor.tor = tor;
    }

    public TorRuntime getTorRuntime() {
        return instance;
    }

    /**
     * Stops the daemon, calling onStopped once it has stopped or failed to.
     */
    public void stop(Runnable onStopped) {
        instance.enqueue(Action.StopDaemon, throwable -> {
            log.warn("Error stopping Tor daemon", throwable);
            onStopped.run();
        }, unit -> onStopped.run());
    }

    public void changeIdentity() {
        instance.enqueue(TorCmd.Signal.NewNym.INSTANCE, throwable -> {
            log.warn("Failed to signal newnym", throwable);
        }, success -> {
            log.info("Signalled newnym for new Tor circuit");
        });
    }

    public Proxy getProxy() {
        IPSocketAddress address = socksAddress;
        if(address == null) {
            throw new IllegalStateException("Tor socks proxy is not yet instantiated");
        }

        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(address.address.value, address.port.value));
    }

    public HostAndPort getProxyHostAndPort() {
        IPSocketAddress address = socksAddress;
        if(address == null) {
            throw new IllegalStateException("Tor socks proxy is not yet instantiated");
        }

        return HostAndPort.fromParts(address.address.value, address.port.value);
    }

    public static boolean isRunningExternally() {
        try(ServerSocket ignored = new ServerSocket(9050, 1, InetAddress.getLoopbackAddress())) {
            return false;
        } catch(IOException e) {
            return true;
        }
    }
}
