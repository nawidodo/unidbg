package com.github.unidbg.headless;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.backend.HypervisorFactory;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.debugger.Debugger;
import com.github.unidbg.debugger.McpTool;
import com.github.unidbg.debugger.McpToolkit;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.MachOLoader;
import com.github.unidbg.ios.MachOModule;
import com.github.unidbg.ios.ipa.EmulatorConfigurator;
import com.github.unidbg.ios.ipa.IpaLoader;
import com.github.unidbg.ios.ipa.IpaLoader64;
import com.github.unidbg.ios.ipa.LoadedIpa;

import java.io.File;
import java.io.PrintStream;

/**
 * Load one arm64 IPA and keep its emulator available through the unidbg MCP server.
 * The IPA path is runtime configuration; the shaded launcher can be reused unchanged.
 */
public final class IpaMcpHeadless implements EmulatorConfigurator {

    private Emulator<DarwinFileIO> emulator;
    private LoadedIpa loaded;
    private Module executable;
    private Options options;

    public static void main(String[] args) throws Exception {
        final Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        if (options.help) {
            printUsage();
            return;
        }
        PrintStream protocolOutput = null;
        if ("stdio".equals(options.transport)) {
            protocolOutput = System.out;
            System.setOut(System.err);
        }
        try {
            new IpaMcpHeadless().run(options, protocolOutput);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private void run(Options options, PrintStream protocolOutput) throws Exception {
        this.options = options;
        if (!options.ipa.isFile()) {
            throw new IllegalArgumentException("IPA not found: " + options.ipa.getAbsolutePath());
        }

        File rootfs = options.rootfs != null ? options.rootfs : defaultRootfs(options.ipa);
        IpaLoader loader = new IpaLoader64(options.ipa, rootfs);
        loader.useOverrideResolver();
        addBackends(loader, options.backend);

        try {
            loaded = loader.load(this);
            emulator = loaded.getEmulator();
            executable = loaded.getExecutable();

            System.out.println("Executable: " + executable.name);
            System.out.printf("Base: 0x%x%n", executable.base);
            System.out.println("Bundle ID: " + loaded.getBundleIdentifier());
            System.out.println("Bundle version: " + loaded.getBundleVersion());
            System.out.println("RootFS: " + rootfs.getAbsolutePath());

            Debugger debugger = emulator.attach();
            if (options.breakOffset != null) {
                long address = ((MachOModule) executable).machHeader + options.breakOffset.longValue();
                debugger.addBreakPoint(address);
                System.out.printf(
                        "Breakpoint: 0x%x (image offset 0x%x)%n",
                        address,
                        options.breakOffset
                );
            }

            if (options.mcpPort != null) {
                McpToolkit toolkit = createToolkit();
                if (options.runEntry) {
                    toolkit.setDefaultTool("runEntry");
                }
                if ("stdio".equals(options.transport)) {
                    debugger.startMcpStdioServer(protocolOutput);
                } else {
                    debugger.startMcpServer(options.mcpPort.intValue());
                }
                toolkit.run(debugger);
            } else if (options.runEntry) {
                loaded.callEntry();
            } else {
                System.out.println("IPA loaded; entry point skipped.");
            }
        } finally {
            if (emulator != null) {
                emulator.close();
            }
        }
    }

    private McpToolkit createToolkit() {
        final McpToolkit toolkit = new McpToolkit();
        toolkit.addTool(new McpTool() {
            @Override
            public String name() {
                return "headlessIdle";
            }

            @Override
            public String description() {
                return "Keep the loaded IPA available for MCP inspection without running its entry point";
            }

            @Override
            public String[] paramNames() {
                return new String[0];
            }

            @Override
            public void execute(String[] params) {
                System.out.println("IPA ready for MCP requests");
            }
        });
        toolkit.addTool(new McpTool() {
            @Override
            public String name() {
                return "runEntry";
            }

            @Override
            public String description() {
                return "Run the loaded IPA entry point; call again to repeat it";
            }

            @Override
            public String[] paramNames() {
                return new String[0];
            }

            @Override
            public void execute(String[] params) {
                System.out.println("Starting IPA entry point");
                loaded.callEntry();
            }
        });
        return toolkit;
    }

    private static void addBackends(IpaLoader loader, String backend) {
        if ("unicorn".equals(backend) || "unicorn2".equals(backend)) {
            loader.addBackendFactory(new Unicorn2Factory(true));
        } else if ("dynarmic".equals(backend)) {
            loader.addBackendFactory(new DynarmicFactory(true));
            return;
        } else if ("auto".equals(backend) || "hypervisor".equals(backend)) {
            loader.addBackendFactory(new HypervisorFactory(true));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported backend '" + backend + "'; use auto, hypervisor, dynarmic, or unicorn2"
            );
        }
        loader.addBackendFactory(new DynarmicFactory(true));
    }

    private static File defaultRootfs(File ipa) {
        String name = ipa.getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0) {
            name = name.substring(0, extension);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty()) {
            name = "ipa";
        }
        return new File(new File("target", "rootfs"), name);
    }

    @Override
    public void configure(
            Emulator<DarwinFileIO> emulator,
            String executableBundlePath,
            File rootDir,
            String bundleIdentifier) {
        if (!options.callInit) {
            ((MachOLoader) emulator.getMemory()).disableCallInitFunction();
        }
        System.out.println("Executable path: " + executableBundlePath);
    }

    @Override
    public void onExecutableLoaded(
            Emulator<DarwinFileIO> emulator,
            MachOModule executable) {
        System.out.printf("Mach-O loaded: %s @ 0x%x%n", executable.name, executable.base);
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java -jar unidbg-ios-headless-*.jar [options] <ipa>\n" +
                "\n" +
                "Load an arm64 IPA once, then expose the emulator through MCP.\n" +
                "The IPA path is runtime input; invoke this same jar for every IPA.\n" +
                "stdio is the default transport; use HTTP only when a URL endpoint is needed.\n" +
                "\n" +
                "Options:\n" +
                "  --ipa PATH             IPA path (or pass it as the final positional argument)\n" +
                "  --rootfs PATH          Root filesystem directory\n" +
                "  --backend NAME         auto, hypervisor, dynarmic, or unicorn2 (default: auto)\n" +
                "  --transport NAME       stdio or http (default: stdio)\n" +
                "  --mcp-port PORT        HTTP MCP port (default: 9239)\n" +
                "  --no-mcp               Load and exit instead of starting MCP\n" +
                "  --run-entry            Run the IPA entry point on startup\n" +
                "  --break-offset VALUE   Add a breakpoint at Mach-O header + image offset\n" +
                "  --no-call-init         Skip Mach-O initializer functions\n" +
                "  -h, --help             Show this help\n"
        );
    }

    private static final class Options {
        private File ipa;
        private File rootfs;
        private String backend = "auto";
        private Integer mcpPort = Integer.valueOf(9239);
        private Long breakOffset;
        private boolean runEntry;
        private boolean callInit = true;
        private boolean help;
        private String transport = "stdio";

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--transport".equals(arg)) {
                    options.transport = requireValue(args, ++i, arg).toLowerCase();
                    continue;
                }
                if ("-h".equals(arg) || "--help".equals(arg)) {
                    options.help = true;
                    return options;
                }
                if ("--no-mcp".equals(arg)) {
                    options.mcpPort = null;
                    continue;
                }
                if ("--run-entry".equals(arg)) {
                    options.runEntry = true;
                    continue;
                }
                if ("--no-call-init".equals(arg)) {
                    options.callInit = false;
                    continue;
                }
                if ("--ipa".equals(arg)) {
                    options.ipa = new File(requireValue(args, ++i, arg));
                    continue;
                }
                if ("--rootfs".equals(arg)) {
                    options.rootfs = new File(requireValue(args, ++i, arg));
                    continue;
                }
                if ("--backend".equals(arg)) {
                    options.backend = requireValue(args, ++i, arg).toLowerCase();
                    continue;
                }
                if ("--mcp-port".equals(arg)) {
                    options.mcpPort = parsePort(requireValue(args, ++i, arg));
                    continue;
                }
                if ("--break-offset".equals(arg)) {
                    options.breakOffset = parseNumber(requireValue(args, ++i, arg), arg);
                    continue;
                }
                if (arg.startsWith("--ipa=")) {
                    options.ipa = new File(arg.substring("--ipa=".length()));
                    continue;
                }
                if (arg.startsWith("--rootfs=")) {
                    options.rootfs = new File(arg.substring("--rootfs=".length()));
                    continue;
                }
                if (arg.startsWith("--backend=")) {
                    options.backend = arg.substring("--backend=".length()).toLowerCase();
                    continue;
                }
                if (arg.startsWith("--mcp-port=")) {
                    options.mcpPort = parsePort(arg.substring("--mcp-port=".length()));
                    continue;
                }
                if (arg.startsWith("--break-offset=")) {
                    options.breakOffset = parseNumber(arg.substring("--break-offset=".length()), "--break-offset");
                    continue;
                }
                if (arg.startsWith("--transport=")) {
                    options.transport = arg.substring("--transport=".length()).toLowerCase();
                    continue;
                }
                if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                }
                if (options.ipa != null) {
                    throw new IllegalArgumentException("Only one IPA may be specified");
                }
                options.ipa = new File(arg);
            }
            if (!"stdio".equals(options.transport) && !"http".equals(options.transport)) {
                throw new IllegalArgumentException("Transport must be stdio or http");
            }
            if (options.ipa == null) {
                throw new IllegalArgumentException("An IPA path is required");
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static Integer parsePort(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("MCP port must be between 1 and 65535");
                }
                return Integer.valueOf(port);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid MCP port: " + value, e);
            }
        }

        private static Long parseNumber(String value, String option) {
            try {
                return Long.decode(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid value for " + option + ": " + value, e);
            }
        }
    }
}
